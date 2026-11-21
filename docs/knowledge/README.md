# Knowledge base

Read this before working in this repository. It records the owner's preferences and verified,
project-specific know-how that the code does not make obvious. Keep entries short and factual, and
update them in the same change when you learn something durable or an entry stops being true.

## Source of truth

- `AGENTS.md` holds the rules; this file holds the facts behind them.
- The build files are the executable authority: `gradle/libs.versions.toml` for versions,
  `settings.gradle.kts` for repositories, `build.gradle.kts` for packaging and the jar check.
- `src/main/resources/paper-plugin.yml` is hand-written; `PaperPluginYmlTest` and `verifyPluginJar`
  keep it in step with the classes.

## Hawkeye facts (verified 2026-11-16)

- The design contract (storage format, threading, multi-server protocol) is `docs/architecture.md`;
  change it in the same commit as the code.
- ConfigLib 4.8.1 fills a record component that is missing from the file with `null`/`0`, not with
  the record's no-argument defaults, so a key added in a later version would break every existing
  file. Settings are `@Configuration` classes with field initializers, read through Lombok
  `@Getter @Accessors(fluent = true)` (`config.storage().type()`), plus `@EqualsAndHashCode` so a
  reload can tell which restart-only sections changed.
- PostgreSQL identities and InnoDB auto-increments consume an id even for an insert that conflicts
  (`ON CONFLICT DO NOTHING`, `ON DUPLICATE KEY UPDATE`). Dictionary interning and server registration
  therefore select first and insert only what is missing: node ids end at 1023.
- MySQL's `>>` is a logical 64-bit shift and SQLite/PostgreSQL's is arithmetic, so SQL on packed
  positions only extracts low bits (`(pos >> n) & mask`), which agree on all three.
- SQLite creates its database file but not missing folders; `DatabaseFactory` creates them.
- SQLite query plans for the lookup shapes are asserted in `EventSqlPlanTest` (area: index range scans
  on `(world, pos)`; one block: one seek; whole worlds: the primary key, because `(world + 0)` keeps
  the position index out).
- Storage footprint on SQLite (`StorageFootprintTest`, 60,000 random block events numbered by
  `IdAssigner`), verified 2026-11-16 with archive format 2: hot rows about 73 bytes per event, archived
  about 6.2 (one segment), CoreProtect's relational `co_block` layout about 80. Random positions and
  types are the worst case; the codec stores replayed real history in about 1.2 bytes per event.
- Full-scale footprint (`StorageBenchmark`, opt-in: `HAWKEYE_BENCH_EVENTS`, `HAWKEYE_BENCH_DIR` on disk,
  optional `HAWKEYE_BENCH_DAYS`; set `SQLITE_TMPDIR` off `/tmp`). It numbers ids through `IdAssigner`
  and archives with the default settings once a simulated minute. The 100M-event figures measured with
  the per-region format 1 no longer apply; re-run it for format 2.
- Replay benchmark (`ReplayBenchmark`, opt-in; `scripts/bench/reference-export` turns a packed database
  of the `ericlmao/CoreProtect` fork into the row files it reads). Verified 2026-11-16 on the owner's
  reference database (1,398,675,414 packed rows over 59 days, 3,397,459,968 bytes, 2.43 B/row): Hawkeye
  stores the same events in 1,517,871,104 bytes (1.085 B/event, 5,337 segments, bodies 1,490,269,827,
  headers 19,135,618). Bulk mode packs about 270,000 events a second with 5 threads (about 85 minutes
  in all); live mode (hot table plus archiver) about 18,000 and gives the same segments. The run is
  split by `HAWKEYE_REPLAY_DAYS` with `HAWKEYE_REPLAY_RESUME=1` (20714-20729, 20730-20740, 20741-20773)
  because a background command on this host ends after two hours. Lookups on the full database, warm,
  host at load 6, before the place index: a player's newest 10 events 18 ms, radius 5 at a busy block
  133 ms, the last hour 106 ms, a block with little history 270-320 ms, a block never touched in the
  busiest chunk 3.9 s (14 s cold: one unit per segment).
- Segment index on that database (schema version 5, verified 2026-11-17; `lookupsOnAKeptDatabase`
  catches the index up first): catch-up 583 s for 5,337 segments. 29,353 region rows (5.0 MB of
  bodies, 7.2 MB of pages), 14,699 busy-chunk rows (29.6 MB, 37.0 MB of pages; 2.0 KB on average, 128 KB
  at most) and 7,573 actor rows (0.19 MB, 0.5 MB of pages): 44.7 MB of pages, 2.9% of the file, which
  is 1,563,283,456 bytes with it (1.12 B/event; the reference is 3,397,459,968). SQLite rowid tables:
  as `WITHOUT ROWID` tables the version-4 rows took 37.9 MB against 29.8, since an index b-tree row
  keeps only about 1 KB of a 4 KB page before overflowing.
- Lookups on that database, version 5 against version 4 (buckets only, no actor index), each build three
  times in alternating runs at load 1.4-1.8, warm, median of 15 repeats per run, in ms as page of 10 /
  open (page then the 16-segment count) / exact count. A block never touched in the busiest chunk
  0.5 / 1.0 / 0.4 (unchanged; 3.9 s without any index). The busy block (6,101,539 events) 8.4 / 36 /
  2,394 (was 14.7 / 44 / 2,602). A block with 328 events in a chunk of 488 segments 11.6 / 31 / 92 (was
  22.2 / 40 / 179; 3 body reads a page instead of 20). A block with 195 events in the busiest chunk
  18.3 / 38 / 557 (was 44.4 / 65 / 879; 9 body reads instead of 40; it is in 165 segments, the plan
  names 784 where buckets named 1,196, so its exact count still reads bucket candidates for the 101
  segments older than its newest 64). Radius 5 at the busy block (16,111,922 events) 18.6 / 64 / 5,860
  (was 32.6 / 82 / 6,666). A player in 1 segment 3.3 / 4.6 / 1.0 (was 225 / 475 / 213: every header was
  tested); a player in 2 segments, the median of the 7,452 players, 3.2 / 4.6 / 1.3 (was 147 / 350 /
  201); a player in 3,324 segments (31,629,010 events) 5.9 / 29 / 7,313 (was 6.4 / 35 / 7,097). A plan
  costs 0.2-0.4 ms for a place and under 0.1 ms for an actor. In the plugin the page and the count run
  side by side on the query pool, so a reply waits for the slower of the two, not their sum.
- Timing on this shared host: one run's median of three repeats moved by 2-3x between runs (a lookup of
  a few milliseconds is drowned by one collector pause after the multi-second exact counts), which
  once looked like a 40 ms regression that was not there. `ReplayBenchmark` now takes the median of 15
  repeats for lookups under 300 ms; compare two builds by alternating them (a `git worktree` of the
  old commit with the same benchmark code) and taking the median of three runs each.
- Benchmark copies: `~/.cache/hawkeye-bench/replay-v5` is migrated to version 5 and indexed;
  `~/.cache/hawkeye-bench/replay-index` stays at version 4 (indexed, buckets only) for comparisons with
  commit `282c7fd`; `~/.cache/hawkeye-bench/replay-full` is the version-3 file without an index.
- An index format change takes new table names on purpose (version 5 renamed all four and dropped the
  version-4 ones): an older build then fails on its first index statement instead of misreading rows,
  and the migration needs no "empty and reset" step. The archiver's catch-up indexes a migrated
  archive again; lookups stay correct meanwhile.
- `PlaceChunk.EXACT_SEGMENTS` (64) trades bytes for read precision: per busy-chunk position, the newest
  64 segment ids are exact and older ones are buckets of 16. Measured on the full replay database's
  27,082,679 busy-chunk positions (126,965,244 position-segment pairs; study with zstd 9, later
  confirmed by the real index: 29.6 MB): buckets alone 18.1 MB and 3.34 times the segments holding a
  block; newest 16 exact 27.0 MB and 1.94; 64 exact 29.8 MB and 1.75; 256 exact 34.7 MB and 1.57;
  every id exact 50.9 MB (largest row 769 KB). The raw size is about the same for every choice, so
  decoding costs the same. 21.0 million of the positions are in one segment and 99.5% in at most 64.
  Coding positions by their rank among the chunk's own segments, or as bucket runs plus bit masks,
  saved nothing over exact runs (46-49 MB for every id): the cost is the flicker of a few positions
  touched in thousands of segments.
- Reading 5,337 rows of three longs through sqlite-jdbc costs about 3 ms (every column is a JNI call),
  as much as the rest of a short lookup: a read that names many segments therefore walks the covering
  index in reading order page by page (`Archive.WalkedPages`, 64 ranges first) instead of reading every
  segment's id range up front. The first statements of a lookup by an active player cost 0.5 ms either
  way (state row 0.07, actor row 0.09, 64 ranges 0.16, 4 headers 0.2; the keyset page of 32 headers
  0.5 plus 1.6 to parse them when they are not cached).
- `ReplayBenchmark.lookupsOnAKeptDatabase` reports: `placeIndexCatchUp`, `placeTables` (rows, body bytes
  and `dbstat` bytes of the four index tables), `lookups` (per "open ..." entry also `page`, `bounded
  count` and `exact count` apart; three players chosen from the headers: the fewest, the median and the
  most segments) and `placeProfile` (plan time, candidate count, body reads of one page).
- Embedded servers for `ServerDatabaseIntegrationTest` live in `~/.cache/hawkeye-ref/dbs`: MariaDB
  (`mariadb/bin/mariadbd --no-defaults --datadir=$PWD/mdata --basedir=$PWD/mariadb --port=33069`,
  `HAWKEYE_TEST_MYSQL=mariadb://root:@127.0.0.1:33069/hawkeye`) and PostgreSQL (`pg/bin/pg_ctl -D pgdata`
  on port 54329, trust auth, role `hawkeye`, database `postgres`:
  `HAWKEYE_TEST_POSTGRES=postgresql://hawkeye:@127.0.0.1:54329/postgres`).
- That reference database was copied while its server ran: the file is one page shorter than its header
  says (the last page was in the write-ahead log) and SQLite calls it malformed. Appending one page of
  zeros (8,192 bytes) to a copy makes every packed row readable.
- Archive: SQLite, MySQL/MariaDB and PostgreSQL all slice a blob with 1-based `substr(body, from, len)`;
  `LENGTH` of a blob is its byte count on all three. `ROWS` is a reserved word in MySQL 8, so the flag
  column is `ordinals`. SQLite's `segment.id` is `AUTOINCREMENT` so a deleted segment's id is never
  reused (the header and shared-frame caches rely on it). Two databases
  hold byte-identical segments only when they interned dictionaries in the same order: `writeArchived` and hot+compact give
  equal bytes only for the same input order (type ids are interned in batch order).
- Archive lookups on the replay database (`ReplayBenchmark`, 26.0 million real events in 100 segments,
  SQLite, warm "againMillis", measured 2026-11-16 on the shared build host at load 5-7, so a run varies
  by up to 2x; before = full-unit decode, after = column matching first plus the unplaced-region list):
  busy block newest 10: 14 -> 8-22 ms; its next page: 8 -> 5-20; a block touched once in a quiet chunk:
  846 -> 16-37; the same in the busiest chunk: 893 -> 105-204 (usually about 105-130); radius 5: 4 ->
  3-12; count of a day at the busy block (616,596 events): 879 -> 106-134. The region list added 15,956
  header bytes (296,593 -> 312,549, about 160 per segment); the body bytes are unchanged (30,158,764).
- Per segment, for the busiest-chunk block lookup (about one placed unit per segment): the SQL fetch of
  the unit by `substr` is about 0.5 ms through JDBC, zstd on the scalar frame 0.15 ms, column decoding
  and matching about 0.45 ms before stopping at the area's last key (placed rows are sorted). Decoding a
  segment's shared frames (ids) costs about 7 ms, which a read now pays only when a row matched.
  SQLite materializes the whole body (about 300 KB) for `substr`: 0.10 ms per call whether it takes
  20 bytes or 20 KB, against 0.016 ms for the header and 0.15 ms for the whole body (Python's sqlite3
  on the same file), so about a fifth of the SQL fetch and under a tenth of a per-segment read.
- `./gradlew test` with only environment variables changed is up to date and does not run again; time
  benchmarks with `--rerun`.
- ConfigLib (through the engine's `Configuration.config`) writes the file back on load: a removed key
  (`archive.batch-events`) is dropped and new keys are added with their defaults.
- Paper 26.3's `UnsafeValues#serializeEntity` rejects an entity that is no longer valid unless it is
  passed `EntitySerializationFlag.FORCE`, and a mob in its `EntityDeathEvent` is no longer valid.
  `EntityBytes.serialize` passes it (before, `/kill`ed zombies, villagers, cows and skeletons were
  stored without entity bytes; armour stands worked).
- Item and entity payloads (verified 2026-11-16): `ItemStack#serializeAsBytes` and `serializeEntity`
  return gzip NBT (`1f 8b`), and `deserializeBytes`/`deserializeEntity` read only gzip. Hawkeye stores
  raw NBT (`0a`) and gzips it again on read (`util.nbt.NbtGzip`); gzip rows from earlier builds read
  as they are. MockBukkit's `serializeAsBytes` returns Java serialization (`ac ed`), which passes
  through unchanged.
- Paper writes some entity keys with a dot in the key itself at the top level (`Paper.Origin`,
  `Paper.OriginWorld`, `Paper.SpawnReason`, `Spigot.ticksLived`, `Bukkit.updateLevel`), not as nested
  compounds, and writes `attributes` in hash order (it differs between identical mobs).
- Entity normalization rule (`util.EntitySnapshots`): strip only what a respawn resets, re-rolls or
  gets from the event row: UUID, motion, fall distance, fire, air, on-ground, portal cooldown, hurt and
  death timers, spawn bookkeeping (`Paper.Origin*`, `Spigot.ticksLived`, world UUIDs), the knockback
  timer, combat memory (`last_hurt_by_*`, anger), `EggLayTime`, the villager
  `golem_detected_recently` memory, `random_spawn_bonus` modifiers; `Health` of a dying entity; `Pos`
  and `Rotation` of a `Mob` only. Attributes are sorted by id. Minecraft loads every stripped tag as
  absent: a respawned kill has full health, no fire, zero fall distance (checked with vanilla
  `/execute if entity ... nbt=` in `scripts/smoke/kill-rollback.txt`). Kept on purpose: variants, sound
  variants, `LeftHanded`, baby state, names, equipment and drop chances, villager data and gossip,
  the zombie `spawn_reinforcements` base (rolled at spawn, but gameplay state), persistence, AI flags,
  tags. `random_spawn_bonus` did not appear on `/summon`ed mobs in 26.3.
- Measured 2026-11-16 on Paper 26.3 build 253 (`kill-rollback.txt`, 401 kills of 11 farm types plus
  babies, named and equipped mobs and an armour stand; zstd level 19 over the concatenated payloads):
  about 500 bytes raw per kill and 6.8 bytes per kill compressed together. Compressed per type alone:
  spider 9.3, enderman 9.8, iron golem 10.0, cow 10.5, creeper 10.6, sheep 11.4, skeleton 12.1,
  chicken 14.3, pig 14.6, villager 14.6, zombie 18.4 (babies, named and equipped ones, the random
  reinforcement chance). The same normalized snapshots gzipped one by one cost 49 bytes per kill
  compressed together. Items: 108 hopper moves of identical named, enchanted swords are 193 bytes raw
  and 1.4 bytes each compressed together; a broken chest of 27 of them is one 5.3 KB payload that
  compresses to 229 bytes (8.5 per item).
- Real-server smoke runs wait several commands after `forceload` before cleaning up (entities load
  after the chunks), and select the whole force-loaded area: mobs walk to its edge in a minute. Iron
  golems kill zombies and zombies hunt villagers within about 60 blocks, so `kill-rollback.txt` keeps
  them 200 blocks apart. 26.3 prints `Count: N` (capital C) for `/execute if entity`.
- This Linux build host has no JDK 25 and no Docker. `settings.gradle.kts` applies the foojay toolchain
  resolver, which downloaded Temurin 25 into `~/.gradle/jdks`. SQLite runs for real in every storage
  test.
- `ServerDatabaseIntegrationTest` runs the storage scenarios on real servers when
  `HAWKEYE_TEST_MYSQL` / `HAWKEYE_TEST_POSTGRES` hold `scheme://user:password@host:port/database`;
  otherwise it is skipped. Without Docker, real servers come from Maven Central binaries: PostgreSQL
  from `io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64` (a `.txz` with `initdb` and
  `pg_ctl`) and MariaDB from `ch.vorburger.mariaDB4j:mariaDB4j-db-linux64` (`scripts/mariadb-install-db`
  and `bin/mariadbd`), unpacked under `~/.cache` and started on local ports. Verified 2026-11-16 on
  MariaDB 11.4.5 and PostgreSQL 18.6.
- `scripts/smoke/run.py <server-dir> <scenario>` boots a real Paper server from the console, runs a
  scenario (`command => expected output`) and fails on a missing reply or a Hawkeye error in the log.
  `scripts/smoke/tnt-rollback.txt` records TNT damage, then looks it up, rolls it back, restores and
  undoes it, checking the world with vanilla `/execute if block`; it needs a flat overworld. The
  server folder holds `paper.jar` (from `https://fill.papermc.io/v3/projects/paper/versions/26.3/builds`),
  `eula.txt` and `plugins/Hawkeye.jar`. Verified 2026-11-16 on Paper 26.3 build 253: the loader
  resolved every library, Cloud registered all command groups through Brigadier, and the scenario
  passed.
- MariaDB Connector/J reports matched rows, not inserted rows, for `ON DUPLICATE KEY UPDATE`, so an
  insert's update count is not "rows inserted" on MySQL; nothing relies on it.
- Heavy builds on this host go through `rtk proxy t3-build ./gradlew ...` (shared build slots).
- Cloud 2.0.0 recurses until `StackOverflowError` on an empty greedy `@Default("")`. Optional greedy
  parameter lists are `[parameters]` with a `@Nullable` argument read as `""`; an optional `[arg]`
  without `@Default` arrives as `null`.
- Cloud resolves a named suggestion provider while it parses each command that references it, so the
  bean declaring `@Suggestions(QueryInput.SUGGESTIONS)` (`LookupCommand`) is registered first
  (`@Order(Ordered.HIGHEST_PRECEDENCE)`). A `@Suggestions` method can take a `CommandInput`, whose
  `remainingInput()` is the whole greedy argument.
- `Placeholder.parsed` values work inside MiniMessage tag arguments (click commands);
  `Placeholder.unparsed` values do not.
- CompletableFuture runs a dependent stage's `execute` on the thread that completes the source; an
  executor that rejects throws there. `EventWriter` completes flush requests one by one inside a
  guard so a caller's stage can never stop the writer thread.
- MockBukkit 4.120: `Entity#teleportAsync` throws `UnimplementedOperationException`;
  `PlayerMock#sendBlockChange` does nothing (subclass it to record what was sent);
  `BlockMock#setBlockData` does not replace the block's state object, so restored container contents
  and sign text cannot be checked there; region and entity tasks run on `performOneTick`. Its data
  folder is predictable (`<tmp>/TestHawkeyePlugin-0.0.0`), so tests write a `config.yml` before
  loading for start-only settings.
- `PlayerInteractEvent#isCancelled` is deprecated in Paper 26.3; use `useInteractedBlock()`.
- More shared handler lists: `PlayerSignCommandPreprocessEvent` arrives on the command handler (so it
  is handled inside the command listener, never twice), and arrow pickups and projectile launches
  share their parents' lists.
- Paper's `spawnAt` re-attaches a hanging entity to the block containing the spawn point, so a rollback
  respawns paintings at their recorded position and rotation (even-sized paintings anchor one block
  off); `HangingBreakByEntityEvent#getRemover` is the direct entity; the item-frame PLACE event carries
  the whole hand stack; `EntityConstructEvent` fires before the built entity is added.
- `InventoryPickupItemEvent` fires before a hopper takes the item and the take may be partial, so only
  what fits is recorded.
- `World#isUltraWarm()` is deprecated since 1.21.11 with no replacement; fast lava is detected as the
  NETHER environment.
- More MockBukkit 4.120 gaps: `getPistonMoveReaction`, entity serialization, item default data, the
  jukebox inventory, `BookMeta#pages()` and `AbstractArrow#getItemStack` are unimplemented; it fires no
  `EntitySpawnEvent` for primed TNT; `PlayerMock#kick` fires no quit event; its decorated-pot and
  lectern states count as `Container`; the state of a block set with `setBlockData` reads as air (set
  the data on the state in tests); `getState()` is live and `getState(false)` a copy (the reverse of
  Paper); nearby entities are found by position, not bounding box.
- `Recorded.events()` in listener tests includes the JOIN that `server.addPlayer` records.
- Paper 26.3 event behaviour verified in the server jar's bytecode (2026-11-16; the jar is
  `versions/26.3/paper-26.3.jar` after a first start; grep class files for an event's internal name,
  and for `CraftEventFactory` helpers such as `handleBlockGrowEvent`, to find where it fires):
  - `BlockPistonExtendEvent`/`BlockPistonRetractEvent#getBlocks()` is the moved blocks plus the blocks
    the piston destroys (CraftBukkit concatenates `toPush` and `toDestroy`).
  - `BlockDestroyEvent` fires only from `Level.destroyBlock`: physics pops after `updateShape` turns a
    block to air, pistons destroying, entity changes, `/setblock ... destroy`; never for player breaks.
  - `EntityChangeBlockEvent` already covers cake eating, composters, ender eyes in portal frames,
    honeycomb waxing, brushing suspicious blocks, cave-vine harvesting, farmland trampling, endermen,
    ravagers, silverfish, withers, falling blocks and lily pads broken by boats.
  - `BlockGrowEvent` fires for crops, cactus, sugar cane, stem fruit, cocoa, nether wart, pitcher
    crops, sweet berries, turtle and sniffer eggs, vines, chorus, budding amethyst and dried ghasts;
    bamboo, kelp and twisting vines grow through `BlockSpreadEvent`.
  - Handler lists are per event class: `BlockSpreadEvent`, `BlockFertilizeEvent`, `BlockGrowEvent`,
    `EntityPlaceEvent` and `BlockDestroyEvent` have their own and never reach a parent's handler;
    `EntityBlockFormEvent`, `BlockMultiPlaceEvent`, `EntityBreakDoorEvent`, `PlayerDeathEvent` and
    `HangingBreakByEntityEvent` share their parent's.

## CoreProtect import facts (verified 2026-11-21)

- Real-server check (2026-11-21): `scripts/smoke/coreprotect-generate.txt` on Paper 26.2 build 135 with
  CoreProtect CE 24.1 (Modrinth; it refuses to enable on 26.3: "Minecraft 26.3 is not supported") wrote a
  SQLite database of 13 `block` rows (11 `#tnt` breaks, 2 `#kill` kills) and 6 `container` rows (`#hopper`,
  blobs of 497 and 451 bytes for the sword and the potion). `scripts/smoke/coreprotect-import.txt` on Paper
  26.3 build 253 imported its 19 rows, imported nothing the second time, and rolled back from it: the
  stairs came back as `oak_stairs[facing=east,half=top]` (stored with block data) and
  `oak_stairs[facing=north,half=bottom]` (stored without: the server's default), the chest got back the
  sword with `custom_name` and `sharpness` 5 and the potion with `custom_color`. So item metadata written by
  CoreProtect on 26.2 is rebuilt by `ConfigurationSerialization` on 26.3. The same rows copied into a
  DuckDB file (CoreProtect's column layout) and into ClickHouse tables shaped like CoreProtect's views
  passed the same scenario on that server with each driver downloaded by `DriverResolver` on first use
  (DuckDB about 4 s for 80 MB): both drivers load under Paper's plugin class loader. Not checked: a DuckDB
  or ClickHouse database written by CoreProtect 25 itself (no released build runs on 26.3 yet). On this
  host the two server folders are `~/.cache/hawkeye-ref/coreprotect-gen` (Paper 26.2, CoreProtect) and
  `~/.cache/hawkeye-ref/import-smoke` (Paper 26.3, Hawkeye); both may be cleared. Re-run after cursor
  anchoring, the settle horizon and the catalog existence check were added: same 19 events, and the
  second run anchored nothing (no "renumbered" warning).
- CoreProtect 24.1 stores the version row as `2.24.1`, a non-player cause as a user with a NULL uuid
  (`#tnt`, `#hopper`, `#kill`), `/kill` as a kill by `#kill`, and a block whose state is the default
  without block data.

- `CoreProtectSourceIntegrationTest` runs the reader scenario on real engines when set:
  `HAWKEYE_TEST_COREPROTECT_MYSQL=mariadb://root:@127.0.0.1:33069/coreprotect` (the embedded MariaDB,
  database `coreprotect`), `HAWKEYE_TEST_COREPROTECT_DUCKDB=1` (with
  `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=$HOME/.cache/hawkeye-ref/tmp`; the jar is cached in
  `~/.cache/hawkeye-ref/drivers`), `HAWKEYE_TEST_COREPROTECT_CLICKHOUSE=http://default:@127.0.0.1:18123/default`.
  Verified on MariaDB 11.4.5, DuckDB 1.4.5.0 and ClickHouse 26.12.1; the import checkpoint scenario of
  `ServerDatabaseIntegrationTest` on MariaDB 11.4.5 and PostgreSQL 18.6.
- Table existence from the catalog (verified 2026-11-21 on SQLite, MariaDB 11.4.5, DuckDB 1.4.5.0 and
  ClickHouse 26.12.1 by the source tests): SQLite `sqlite_master` (`type IN ('table', 'view')`); MariaDB
  `information_schema.tables WHERE table_schema = DATABASE()`; DuckDB `information_schema.tables WHERE
  table_catalog = current_database() AND table_schema = 'main'`; ClickHouse `information_schema.tables
  WHERE table_schema = currentDatabase()`, which lists views too (CoreProtect's ClickHouse tables are
  views). On SQLite a view over a missing table is listed but fails on `SELECT`, which the import now
  reports instead of treating the table as absent.
- A local ClickHouse without Docker: the official single binary
  (`curl -fsSL https://builds.clickhouse.com/master/amd64/clickhouse`) in `~/.cache/hawkeye-ref/clickhouse`,
  started with `./clickhouse server -- --path=$PWD/data/ --tmp_path=$PWD/tmp/ --http_port=18123
  --tcp_port=19000 --mysql_port=0 --postgresql_port=0 --interserver_http_port=0 --listen_host=127.0.0.1`
  (plus `--logger.log=...`); user `default`, no password. Up in under a minute.
- Pinned on-demand drivers (`DriverArtifact`), SHA-256 of the Maven Central files (their published `.sha1`
  matched): `org.duckdb:duckdb_jdbc:1.4.5.0` `5f11500c4fe8af47cfb15b36bb92e22597ad2109556c1564823e2db1a210072e`,
  class `org.duckdb.DuckDBDriver`; `com.clickhouse:clickhouse-jdbc:0.10.0:all`
  `d83736e24306e11929b0cf81b3306ae903c6f51c735c509ad5680c88640d99ce`. The ClickHouse jar registers both
  `com.clickhouse.jdbc.Driver` (JDBC v2, used) and the older `ClickHouseDriver` wrapper, and does not shade
  SLF4J: `slf4j-api` must come from the parent class loader (Paper gives plugins one).
- ClickHouse 26.12 answers the 0.10.0 driver in a compression it cannot read ("Invalid LZ4 magic byte")
  unless the connection sets `clickhouse_setting_network_compression_method=LZ4` (what CoreProtect sets) or
  `compress=false`. With `binary_string_support=true` a `String` column reads as raw bytes through
  `getBytes` (JDBC type VARCHAR), which is how CoreProtect's views expose blobs.
- MariaDB Connector/J 3.5: `Connection.setReadOnly(true)` sends nothing to a single server unless
  `assureReadOnly` is set, so a write still succeeds; `SET SESSION TRANSACTION READ ONLY` makes the session
  refuse writes. `useSsl=true` is a deprecated alias that turns into `sslMode=verify-full` (with a warning);
  CoreProtect's `enable-ssl` does not check certificates, so the import uses `sslMode=trust`.
- sqlite-jdbc's read-only `open_mode` is 65 (read-only plus the URI flag); opening a missing file read-only
  fails and creates nothing.
- CoreProtect's `/co purge` (`command/PurgeCommand.java`, 24.x/25.x) on SQLite copies each table into a
  new one with `INSERT INTO ... SELECT`; only `block` copies `rowid` as well, so every other table is
  renumbered from 1 in the same order and an import cursor's rowid then names a later row or none. On
  MySQL and DuckDB a purge deletes rows in place and row ids are kept. `RowidScan.anchor` handles both.
- CoreProtect's SQLite event tables have no declared key (the implicit `rowid` is the row id); `user` and
  `entity_spawn` declare `id INTEGER PRIMARY KEY`, an alias of the rowid, so `rowid` works on every table.
  DuckDB's explicit `rowid` column (CoreProtect's, fed by a sequence) is what `rowid` names in SQL there.
- MockBukkit 4.120 for the import's item decoding (verified 2026-11-21): `getClassByAlias("ItemMeta")` is
  MockBukkit's `SerializableMeta`, and `ConfigurationSerialization.deserializeObject` rebuilds plain,
  potion, leather, stew, banner and map meta from `ItemMeta#serialize()` output. But `new ItemStack(type)
  .hasItemMeta()` is `true` there (Paper: `false`), so "has components" is tested with
  `isSimilar(new ItemStack(type))`; `serializeAsBytes` then `deserializeBytes` drops a potion's custom
  colour; and `serializeAsBytes` of an item with attribute modifiers throws `ItemSerializationException`
  (`AttributeMock` is not `Serializable`). Tests check those through `ServerItemDecoder.rebuild`, before
  serializing.
- MockBukkit's `Bukkit.createBlockData(String)` fills the defaults as Paper does
  (`minecraft:oak_stairs[facing=east,half=bottom]` reads back with `shape=straight,waterlogged=false`) and
  throws `IllegalArgumentException` for an unknown material. `MockBukkit.createMockPlugin("CoreProtect")`
  makes `isPluginEnabled("CoreProtect")` true.
- An `ObjectInputFilter` set on a `BukkitObjectInputStream` applies to everything it reads, wrapped
  `ConfigurationSerializable` values included: a `java.io.File` in a blob is rejected with
  `InvalidClassException` before it is constructed.
- `ImportServiceIntegrationTest` runs the whole import (`ImportScenario`, every row kind) from real engines
  with the same variables as `CoreProtectSourceIntegrationTest`; the server engines are found through a
  written CoreProtect `config.yml`. It hard-links the cached driver jars from `~/.cache/hawkeye-ref/drivers`
  into the plugin's `drivers` folder (copies when the temp folder is on another file system), so nothing
  is downloaded. Verified 2026-11-21 on MariaDB 11.4.5, DuckDB 1.4.5.0 and ClickHouse 26.12.1, in one test
  JVM with the reader test: two DuckDB driver class loaders (the reader test's resolver and the plugin's)
  load side by side.
- MockBukkit's plugin class loader throws `NullPointerException` ("No jar file selected") from `findClass`
  for a class it does not have, where Paper's throws `ClassNotFoundException`; a `URLClassLoader` with it
  as parent then fails the same way. `DriverResolver` wraps its parent so either counts as not found.
- `.gitignore`'s `run/` (for Paper server folders) ignores every folder named `run`, packages included:
  the import's run loop was once written as `core.service.importer.run` and its files were silently left
  out of commits. Never name a package `run`; check `git status --ignored` after adding a package.
- `ImportService.tune` (package-private, tests only) lowers the batch size below `archive.segment-events`'
  minimum of 16,384 and the 30 s progress interval, so cancel and resume can be tested on a few hundred
  rows.



- Commit after every logical, working unit and push right after. Merge `origin/main`, never rebase or
  force-push published history.
- No AI attribution anywhere: commits, pull requests, release notes, code review comments, docs.
- Conventional commit prefixes (`feat:`, `fix:`, `build:`, `ci:`, `docs:`, `test:`, `chore:`).
- Strict Java style from the `java-development` skill: explicit visibility, `@NotNull`/`@Nullable`
  on parameters, no `return null`, records and sealed interfaces, SLF4J, constructor injection,
  layered packages, files under 1000 lines.
- Moonrise house build: Gradle Kotlin DSL, version catalog with camelCase aliases, the shadow jar
  named after the plugin in `build/libs`, the plain `jar` task disabled, Java 25.
- Every player-facing message is configurable (MiniMessage); commands, permissions and config keys
  are contracts with operators.
- Folia support by default (`folia-supported: true`): schedule through the engine `Scheduler` only.
- Repositories are private and carry no license file unless the owner adds one.

## Environment facts (verified 2026-11-12)

- Checkouts under `~/Documents` live in an iCloud Drive file-provider domain. iCloud can write
  conflict copies of build outputs (`Foo 2.class`, `index 3.html`); a `Foo 2.class` in
  `build/classes/java/test` fails the test task with "wrong name". Fix: delete them
  (`find . -name '* [0-9]*' -not -path './.git/*' -prune -exec rm -rf {} +`, after checking no source
  file matches) and exclude the checkout, or at least its `build` and `.gradle` folders, from sync with
  `xattr -w 'com.apple.fileprovider.ignore#P' 1 <folder>` (macOS 14 or later; local, not in git).
- Local JDKs on the owner's Mac: Temurin 25 at `/Library/Java/JavaVirtualMachines/temurin-25.jdk`;
  the default `java` is OpenJDK 26. Gradle 9.9.0 runs on JDK 26 and compiles with the Temurin 25
  toolchain. Its "restricted method System::load" and `sun.misc.Unsafe` warnings (Gradle's
  native-platform, Lombok, ByteBuddy in MockBukkit) are harmless.
- `https://repo.moonrise.gg/repository/maven-releases/` and `.../maven-snapshots/` allow anonymous
  read: no credentials are needed to build. Publishing needs `nexusUsername`/`nexusPassword`; no CI
  publishes (`./gradlew publish -Pversion=26.4` by hand).
- GitHub Actions in `moonrise-studios` refused to start any job on 2026-11-12 ("recent account
  payments have failed or your spending limit needs to be increased"); the run shows as failed with
  no steps. That is billing, not the workflow: build locally or on Jenkins until it is fixed.
- MockBukkit 4.119 loads a plugin through a ByteBuddy subclass in the unnamed package, and Moss scans
  `getClass().getPackageName()`, which would then be the whole class path. Tests load
  `support.TestHawkeyePlugin`, which repeats `MossPaper.onLoad` with the real base package. In tests
  the engine is not relocated, so its own beans (`PlayerArgument`, `Cooldowns`, ...) are not scanned.
- MockBukkit cannot build Cloud's `PaperCommandManager`: `HawkeyeCommandRegistry` logs the failure and
  the plugin still enables. Tests register the command beans on `support.TestCommandManager`
  (`TestPlugins.commands()`).
- `MockBukkit.load(Class)` gives the plugin a synthetic descriptor (version `0.0.0`): compare against
  `getPluginMeta().getVersion()`, never a literal. MockBukkit does not register the permissions of a
  `paper-plugin.yml`.
- MockBukkit builds a Bukkit `LibraryLoader` on every `registerEvents`, whose Maven resolver calls
  `InetAddress.getLocalHost()`. On macOS a host name missing from `/etc/hosts` waits 5 s for mDNS per
  call, so the test JVM resolves names from `src/test/hosts` (localhost only).
- MockBukkit 4.119 throws `UnimplementedOperationException` from, among others,
  `ScheduledTask.cancel()` for region and entity tasks; JUnit reports that as a skipped test.

## Build facts (verified 2026-11-12)

- Pinned and resolved: Gradle 9.9.0, Shadow 9.6.1, paper-api `26.3.build.246-stable`, plugin-engine
  `1.10.1`, moss-paper `1.2.2`, spring-context `6.2.13`, jakarta.annotation-api `3.0.0`, cloud-paper
  `2.0.1`, cloud-annotations `2.0.0`, configlib-yaml `4.8.1`, Lombok `1.18.48`, JetBrains annotations
  `26.1.0`, JUnit `6.1.3` with platform launcher `6.1.3`, MockBukkit `mockbukkit-v26.3` `4.120.0`,
  ArchUnit `1.5.1`.
- The engine jar is a fat jar whose POM declares no dependencies. Moss, Spring, Jakarta annotations,
  Cloud and ConfigLib are `compileOnly` (and on the test class path) because the engine
  `PaperPluginLoader` downloads exactly these versions at server start. When the engine version
  changes, read the new loader's defaults and move the catalog with it; Dependabot ignores these
  five for that reason.
- Engine 1.10.1 `PaperPluginLoader.classloader(builder)` calls `onClassLoaderInit(builder)`, then reads
  `libraries()` (`group:artifact:version` strings; an entry with the same `group:artifact` as an engine
  default replaces it), then `addLibraries(resolver)`. It resolves from the Maven Central mirror plus
  the Moonrise releases and snapshots repositories.
- The shadow jar bundles our classes and the engine only (an include filter), relocates
  `gg.moonrise.engine` to `gg.moonrise.hawkeye.libs.engine` and excludes the engine's
  `PaperCommandRegistry`, which runs handlers on Cloud's async coordinator; `HawkeyeCommandRegistry`
  uses `simpleCoordinator` instead. `verifyPluginJar` checks all of it, plus the package directory
  entry Spring needs to list classes.
- Moss 1.2.2 `MossPaper.disableComponents` ends with `Bukkit.getScheduler().cancelTasks(this)`, which
  throws on Folia and skips the Spring context close; `HawkeyePlugin` overrides it with the global
  region and async schedulers.
- Moss lifecycle: `Loadable` (after the context refresh in `onLoad`), `Enableable`, `Disableable` and
  the engine's `Reloadable` (run by `PaperPlugin.reload()`); `@PostConstruct` works. Every `Listener`
  bean is registered at enable.
- `processResources` expands `${version}` in `paper-plugin.yml`; `release.yml` passes `-Pversion=<tag>`.
- CI pins `gradle/actions/setup-gradle@v5` on purpose. v6 (2026-03) moved caching into the closed-source
  `gradle-actions-caching` component; using v6 accepts the Gradle Terms of Use. Dependabot ignores
  `gradle/actions >= 6` until the owner decides to accept them.

## Release facts

- Versions follow MyCore's scheme and are year.quarter, computed in `build.gradle.kts` (there is no
  `version` in `gradle.properties`): a stable release is `26.4` (a second one in the same quarter
  `26.4.1`), a Jenkins build `26.4.B<BUILD_NUMBER>`, anything else `26.4-SNAPSHOT`. The quarter comes from
  the HEAD commit's date in UTC, so a rebuild of an old commit keeps its version. The build fails on any
  other version. The jar is `build/libs/Hawkeye-<version>.jar`.
- Jenkins (`ci.moonrise.gg`, `Jenkinsfile`) is the only build and test CI for pushes; GitHub Actions only
  runs `release.yml`.
- The repository webhook (push events, JSON, no secret, same as MyCore's) posts to
  `https://ci.moonrise.gg/github-webhook/`; set up 2026-11-21, the ping returned 200.
- A release is: publish a GitHub release tagged `26.4` (`v26.4` also works). `release.yml` builds
  `-Pversion=26.4` (jar and `verifyPluginJar`, no tests: Jenkins ran them) and attaches `Hawkeye-26.4.jar`.
  Nothing to bump afterwards.
- Without GitHub Actions: build locally with `./gradlew build -Pversion=26.4` and attach
  `build/libs/Hawkeye-26.4.jar` with `gh release create 26.4 --verify-tag`.
