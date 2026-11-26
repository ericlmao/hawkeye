# Hawkeye architecture

Hawkeye records what happens to a Paper/Folia world (blocks, containers, items, kills, signs, chat,
commands, sessions) and lets staff look it up, inspect blocks, roll changes back and restore them,
on one server or across a network of servers. This document is the design contract: the storage
format, the threading model and the multi-server protocol. Code follows it; change it in the same
commit as the code.

## Goals, measured against CoreProtect

| Concern | CoreProtect (PlayPro + the ericlmao fork) | Hawkeye |
|---|---|---|
| Code structure | Static state (`ConfigHandler` has 132 public statics), classes of 1,000-3,900 lines, hand-parsed commands, raw `Thread`s | Spring beans with constructor injection, layered packages checked by ArchUnit, Cloud 2 annotation commands, owned executors, no class near 1,000 lines |
| Row size | `time int` (overflows 2038), separate x/y/z, `rowid`, 3+ B-tree indexes per table | One 64-bit id carries the second, the server and the rank in that second; one 64-bit packed position; two secondary indexes |
| Old data | The fork packs cold SQLite data into segments; MySQL and the other engines keep full rows | Every engine packs settled events into immutable segments of 262,144 events (archive format 2) read by byte range. A real history of 1.40 billion events that takes 3.40 GB in the fork's SQLite database takes 1.52 GB in Hawkeye's: 1.09 bytes per event against 2.43 (`ReplayBenchmark`) |
| Storage engines | Four engines and four query builders (DuckDB, ClickHouse, SQLite segments, MySQL) | One schema, one query builder, three dialects (SQLite, MySQL/MariaDB, PostgreSQL) behind a small `Dialect` seam |
| Multi-server | One writer per database; id allocation is an in-memory counter that races under several writers | Ids are generated locally and never collide; dictionaries are insert-or-get against unique keys; any number of servers share one database |
| Sharding | None | Each server writes to its home shard; network lookups fan out to every shard and merge by id |
| Network operations | None | Durable network jobs (rollback/restore on other servers) with a Redis or database-polling bus |
| Freshness | Lookups miss events still in the 500 ms queue | Every lookup first flushes the local queue (read-your-writes) |
| Durability | Queue lost on crash or database outage | Events stay queued through outages with retry and back-off, and are spooled to disk on shutdown when the database is unreachable |

## Storage format

### Event id (`util.event.EventIds`)

Every event has a 64-bit id, issued on the server that captured it, without a database round trip:

```
 63      62 ............................ 22   21 ........ 12   11 ........ 0
 [0]     [ milliseconds since 2025-01-01Z ]   [ node id    ]   [ sequence  ]
          41 bits (until 2094)                 10 bits          12 bits
```

- The id is the primary key and the time column at once: a time window is an id range
  (`EventIds.floor(t)` .. `EventIds.ceiling(t)`), and `ORDER BY id DESC` is newest-first.
- The node id is the server's registration number in its shard (`hawkeye_server`, 1..1023), so two
  servers never produce the same id, and ids from all servers interleave by time.
- **The one-second grid.** Capture only stamps events (`[time][sequence]`, capture order). The writer
  (`core.queue.id.IdAssigner`) buffers them by second (their *slot*) until the second has closed (plus
  250 ms of grace), sorts each slot's events in `util.event.EventOrder` and numbers them 0, 1, 2, ... (their
  *rank*). The rank fills the sequence bits and then the millisecond bits, so an id is
  `slot * 1000 ms + rank` and is never more than a second off; a slot holds 4,096,000 events.
- **The order inside a second.** Events about a player rather than a place (chat, commands, books,
  game modes, sessions, inventory snapshots; action codes 40..59) come first, in capture order; every
  other event follows by world name, packed position and capture order. The archive stores rows in
  exactly this order, so it rebuilds every rank from the stored rows and never stores one. Changes to
  one block keep their order, and a conversation reads in order.
- Late events of a slot continue its numbering (the counters of the last 120 slots are kept); the
  writer starts after the newest stored slot on restart, so no id is ever issued twice.

### Packed position (`util.position.BlockPositions`)

```
 63 ............ 42   41 ............ 20   19 .. 16   15 .. 12   11 ...... 0
 [ chunk x, signed ]  [ chunk z + 2^21 ]   [ x & 15 ]  [ z & 15 ]  [ y + 2048 ]
       22 bits             22 bits           4 bits      4 bits      12 bits
```

- Covers the whole world border (±33.5 million blocks) and y from -2048 to 2047.
- Chunk-major ordering: every block of a chunk column is one contiguous range, and a run of chunks
  with the same chunk x is also one contiguous range. A radius lookup is therefore
  `2 * ceil(r / 16) + 1` index range scans (3 for r = 10, 15 for r = 100), not a scan of the x slab.
- An exact block lookup is one index seek on `(world, pos)`.

### Tables

All tables carry the configured prefix (default `hawkeye_`).

| Table | Columns | Keys |
|---|---|---|
| `schema` | `version` | Migration bookkeeping (`core.sql.Migrations`) |
| `server` | `id SMALLINT`, `name`, `scope`, `last_seen` (0 for an import node) | PK `id`, unique `name` |
| `world` | `id INT`, `scope`, `name` | unique `(scope, name)` |
| `actor` | `id INT`, `kind`, `key`, `name`, `name_lower` | unique `key`, index `name_lower` |
| `type` | `id INT`, `value` | unique `value` |
| `event` | `id BIGINT`, `world INT`, `pos BIGINT`, `actor INT`, `action SMALLINT`, `subject INT`, `secondary INT NULL`, `amount INT NULL`, `flags SMALLINT`, `data BLOB NULL` | PK `id`, index `(world, pos)`, index `(actor)` |
| `job` | network jobs (see below) | PK `id`, index `(target, status)` |
| `lease` | `name`, `holder`, `expires` | PK `name` |
| `segment` | `id` (auto-increment, never reused), `first_id BIGINT`, `last_id BIGINT`, `events INT`, `header` (small blob), `body` (SQLite `BLOB`, MySQL `LONGBLOB`, PostgreSQL `BYTEA` stored `EXTERNAL`) | PK `id`, indexes `(last_id, id, first_id)` (reading order, and covering for every segment's id range) and `(first_id)` |
| `segment_flag` | `segment`, `ordinals` (blob: ascending ordinals of the rolled-back rows, delta varints) | PK `segment` |
| `region_segment` | `world INT`, `region BIGINT`, `newest BIGINT`, `body` (a compressed frame) | PK `(world, region)`, index `(newest)` |
| `block_segment` | `world INT`, `chunk BIGINT`, `newest BIGINT`, `body` (a compressed frame) | PK `(world, chunk)`, index `(newest)` |
| `actor_segment` | `actor INT`, `newest BIGINT`, `body` (raw `IdRuns` bytes) | PK `actor`, index `(newest)` |
| `segment_index` | `id` (always 1), `indexed_from BIGINT` | PK `id` |
| `import_state` | `source` (up to 191 characters), `revision BIGINT`, `state` (text; MySQL `MEDIUMTEXT`), `updated BIGINT` | PK `source` |

- **Dictionaries.** Worlds, actors and types are interned to small integers. `type` holds every
  namespaced value an event refers to: block states (`minecraft:oak_stairs[facing=east,...]`), item
  materials (`minecraft:diamond`) and entity types (`minecraft:zombie`); the action says which.
  Dictionary rows are immutable, so every server caches them forever and no invalidation traffic
  exists. A new value is `INSERT ... ON CONFLICT DO NOTHING` followed by a select, which is safe
  with any number of concurrent writers.
- **World scope.** A world is keyed by `(scope, name)`. The scope defaults to the server name, so
  `world` on `survival` and `world` on `creative` are different worlds. Servers that share world
  files (MultiPaper) configure the same scope.
- **Actors.** Players are keyed by UUID (`kind = PLAYER`) and keep their latest name; non-player
  causes are keyed by a `#name` (`#tnt`, `#creeper`, `#fire`, `#water`, `#piston`, ...). Imported
  history creates missing actors but never renames a known one (`ActorRepository.internKeepingNames`):
  the source's name of a player may be older than the one the live writer has seen.
- **`subject` / `secondary`.** For block actions `subject` is the block the action is about (the
  broken block for a break, the placed block for a place) and `secondary` is the other side of the
  change (the block left behind after a break, the block replaced by a place). Rollback restores
  `subject` for a break and `secondary` for a place; restore does the opposite. For item actions
  `subject` is the material and `amount` the count; for kills it is the entity type. Container
  actions 10 and 11 (a container block or an entity carrying one) are reversible; 12 and 13 (an ender
  chest, a plugin's inventory, another player's inventory: recorded where the player stood) are
  history only, so a rollback never changes the world for them.
- **`flags`.** Bit 0: rolled back. Archived events keep it in `segment_flag` instead. An event can be
  written already rolled back (`PendingEvent.rolledBack`: imported history whose source had undone it).
- **`data`.** An optional payload, tagged by its first byte (`util.codec.PayloadCodec`): text (chat,
  commands), sign sides (before and after), an item (only when the item has components), an inventory
  snapshot (a broken container), an entity (for kill and removal rollback), or a player inventory
  snapshot (tag 7: a detail text such as where a teleport went or the death message, then the
  non-empty slots by `PlayerInventory#getContents()` index, 41 for the cursor; `amount` holds the item
  count), or a virtual container item (tag 8: the inventory's label, then the item's raw NBT as the
  rest of the body, empty for a plain stack). Items and entities are
  the NBT of Paper's version-safe serialization (it carries the data version, so the server's data
  fixers upgrade old rows), stored uncompressed (`util.serialize.ItemBytes`, `util.serialize.EntityBytes`) so the archive,
  which compresses many rows together, finds what repeats between snapshots; rows in Paper's own gzip
  form (from earlier builds) read the same. Entity snapshots are normalized first
  (`util.serialize.EntitySnapshots`): what a respawn resets, re-rolls or takes from the event row is stripped.
  Large text and sign payloads are deflated; nothing else is compressed per row.

### Archive

The `event` table is the hot tier: every row individually indexed, cheap to insert and to flag. The
cold tier is `segment`: immutable segments of archive format 2 (`core.storage.segment`), written
by `core.storage.archive.Archive` under the `archive` lease (one server per shard).

- **Packing.** Every `archive.interval-minutes` (1) a run packs the `archive.segment-events` (262,144)
  oldest hot events that are older than `archive.settle-seconds` (60) into one segment and deletes
  them from `event`, in one transaction, and repeats until nothing is packed. Fewer events are packed
  only once the oldest is older than `archive.after-hours` (24), so a quiet server is archived too, in
  smaller segments. A segment holds any worlds and servers; segments are time slices whose id ranges
  can overlap (a late event is packed after its neighbours). Packing compresses on the calling thread;
  a bulk import (`EventStore.writeArchived`) compresses on a pool.
- **Stored order.** Unplaced rows by id, then placed rows by (world index in the world order,
  position, id); the world order is the worlds by (scope, name), the order the writer ranks in. Rows
  are numbered 0..n-1 in this order (*ordinals*) and cut into *units* of 16,384 rows; unplaced and
  placed rows never share a unit.
- **Header** (uncompressed, about 1-2 KB for a full segment, cached parsed by segment id): version
  (2), counts, base slot, the world, node and actor lists, an action mask, every distinct chunk of
  the placed rows, every distinct region (32 x 32 chunks, `chunk >> 5`) of the unplaced rows as
  sorted, delta-coded (world index, region x, region z) entries, the lengths of the shared frames
  and, per unit, its row count, first key, frame lengths, actors, action mask and slot range. From it
  a reader decides whether the segment and which units can match an `EventFilter`, and where their
  bytes are: placed units by the chunk list and their first keys, unplaced units by the region list
  (a world or area that reaches none of those regions skips them). The byte layout is in the
  `SegmentHeader` Javadoc.
- **Body.** Frames (`util.codec.binary.Compression`: stored, zstd level 19, or deflate level 9 when zstd's
  native library does not load). First the shared frames: per row the slot delta, the node index (with
  several nodes), the rank difference from the expected rank (omitted when all are 0, the normal case
  for ids the writer issued), and the actor and type palettes. Then per unit a scalar frame (world,
  chunk x/z deltas, local x/z byte, y delta, actor, action, subject, secondary, amount, data length)
  and a payload frame.
- **Reading.** Candidates are found by id range on `last_id` (header and flags, never the body), in
  reading order (`last_id DESC` or `first_id ASC`, keyset paged). Each header selects the units that
  can match; a read then has two steps. (1) One statement fetches the selected units by `substr` on the
  body, touching ranges merged, plus the palette frame (the small last shared frame) when the filter
  names actors or types and the palettes are not cached. Each unit's scalar frame is decompressed and
  only the columns the filter needs are decoded into primitive arrays; the column rules (world, area
  and bounds, actors, actions, types, rolled-back flag: `EventFilter.matchesColumns`) give the
  matching row numbers. No row object is built and no payload decompressed. (2) Only when a row
  matched are the segment's ids obtained (the decoded shared frames, cached by weight, about 32 MB,
  else fetched and decoded), the id rules applied (`EventFilter.matchesId`: id range and nodes, and
  the keyset cursor) and rows built for the survivors; a unit's payload frame is decompressed only
  when a surviving row has data. A segment whose ids all pass the id rules (id range inside the
  filter's, beyond the cursor, its one node listed) skips the per-row id check, and a count of such a
  segment counts column matches without computing ids. `EventFilter.matches` is the Java twin of the
  SQL predicate (`EventFilterTest` checks they agree); both steps use its rule methods. A page stops
  reading segments once none can beat its worst event (by `first_id`/`last_id`).
- **Counting.** `Archive.count` takes a segment budget: it reads candidates newest first and counts
  at most that many segment bodies (a candidate whose header selects no unit costs nothing); when the
  budget is spent with a candidate left that needs its body, the count stops and is a lower bound
  (`EventCount.exact` false). The hot table's SQL count is always exact, and a total summed over shards
  is exact only when every shard's is. Opening a lookup or an inspection (`LookupPager.open`) runs the
  first page and a count with a budget of `LookupPager.COUNT_SEGMENTS` (16) side by side, so the reply
  never waits for the whole history; the header and footer then show the total and the page count as
  lower bounds (`lookup.at-least`, "160+"). A session with a lower bound has no page count:
  `/hawkeye page n` walks forward until the rows end (reporting "out of range" only there), and once
  they have ended the session's total is exact. `#count` and `HawkeyeApi#count` count every segment.
  On the 1.40 billion event replay database (2026-11-17, warm, load 6) opening the busy block takes
  81 ms against 3.2 s for its exact count (6.1 million events), radius 5 around it 181 ms against
  7.8 s (16.1 million), a block with little history 51-133 ms against 0.2-1.0 s, a block never
  touched 1.4 ms (exact either way); page 1 is never below what it shows.
- **Flags.** A rollback of archived events maps their ids to ordinals (from the shared frames) and
  rewrites the segment's `segment_flag` row under a lock on the segment row; a body is never rewritten
  for a flag.
- **Purging.** A segment entirely older than the bound is deleted with its flags. A segment that
  straddles it, or that holds purged worlds next to others, is decoded with its flags, re-encoded
  without the purged rows as a new segment and the old one deleted. At most 8 segments per batch.

Measured on 2.43 million replayed real events (bytes per event):

| Layout | B/event |
|---|---|
| CoreProtect fork reference (rows by chunk, player, time; zstd 19 per 8,192 rows) | 2.22 |
| Position order, ranks stored | 2.24 |
| Position order, ranks implied, one frame per 262,144 events | 1.18 |
| ... units of 16,384 rows (the format) | 1.22 |
| ... 1,048,576 events per segment | 1.09 |

On SQLite, `StorageFootprintTest` (60,000 uniformly random block events, the worst case) measures 73
bytes per hot event and 6.2 archived, against 80 for CoreProtect's relational layout.

At full scale (`ReplayBenchmark`, 2026-11-16): every packed row of a real CoreProtect database of the
`ericlmao/CoreProtect` fork (1,398,675,414 rows over 59 days, 98% hopper, piston and block activity,
3.40 GB as that fork's SQLite file) replayed as the events Hawkeye would have captured, numbered by
`IdAssigner` and packed with the default settings:

| | Events | SQLite file | Bytes per event |
|---|---|---|---|
| CoreProtect fork | 1,398,675,414 packed + 181,684 waiting | 3,397,459,968 | 2.43 |
| Hawkeye | 1,398,675,414 | 1,517,871,104 | 1.09 |
| Hawkeye with the segment index | 1,398,675,414 | 1,563,283,456 | 1.12 |

Hawkeye's file is 5,337 segments (1.49 GB of bodies, 19 MB of headers) and 3 MB of dictionaries; the
segment index (below) adds 44.7 MB. By
period, against the fork's packed segments alone (its entity tables, 0.58 GB, come on top): days 1-16
0.93 against 1.76 bytes per event, days 17-27 0.87 against 1.74, days 28-60 (many kills) 1.39 against
1.92. A kill's entity is a stand-in in the replay (the fork's own stored entity, about 15 bytes
packed); Hawkeye's real snapshots measured 6.8 bytes per kill on a Paper server (docs/knowledge).
A running server adds the hot table on top: the events not yet packed and, as free pages, its
high-water mark (about 22 MB at the default segment size).

Lookups on that database are measured under "Segment index" below: with the index a block that was never
touched inside a chunk active in every segment takes under a millisecond instead of 3.9 s, and a
player who is in one or two segments 3 ms instead of 150-225.

### Segment index

A segment's header settles most lookups about a place without reading the segment, but a chunk that
is active all the time (a hopper line, a farm) is in every segment, and a block inside it would cost
one unit read per segment. The segment index (`core.storage.index.SegmentIndex`, the one entry point
`Archive` uses) remembers which segments hold which place, so such a lookup reads only the segments
that can hold the block, and which segments hold which actor, so a lookup by a player without a
place reads only that player's segments instead of every segment's header. Its place part is
`core.storage.index.PlaceIndex` (rows in `core.storage.index.place`), its actor part
`core.storage.index.ActorIndex`; `SegmentIndex` takes the state lock and calls both in the same transaction. Schema version 5 (version
4 had the place index under the names `place_region`, `place_chunk` and `place_state`; version 5
drops them and every segment is indexed again: an index format change takes new table names, so a
build that does not know the new formats fails on its first index statement instead of misreading
rows).

- **Keys.** A chunk key is a packed position without its 20 in-chunk bits (`pos >> 20`: chunk x above
  chunk z + 2^21); a region key drops 5 more bits of each chunk coordinate (32 x 32 chunks, the
  header's regions). Both order like packed positions.
- **`region_segment`.** One row per region that archived rows are in: per chunk (numbered
  `(x & 31) << 5 | (z & 31)`, delta-coded) the ids of the segments holding rows in it, placed and
  unplaced rows alike (a chat line said at a place is found there), as runs of consecutive ids
  (`IdRuns`: start coded against the previous chunk's first id, gaps and lengths as varints), and
  whether the chunk has a `block_segment` row. `newest` is the largest id in the row.
- **`block_segment`.** Only for a chunk in more than `BUSY_SEGMENTS` (16) segments: every position the
  archived rows touched (in-chunk key delta-coded), each with its newest `PlaceChunk.EXACT_SEGMENTS`
  (64) segment ids exactly and only the older ones as *buckets* (`segment id >> 4`, 16 consecutive
  segments). Most positions are in a handful of segments, so a block's lookup reads exactly the
  segments that hold it; a position touched in more segments stays bounded (the first six pages of a
  block lookup come from exact ids, only deep paging and exact counts read bucket candidates). With
  buckets alone, a block with little history in a busy chunk read every segment of its buckets
  (summed over the full replay database's blocks, 3.34 times the segments that hold them); 64 exact
  ids name 1.75 times at 1.6 times the bytes of buckets alone (a study on that database's rows; every
  id exact would be 2.8 times the bytes). The kept set depends only on the ids added, not their order
  (packing adds ascending, catching up descending). Per position: the key delta, a varint
  `(exact run count << 1) | hasOlder`, the exact runs coded against the previous position's first
  exact id, and when `hasOlder` the bucket run count and runs coded against the previous position's
  first bucket. When the row exists it covers every segment the region row lists for the chunk.
  Bodies are zstd level 9 (`LEVEL`): rows are rewritten on every pack, where level 19 costs more time
  than it saves bytes.
- **`actor_segment`.** One row per actor id (the dictionary id) that archived rows have: the ids of the
  segments holding rows of that actor, as `IdRuns` bytes against 0 without a compression frame (rows
  are a few bytes to a few hundred), and `newest`, the largest id. What a segment contributes to the
  index travels as one value (`SegmentContent`: its places and its sorted distinct actor ids).
- **Writing.** `Archive.pack` (compaction, bulk import, purge rewrite) updates the index in the
  transaction that inserts the segment. Every writer first locks the `segment_index` row (`FOR UPDATE`
  on MySQL and PostgreSQL), so two writers never rewrite a row from the same old version. The region
  rows of the segment's chunks get its id, and so do the actor rows of every actor it holds (the
  distinct actors of the packed rows; the header's actor list when catching up: the same set); a chunk that now crosses `BUSY_SEGMENTS` gets a
  `block_segment` row built from all its segments (their units for the chunk are read and only the
  positions decoded) plus the new rows; an existing row gets the new segment at its positions (the
  smallest exact id of a position that already has 64 moves into its bucket).
  Rows are read and written in batches (one `IN` list or multi-row upsert for many keys; busy rows 500
  at a time, so memory stays bounded). While rewriting a row, ids below the oldest stored segment,
  buckets below its bucket and chunks or positions left with nothing are dropped.
- **Deleting.** A purge that deletes whole segments leaves the rows alone (readers ignore ids that no
  longer exist) and then deletes the rows whose `newest` is below the oldest stored segment, so the
  index shrinks with the data; rows of places still active are trimmed by the next pack that touches
  them; actor rows likewise (`newest` below the oldest stored segment). A rewrite (straddling or world
  purge) removes the old id from the rows of every region and every actor its header lists and adds
  the new segment like any pack; the busy rows read for the new segment also
  drop the old id from their exact ids (a bucket cannot drop one id; the region row no longer lists
  it, which keeps it out of lookups).
- **Catching up.** `indexed_from`: every segment at or above it is indexed, places and actors alike
  (one pass and one transaction per batch fills both). Version 5 sets it to one
  more than the largest existing segment id. After its packing passes the archiver spends up to
  `Archiver.CATCH_UP_TIME` (20 s) per run, under its lease, indexing the newest missing segments in
  transactions of `CATCH_UP_BATCH` (64; positions only are decoded) and lowering `indexed_from` in the
  same transaction (`Archive.catchUpIndex`). Emptying the index tables and resetting `indexed_from`
  (`SegmentIndexRepository.reset`) rebuilds the index the same way; the rows equal the ones packing
  wrote.
- **Reading.** `SegmentIndex.plan` combines two optional plans. The place plan: a filter with an area
  of at most `MAX_AREA_CHUNKS` (1,024 chunks, one region's worth: at most a few region rows and a
  bounded number of busy rows, against a cached header test per segment for larger areas) reads the
  region rows of the area's chunks and, for busy chunks the area covers only partly, their
  `block_segment` rows (`IN` lists on the primary keys). A chunk without a busy row contributes all its
  segment ids; a busy one, of the ids its region row lists, those the accepted positions
  (`EventFilter.inPositions`) name exactly and those in their older buckets; no accepted position, no
  segment. The actor plan: a filter listing 1 to `ActorIndex.MAX_ACTORS` (64) actors reads their rows
  (one `IN` list) and takes the union; an actor without a row contributes nothing, and excluded actors
  never narrow. Both present: their intersection; one: that one; none: the keyset path below.
  `indexed_from` is read before the rows, and every segment below it is added to the plan.
- **Candidate ranges.** How the planned segments are put in reading order depends on how many there
  are. A plan of at most 16 runs and 64 ids reads their id ranges by primary-key ranges
  (`id = ? OR id BETWEEN ? AND ?`), filters them by the filter's id range and the cursor, and sorts
  them. A larger plan (a place active for a long time, an actor seen all the time) read newest first
  walks the covering index on `(last_id, id, first_id)` in its own order, 64 ranges with the first
  statement and four times as many with each later one up to 4,096
  (`last_id >= ? AND first_id <= ? ... ORDER BY last_id DESC, id DESC LIMIT ?`, the candidate keyset
  without headers, about 20 bytes a segment), and keeps the ids the plan names: a lookup that fills
  its page from the newest segments stops after the first statement instead of reading every
  segment's id range (about 3 ms through JDBC for 5,337 segments, and growing with history), and a
  count or a rollback walks them all in a few statements. Read oldest first, a larger plan reads every
  range overlapping the filter's id range from the same index in one statement and sorts them (no
  index holds `first_id` order with `last_id`). Headers and flags are then fetched page by page (4, 8,
  16, then 32 ids), with the same early stop as the keyset path. Larger areas and filters the index
  does not narrow keep the keyset path over `last_id`/`first_id`.

Measured on the 1.40 billion event replay database (5,337 segments, 2026-11-17, SQLite, 4 KB pages):
catching the whole database up takes 583 s (about 9 segments a second; only positions are decoded).
The index is 29,353 region rows (5.0 MB of bodies, 7.2 MB of pages), 14,699 busy-chunk rows (29.6 MB,
37.0 MB of pages; 2.0 KB on average, 128 KB at most) and 7,573 actor rows (0.19 MB, 0.5 MB of pages):
44.7 MB, 2.9% of the 1.56 GB file. With buckets alone the busy-chunk rows were 17.3 MB of bodies and
the index 29.8 MB; as `WITHOUT ROWID` tables those rows took 37.9 MB, because such a row keeps only
about a quarter page on its b-tree page and most busy rows spill to an overflow page.

Lookups over the whole history, warm, in ms: the page of 10, opening the lookup (the page, then the
count of at most 16 segments, one after the other; the plugin runs them side by side) and the exact
count that only `#count` and the API ask for. "Before" is the build without the index for places
(page only, measured at a higher load) and without the actor index for players (measured in
alternating runs with the indexed build).

| Lookup | Before | Page / open / exact count |
|---|---|---|
| A block never touched in the busiest chunk | 3,900 (14,000 cold) | 0.5 / 1.0 / 0.4 |
| A block busy until a month before the end (6,101,539 events) | 686 | 8.4 / 36 / 2,394 |
| A block with 328 events in a chunk active in 488 segments | 270-320 | 11.6 / 31 / 92 |
| A block with 195 events in the busiest chunk (3,668 segments) | 270-320 | 18.3 / 38 / 557 |
| Radius 5 around the busy block (16,111,922 events) | 133 | 18.6 / 64 / 5,860 |
| A player in 1 segment (10 events) | 225 / 475 / 213 | 3.3 / 4.6 / 1.0 |
| A player in 2 segments, the median player (50 events) | 147 / 350 / 201 | 3.2 / 4.6 / 1.3 |
| A player in 3,324 segments (31,629,010 events) | 6.4 / 35 / 7,097 | 5.9 / 29 / 7,313 |

Exact ids against buckets alone, measured the same way: the 195-event block 18 ms a page instead of
44 and 557 ms an exact count instead of 879 (9 body reads a page instead of 40; the block is in 165
segments, the plan names 784 where buckets named 1,196, since its 101 segments older than the newest
64 are still buckets); the 328-event block 12 instead of 22 and 92 instead of 179 (3 body reads
instead of 20); radius 5 19 instead of 33; the busy block 8 instead of 15.

A plan costs 0.2-0.4 ms for a place and under 0.1 ms for an actor. What is left is reading the
segments it names, about 1-2 ms each (one statement for the units, one for the shared frames when a
row matched and they are not cached), and for a lookup by actor matching the actor column of every
unit the header selects.

### Indexes and query shapes

| Query | Plan |
|---|---|
| Inspector (one block) | `world = ? AND pos = ?` on `(world, pos)`, newest first |
| Radius / rollback area | one `(world = ? AND pos BETWEEN ? AND ?)` term per chunk column, OR-ed; rows outside the exact radius are filtered in Java |
| User | `actor IN (...)` on `(actor)` plus the id range |
| Time only | primary key range |
| Purge | primary key range delete, in batches; archive segments by `first_id`, deleted or rewritten whole |
| Segment index | `world = ? AND region IN (...)` / `world = ? AND chunk IN (...)` / `actor IN (...)` on the primary keys; trimming `newest < ?` on `(newest)` of all three tables; candidates `id = ? OR id BETWEEN ? AND ? ...` on the segment primary key, or, for a set of more than 16 runs or 64 ids, `last_id >= ? AND first_id <= ?` answered by the covering index alone (newest first: in index order with `ORDER BY last_id DESC, id DESC LIMIT ?`, page by page), then `id IN (...)` on the primary key |
| Archive candidates | `last_id >= ? AND first_id <= ?` on `(last_id)` (newest first) or `(first_id)` (oldest first); a later page adds `last_id <= ?` so it seeks to where the previous one ended; the flag row by its primary key; the body by `substr` byte ranges |

InnoDB and SQLite append the primary key to secondary indexes, so `(world, pos)` and `(actor)` are
already ordered by id within a key; PostgreSQL declares `id` explicitly.

### Imports

`/hawkeye import coreprotect` copies a CoreProtect database (SQLite, MySQL/MariaDB, DuckDB or
ClickHouse, opened read-only) into the home shard. It only adds: archive segments, dictionary rows,
one `server` row and one `import_state` row per source.

- **Import node.** Imported events are numbered under their own node, a `server` row named
  `<server.name>-coreprotect` (then `-coreprotect-2`, `-3`, ... for further sources) with the server's
  world scope and `last_seen = 0` (`ServerNode.imported()`), so their ids never collide with the ones
  the live writer issued for the same seconds. An import node never runs: `/hawkeye network` and network
  job destinations leave it out. Lookups filter by world scope, so imported events appear in ordinary
  lookups and rollbacks. The row is a plain insert (`ServerRepository.registerImport`): a taken name is
  rejected by the unique key on every engine and the next suffix is tried.
- **Reading the source.** `core.repository.coreprotect` (`source`, `schema`, `driver`, `reader`):
  `CoreProtectSources` finds the database from a
  folder (its `config.yml`, else `database.db`, else `database.duckdb`), a config or a database file, and
  opens one read-only connection (`CoreProtectSource`). SQLite, MySQL and DuckDB tables are walked by rowid
  in windows of row ids (`WHERE rowid > ? AND rowid <= ?`; an empty window is jumped with `MIN(rowid)`),
  ClickHouse by `(time, rowid)` keyset pages bounded to a month above the cursor (rowids are reserved in
  blocks per writer there). Columns and optional tables an older CoreProtect lacks read as NULL or absent.
  Whether a table exists comes from the engine's catalog (`sqlite_master`; `information_schema.tables` of
  the current database on MySQL and ClickHouse, where CoreProtect's tables are views, and of schema `main`
  on DuckDB); any other failed query is an error, never a missing table. A source without `block`, `user`
  and `world` is refused when it is opened ("not a CoreProtect database"), before the import registers
  anything.
  SQLite and MySQL/MariaDB use the drivers Hawkeye loads anyway; DuckDB and ClickHouse drivers are fetched
  on first use into `<plugin data folder>/drivers` from Maven Central, checked against a pinned SHA-256 and
  loaded in their own class loader (`SourceDrivers`, `DriverResolver`).
- **Segments.** Events go straight into the archive (`EventStore.writeImported`, one segment of
  `archive.segment-events` per transaction), numbered by a private `IdAssigner` fed in time order.
- **Checkpoint.** Schema version 6 adds `import_state`: per source (`coreprotect:<scope>:<fingerprint>`)
  the per-table cursors, counters, node name and newest slot written, as `key=value` text
  (`util.codec.ImportStateCodec`, versioned, unknown keys ignored). Each segment's transaction ends with
  `UPDATE ... SET revision = revision + 1 ... WHERE source = ? AND revision = ?`
  (`core.storage.importer.ImportStore`); when no row matches, another run moved the checkpoint and the
  segment rolls back with it. A crash, a cancel or a restart resumes from the last commit and imports
  nothing twice.
- **The run** (`core.service.importer`: `ImportService`, the run in `pipeline`, the row mapping in
  `mapping`). One import runs per server, on its own `Hawkeye-Import` thread. It locates and opens the
  source, refuses a DuckDB file while a plugin named CoreProtect is enabled (one process cannot open a
  DuckDB file twice) and a source whose CoreProtect migration is incomplete, takes the checkpoint
  (`ImportStore.begin`) and refuses one taken in another scan order (the database moved to or from
  ClickHouse).
- **Merge order.** `SourceMerge` reads every event table in pages of 4,096 rows from its cursor, in the
  table's own scan order, and always consumes the row with the smallest `time` among the tables' next
  rows, ties in `SourceTable` order (block, sign, container, entity container, item, entity
  interaction, chat, command, session). Rows are therefore only as time-ordered as each table is;
  the assigner absorbs small disorder.
- **Horizon** (`RunHorizon`). A run imports rows stamped up to its start minus the source's settle time:
  120 s on MySQL and ClickHouse, where CoreProtect writes while the import reads and a row can become
  visible after the reader passed its place (a transaction still open, a ClickHouse batch published late
  or retried, rows stamped when queued); 0 on SQLite and DuckDB, files nothing else can write to in a way
  the reader could miss (`SourceDialect.settleSeconds`). When the next row in merge order is stamped
  later, the merge stops (`SourceMerge.held`), the run is complete and the row stays unread: every table
  stops at the same time, so the next run's rows are all newer than what is stored and none has to be
  displaced. The final progress then keeps the estimate of the rows left, and the command says how many
  the next run imports. In rowid order a row stamped more than a week past the horizon is a wrong clock,
  not a new row: the merge returns it first and the mapper skips it, so it cannot hold back the rows
  behind it in its table. In time order (ClickHouse) no row past the horizon is ever consumed: such rows
  sort last and stay unread, where consuming one would move the table's keyset cursor past every later
  row. The mapper's upper bound is the same horizon.
- **Mapping.** `RowMapper` turns each row into one event stamped at its second, or a skip counted by
  reason (older than 2025-01-01 or stamped in the far future, no equivalent action, folded into
  another event, unknown world/material/entity/position). When a row names an id missing from its
  snapshot of the source's dictionaries, the snapshot is re-read, at most once per batch. Block states
  are rebuilt from `blockdata_map` tokens and normalised through `Bukkit.createBlockData` (cached), so
  imported and captured events share `type` rows. Sign rows carry no material: the mapper remembers
  each sign block's state per position, folds the "before" snapshot into the next edit and a broken
  sign's text into its block break. Item blobs are read with `BukkitObjectInputStream` behind an
  `ObjectInputFilter` that allows the stream's own objects only `java.lang`, `java.util`, Guava collections
  and `org.bukkit` classes, at most 4 MB; what `ConfigurationSerialization` rebuilds from a wrapped map (a
  head's profile is Paper's `CraftPlayerProfile`) passes when its class is `ConfigurationSerializable` and
  not `Serializable`, which the stream cannot construct itself. `ItemExtras` puts back what CoreProtect
  lists beside the meta (colours, effects, patterns, modifiers, firework explosions, and the items inside a
  bundle or crossbow, each rebuilt the same way). One that does not decode leaves the event without a
  payload.
- **Batching.** Mapped events collect into a batch that closes when it holds `archive.segment-events`
  events, when a million rows were consumed without filling one, when every table is read to its end,
  or on cancel. Except at the end it closes only between two seconds of the source (it never splits a
  second; twice the events or rows close it regardless), so a cancelled and resumed import numbers every
  event exactly as an uninterrupted one.
- **Checkpoint and resume floor.** Closing a batch adds it to the run's private `IdAssigner` and takes
  it whole, then writes the segment and the next checkpoint (per table the last row consumed, the
  newest slot written, the counters) in one transaction; a batch that mapped to no event writes the
  checkpoint alone. A resumed run raises the assigner's floor to the slot after the newest one
  written, so no id is issued twice. An event older than the floor (a row out of order by more than
  the assigner's 120 retained seconds, or one that arrives after its second was written by an
  earlier run) is stored in the floor's second and counted as displaced. When every table is read to
  its end the checkpoint is marked complete; a later run continues from the cursors and imports only
  newer rows.
- **After a CoreProtect purge.** Before the merge starts, every stored rowid-order cursor is checked
  (`CoreProtectSource.anchor`): CoreProtect's `/co purge` on SQLite rebuilds every table but `block` with
  `INSERT ... SELECT`, renumbering its rows from 1 in the same order. When the cursor's row is there with
  another time, or missing from a SQLite table other than `block`, the table was renumbered and the run
  continues after `MAX(rowid)` of the rows stamped at or before the cursor's time (a WARN names the table
  and both rowids). A missing row anywhere else (MySQL, DuckDB, SQLite `block`) was deleted by a purge that
  keeps row ids, and reading continues after it. So the import continues by time after a purge, exact
  except for rows of the cursor's own second that were stored out of order. Time-order cursors
  (ClickHouse) do not depend on row ids alone and are kept.
- **Cancel and shutdown.** `/hawkeye import cancel` closes the current batch at the next second and
  writes it; shutdown cancels and waits up to 10 s. Progress is published as a snapshot after every
  checkpoint and every 4,096 rows, and sent to the command about every 30 s (with a fresh estimate
  of the rows left, from `CoreProtectSource.remaining`; on ClickHouse a count that can time out, so a
  failed estimate keeps the previous one and is logged once at DEBUG, never failing the import:
  `RowEstimate`).

## Threading

| Work | Thread |
|---|---|
| Capturing an event (block state strings, item bytes) | The event's own thread (the owning region on Folia); never blocks |
| Queueing | Lock-free `EventQueue` (bounded; overflow is counted and reported, never blocks the game) |
| Writing | One `Hawkeye-Writer` thread: drains the queue every `flush-interval` or at `batch-size`, resolves dictionaries, writes one transaction per batch, retries with back-off |
| Lookups, rollback planning, purge, jobs | The bounded `Hawkeye-Query` executor |
| Applying a rollback/restore/preview | The region that owns each chunk (`core.service.thread.ServerThreads`), a bounded number of blocks per tick |
| A CoreProtect import | One `Hawkeye-Import` thread per run (reading, mapping, writing) and a `Hawkeye-Import-Pack` pool of 3 that compresses segment frames; both end with the run |
| Replies to a command sender | The sender's own thread |
| Hawkeye's warnings, errors and crashes (`core.diagnostic`) | Sorted on the logging thread by a log4j filter on the root logger (no I/O, never blocks); one `Hawkeye-ErrorLog` thread writes `errors/` and `crash/`; one `Hawkeye-Webhook` thread posts first times to Discord (50 waiting at most) |
| Files waiting for review (`/he errors`, the notice on join) | Listed (and for `/he errors` read) on the `Hawkeye-Query` executor; the join notice only counts the folder listings. Marked reviewed (moved) on the `Hawkeye-ErrorLog` thread between two writes, or on `Hawkeye-Query` when the error log is not running; replies on the receiver's thread |

Every executor is owned by a bean with a `Disableable` shutdown path, except the error log: `HawkeyePlugin`
opens it before the Spring context (so a context that fails to start is a crash file) and closes it last in
`onDisable`, after the context. Shutdown flushes the queue
(bounded by `shutdown-timeout`) and spools what could not be written.

## Multi-server and sharding

- **One database, many servers.** Point every server at the same MySQL/MariaDB/PostgreSQL. Each
  registers its `server.name` and gets a node id; ids, dictionaries and worlds never collide. Every
  server sees every other server's events within one flush interval.
- **Shards.** A large network splits servers over several databases. A server writes only to its
  home shard (`storage`) and lists the other shards under `network.shards` (read access). Lookups
  with `s:<server>` or `s:#network` run on every shard that holds those servers, in parallel, and
  the newest-first pages are merged by id.
- **Network jobs.** A server can only change its own worlds. `rollback`/`restore` with a server
  filter becomes one `job` row per target server, in the target's shard. The target claims the row
  atomically (`UPDATE ... WHERE status = PENDING`), applies it, and writes the result; the issuer
  reports progress and the result to the sender.
- **Bus.** `network.bus` delivers "job queued" and "job finished" notifications instantly over Redis
  pub/sub. The database stays the source of truth: every server also polls its pending jobs every
  `network.poll-interval`, so a lost message only costs latency, and `bus: none` works with polling
  alone.
- **Credentials.** `storage`, each shard's `storage` and `network.redis-uri` may read their secrets
  from environment variables (`${NAME}`, `config.env.EnvReferences`); the configuration object keeps
  the text as written and resolves it only where a connection opens (`StorageSettings.login`,
  `NetworkSettings.redisUri`). MySQL/MariaDB and PostgreSQL pools get a `core.sql.RedactingDataSource`:
  the user, password and parameters are its driver properties only, and every driver exception passes
  through `util.redact.Redactor` (`util.redact.RedactedErrors` rebuilds a graph that names a credential).
- **Leases.** Maintenance that must run once per shard (auto-purge, archiving) takes the `lease`
  row; the holder renews it, and another server takes over when it expires.
