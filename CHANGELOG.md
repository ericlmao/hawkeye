# Changelog

All notable changes to this project are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions are year.quarter (`26.4`, `26.4.1` for a
second release in a quarter).

## [Unreleased]

### Added

- Error and crash files: each of Hawkeye's warnings and errors is shown once in the console, as one line without
  its stack trace that names its file; the stack trace and every repeat are written to
  `plugins/Hawkeye/errors/Error-<year>-<month>-<day>-<hour>-<minute>.txt`. A failure to load or enable (the
  Spring context, a bean, a listener) and a Hawkeye thread that stops are written to
  `plugins/Hawkeye/crash/Crash-<...>.txt` instead, and a plugin that cannot start disables itself instead of
  throwing. Every file starts with the Minecraft version, the server type (Paper, Folia, Purpur, Leaf, Gale,
  Pufferfish or Spigot, told apart by their classes), the server's name and version, Java, the operating system,
  Hawkeye's version and the plugin list. Problems first logged in the same minute share a file; a repeat goes
  into the file of its first time, one line each for the first 100, then one line a minute that counts them.
- Error and crash review: players with the new `hawkeye.errors` permission (operators by default) are told on
  join how many files wait to be reviewed (`error-reports.join-notice`); `/hawkeye errors` lists them with the
  first problem of each and `/hawkeye errors reviewed` moves them to `errors/reviewed/` and `crash/reviewed/`
  (later repeats of a reviewed problem go to its reviewed file). New `messages.yml` section `errors`.
- Discord webhook for errors and crashes: `error-reports.webhook-url` (empty by default; `${NAME}` reads an
  environment variable) gets the first time of every problem with its file attached, `webhook-errors` and
  `webhook-crashes` choose what is sent. Repeats are never sent, mentions are off, the URL is never logged, and
  the webhook is read before Hawkeye starts so a failed start is posted too.
- Lookup dialog (experimental): with `experimental.dialog-lookup: true` (off by default; applied on
  `/hawkeye reload`), players get `/hawkeye lookup` and `/hawkeye near` as a dialog instead of chat lines. It
  shows the same result lines (hovers, click a location to teleport, click a book to read it) with Previous and
  Next buttons, and a Filters form with one field per parameter (`u:`, `t:`, `r:`, `a:`, `i:`, `e:`, `s:` with
  `hawkeye.network`, `#count`) that searches again; a count, an empty result or a refused parameter is shown
  above the form. New `messages.yml` section `lookup-dialog`. Needs Paper 1.21.7 or newer and a 1.21.6 or newer
  client; older servers, the console, the inspector and `/hawkeye page` keep the chat output.
- Dev mode: a new `config.yml` section `dev-mode` with `enabled` (off by default; applied on `/hawkeye reload`)
  for developing Hawkeye itself, never for a real server. While it is on, `/hawkeye dev generate <count> [days]`
  writes that many generated fake events (fake players, places, inventories and every action) over the last
  `days` days (30 by default) straight into the archive, each run under its own server entry
  `<server.name>-dev`, `-dev-2`, ..., with a progress line every 10 seconds; `/hawkeye dev status` shows it and
  `/hawkeye dev cancel` stops it after the current batch, as turning dev mode off does. New permission
  `hawkeye.dev` (operators by default, part of `hawkeye.*`) and `messages.yml` section `dev`. The console says
  once at INFO whenever dev mode is turned on.

### Changed

- The console and `logs/latest.log` no longer hold Hawkeye's stack traces or repeated warnings (a database or
  Redis that is down, HikariCP's warnings about Hawkeye's pools, a failing listener): they are in
  `plugins/Hawkeye/errors` and `crash`. `scripts/smoke/run.py` also fails on an ERROR of Hawkeye's class loggers,
  not only of the `Hawkeye` logger.

### Fixed

- Right-clicking with a block in hand while inspecting now shows the history of the spot the block would fill;
  it used to do nothing.

### Tests

- The tests log through SLF4J into a real log4j-core (`src/test/resources/log4j2-test.xml`: WARN and above, printed
  nowhere), as plugins do on a server, so the error log's filter is tested where it runs.
- `scripts/smoke/dev-data.txt` runs dev mode on a real server: fake data is generated, looked up and rolled
  back, and a long run reports its progress and is cancelled. `scripts/smoke/run.py` now waits up to a minute
  for an expected reply that takes longer than three seconds.

## [26.4] - 2026-11-26

### Added

- Storage engine for SQLite, MySQL/MariaDB and PostgreSQL with one schema and idempotent, versioned
  migrations; 64-bit event ids (time, server node, sequence) generated without a database round trip;
  chunk-major packed block positions; dictionaries for worlds, actors and block/item/entity types that
  are safe with any number of writers.
- A lock-free capture queue and a single writer thread with batching, retry with back-off, and a disk
  spool for events that could not be written before shutdown.
- An archive tier: settled events are packed 262,144 at a time into immutable column segments
  (archive format 2: zstd frames, units of 16,384 rows read by byte range, ranks implied by the
  stored order), about 1.2 bytes per event on replayed real history and about 6 per random block
  event on SQLite, against about 80 for CoreProtect's relational layout; rolled-back flags of
  archived events live beside the segments; every read merges both tiers. An archive read matches a
  lookup's place, actor, action, type and rollback conditions on a unit's columns before it decodes
  ids or builds rows, and block and area lookups skip the chat and command rows of segments whose
  players were elsewhere.
- A segment index (schema version 5: `region_segment`, `block_segment`, `actor_segment`,
  `segment_index`; it replaces version 4's `place_region`, `place_chunk` and `place_state`, and every
  archive is indexed again): per chunk, the archive segments holding rows there, and for chunks active
  in more than 16 segments, per position its newest 64 segments exactly and only older ones in buckets
  of 16 segments. It is kept up to date in the transaction that packs, rewrites or purges a segment,
  and the archiver adds segments written before it existed (newest first, up to 20 s per run). A
  block or area lookup reads only the segments that can hold the place, so a block never touched
  inside a chunk that is active all the time no longer reads every segment, and a block with little
  history inside it reads only the segments that hold it. Per actor, the segments holding its rows: a
  lookup by up to 64 players reads only their segments instead of testing every segment's header,
  and a lookup by player and place reads only the segments both name.
- CoreProtect-compatible parameters (`u: t: r: a: i: e: s: #preview #count`) for lookups, rollbacks,
  restores and purges.
- Multi-server operation: shared databases, shards read in parallel for network lookups, network
  rollbacks and restores as durable jobs, a Redis pub/sub bus with database polling as the fallback,
  heartbeats and `/hawkeye network`.
- Capture of block breaks and places, explosions (attributed to the player who lit the TNT), fire,
  fluids, pistons, natural changes, buckets, signs, interactions, entity block changes, kills,
  container transactions, hoppers (off by default), item drops and pickups, chat, commands (with
  redaction) and sessions.
- `/hawkeye lookup`, `near`, `page` and `tp` with clickable results, `#count`, sessions with pinned
  pages, and lookups across shards merged newest first with per-shard warnings.
- Opening a lookup or an inspection counts at most the 16 newest archive segments with matches (the
  hot table always exactly), so a large result shows lower bounds (`160+`, new message
  `lookup.at-least`, default `<value>+`) instead of making the first page wait seconds for the whole
  history; `/hawkeye page` walks past a lower bound until the events end, and `#count` and
  `HawkeyeApi#count` stay exact.
- The inspector (`/hawkeye inspect`): block history, container transactions, the adjacent block.
- `/hawkeye import coreprotect [source]`, `import status` and `import cancel` (permission
  `hawkeye.import`, default op; messages section `importing`): copies the history of a CoreProtect
  database (SQLite, MySQL/MariaDB, DuckDB or ClickHouse, found through CoreProtect's own `config.yml`
  and opened read-only) into Hawkeye's storage as archive segments numbered under an import node of
  their own (`<server.name>-coreprotect`, left out of `/hawkeye network`). Each segment is written in
  one transaction with a compare-and-set checkpoint (schema version 6, table `import_state`), so an
  import can be cancelled, resumed after a crash and run again later for new rows without importing
  anything twice. Existing events and player names are never changed; rows older than 2025-01-01 and
  actions without a Hawkeye equivalent are skipped and counted. The DuckDB and ClickHouse drivers are
  downloaded from Maven Central on first use into `plugins/Hawkeye/drivers/` and checked against a
  pinned SHA-256. Existing `messages.yml` files keep their old `general.help` list; add the
  `/he import coreprotect [source]` line by hand or delete the list to get the new default.
- `/hawkeye rollback` and `restore` with chunk-by-chunk application on the owning region (blocks,
  container contents, sign text, container transactions, killed entities), `#preview` with `apply`
  and `cancel`, `undo`, network rollbacks with `s:`, and `/hawkeye purge` plus a lease-guarded
  automatic purge.
- `/hawkeye`, `help`, `status` (queue, writer, archive and network jobs) and `reload` with every
  permission node under `hawkeye.*`.
- `HawkeyeApi` in Bukkit's `ServicesManager` for other plugins.
- Compact crop growth (`BLOCK_GROW`: ripe crops and newly grown blocks under `#growth`, every stage
  with `logging.crop-growth-stages`), bone meal, cauldrons, sponges, portals, berries, sheared
  pumpkins and beehives, flower pots, dispensers, dragon eggs, the TNT block itself and exploding
  beds and respawn anchors.
- Attached blocks (`logging.attached-blocks`): blocks that fall off when their support goes, cascades
  included, under whoever removed the support.
- Cause attribution: TNT lit by redstone or fire counts for its placer, withers for their builder,
  end crystals and creepers for the player who hit or lit them, liquid flows for the player who
  poured them (`logging.liquid-tracking`).
- Entities: placed and spawned (armour stands, boats, minecarts, paintings, item frames, end
  crystals, spawn eggs, built golems and withers, bucket mobs) and destroyed or taken ones, with
  rollback (removal and respawn); item frames and armour stands as containers; shearing, naming,
  leashing, unleashing, taming and dyeing.
- Items: crafting, throwing and shooting, worn-out tools, trades, arrow pickups and death drops; hopper
  ground pickups; jukeboxes, chiseled bookshelves, decorated pots, shelves and lecterns as containers
  (with rollback).
- Books, game mode changes, sign-click and console commands, and kick reasons on quits.
- Containers carried by entities: minecarts with a chest or hopper, chest boats and rafts, and donkeys,
  mules and llamas record their transactions at the block the entity stands in, hopper transfers into
  and out of minecarts record both ends, and a rollback puts the items back into (or takes them out of)
  such an entity standing in the recorded block. Other entities with an inventory (players, villagers,
  horses) are still not recorded.
- `config.yml` and `messages.yml` with every default documented; invalid files keep the previous
  configuration, and restart-only changes are reported.
- Database and Redis credentials from environment variables, the recommended setting and the default:
  `${NAME}` in `storage.username`, `storage.password`, `storage.parameters` values (also in every
  `network.shards` entry) and `network.redis-uri` is replaced by the environment variable `NAME` when
  Hawkeye connects. `username` and `password` default to `${HAWKEYE_DB_USERNAME}` and
  `${HAWKEYE_DB_PASSWORD}`; a literal value still works. An unset variable is a configuration error that
  names the variable. Credentials never reach a log, the console or a player: the server engines get
  them as driver properties only (no longer in the JDBC URL or Hikari's configuration), driver errors are
  scrubbed (`<redacted>`), a `config.yml` error names the key instead of quoting a section, and a numeric
  `password: 123456` is read as text. The CoreProtect import shows its source without the username, and
  scrubs driver errors and YAML errors of CoreProtect's config the same way.
- Broken cushions (Paper's `EntityBreakEvent`) are recorded as entity removals, under the player or
  entity that broke them, whoever removed their support (else `#physics`), or `#explosion`; a rollback
  puts them back.
- Turtle eggs cracked by a player or mob walking or landing on them are recorded (`entity-changes`): one
  egg fewer as a change, the last egg as a break, under that player or mob.
- CoreProtect's action synonyms in `a:` (`broke`, `placed`, `clicks`, `deaths`, `login`, `logout`, `drops`,
  `pickups`, `transactions`, ... ) and a leading `#` (`a:#block`), and `#sum` for `#count`. Suggestions
  still list only Hawkeye's own names.
- Commands sent over the remote console (RCON) are recorded under `#rcon` with the console commands
  (`console-commands`, the same redaction), so a leaked RCON password used for `op` or `give` leaves a
  trace.
- Inventory snapshots: a copy of a player's whole inventory (storage, armour, off hand and the stack on
  the cursor) when they join, leave, teleport and die, each with its own switch (`logging.join-snapshots`,
  `quit-snapshots`, `teleport-snapshots`, `death-snapshots`, all on). A teleport is recorded where the
  player left from, with its cause and destination (`ender_pearl to world_nether 10 64 -5`; portals and
  end gateways included); a world change without a teleport event (a respawn in another world) where they
  arrived (`world_change from world`). Position corrections, dismounts and leaving a bed are skipped, and
  within one world at most one teleport a second per player is recorded. A death is recorded before the
  items drop, with the death message. New actions 52-55 (stored data: payload tag 7) and messages
  (`actions.snapshot-join`, `snapshot-quit`, `snapshot-teleport`, `snapshot-death`, placeholder
  `<contents>`; `lookup.snapshot-contents`, `snapshot-item`, `snapshot-empty`): a lookup line shows the item
  and stack count, its hover every item. Snapshots are history only. `a:snapshot` (or `snapshots`) looks
  them all up, `a:join-snapshot`, `quit-snapshot`, `teleport-snapshot` and `death-snapshot` one kind.
- Ender chest and plugin inventory transactions (`logging.virtual-containers`, on): items a player puts
  into or takes out of an ender chest (their own or, as staff, someone else's), another player's inventory
  (an /invsee view) or a plugin's storage inventory (player vaults, backpacks: a chest, barrel, shulker box,
  hopper, dispenser or dropper inventory that belongs to no block or entity), one event per kind of item,
  recorded where the player stands with the inventory's name (`ender_chest`, `ender_chest (Steve)`,
  `inventory (Steve)`, or the title). A change of the inventory counts only as far as what the player holds
  (inventory, cursor, and what they drop from it) changed the opposite way, so plugin menus that change
  their own contents record nothing. New actions 12 and 13 (stored data: payload tag 8) and messages
  (`actions.virtual-deposit`, `virtual-withdraw`; `<text>` is the inventory's name). History only: a
  rollback never changes them. `EventRecorder.container` (and `HawkeyeApi.logContainer`) now refuse any
  action other than `CONTAINER_DEPOSIT` and `CONTAINER_WITHDRAW`. `a:container`, `+container` and
  `-container` include them (a rollback still changes only container blocks and entities); `a:virtual`,
  `+virtual` and `-virtual` (also `enderchest`, `ender-chest`, `vault`, `vaults`) look up only them.
- Item details in lookup results: the hover of an item's name lists its custom name, enchantments (stored
  enchantments of an enchanted book too) and, for a written book or a book and quill, the title, author and
  page count, read from the stored item NBT without a server (`util.serialize.ItemDetails`). Clicking a book
  with pages opens it for the player who clicked, for an hour after the lookup (an Adventure click
  callback); click events in its pages are dropped. New `messages.yml` keys: `lookup.item-name`,
  `item-enchantment`, `item-book`, `item-writable-book`, `item-read-hint`. Plain items keep their hover.
- Paper and Folia 1.21.1 to 1.21.11 and 26.1.2, besides 26.3, from the same jar. Hawkeye decides once at
  startup which newer API the server has, so the capture path checks nothing per event. On older servers:
  a mob a dispenser releases from a bucket is recorded under `#spawn_egg` before 1.21.4; crafted items are
  counted from the click before 1.21.11 (a shift-click counts the crafts the ingredients and the free
  inventory space allow, control-drop one craft); a boat or minecart destroyed without an attacker is
  blamed on `#unknown` before 26.1.2; a golem or wither built by a player is recorded as spawned by them,
  without the pattern blocks it used up, before 26.2. Shelves (1.21.9) and cushions (26.3) are recorded
  where they exist. README's "Server versions" lists it.
- `commands.yml`: configurable aliases for `/hawkeye` (`root-aliases`, default `[he]`) and its
  subcommands (`subcommand-aliases`, defaults `inspect: [i]`, `lookup: [l]`, `rollback: [rb]`,
  `restore: [rs]`), so `/co` can keep working for staff coming from CoreProtect. Aliases are validated
  on load and apply after a restart; `/hawkeye reload` reports a changed file as restart-only.

### Changed

- `paper-plugin.yml` declares `api-version: '1.21.1'` (was `26.3`), and every class is Java 21 bytecode so
  the jar loads on Java 21 (1.21.x servers). The build still runs on Java 25: `src/main` compiles against
  paper-api 1.21.1, the new `src/newerApi` and the tests against 26.3.
- Versions are year.quarter: a stable release is `26.4`, a Jenkins build `26.4.B<build>` (`26.4.B25`), any other
  build `26.4-SNAPSHOT`. Release tags are `26.4` (or `26.4.1` for a second release in a quarter) instead of
  `vX.Y.Z`. The jar is named after its version (`Hawkeye-26.4.jar`, `Hawkeye-26.4.B12.jar`).
- `Jenkinsfile` builds and tests every push and archives only that build's jar. GitHub Actions no longer builds
  every push or publishes to the Moonrise Nexus: `release.yml` only attaches the jar to a published release.
- plugin-engine 1.10.2 and Moss 1.2.3 come from Maven Central; the build no longer uses
  `repo.moonrise.gg`, and at server start the engine downloads its libraries from Maven Central only.
- Runtime libraries the plugin loader downloads at server start: HikariCP 7.1.0, sqlite-jdbc 3.54.1.0,
  MariaDB Connector/J 4.0.0, PostgreSQL JDBC 42.7.14, Caffeine 3.3.1, Lettuce 7.9.0 and zstd-jni 1.5.7-28.
  Connector/J 4 defaults to TLS with a verified certificate (`sslMode=verify-full`); a MySQL or MariaDB
  storage connection keeps the former default instead, `sslMode=disable`, plus `allowPublicKeyRetrieval=true`
  so MySQL 8's `caching_sha2_password` works over it. Set `sslMode` (or the legacy `useSsl`) in
  `storage.parameters` to encrypt, for example `sslMode: verify-full` or `sslMode: trust`.
  Built with Gradle 9.9.2. Paper API stays at 26.3 build 249: build 256 adds the `minecraft:world_clock`
  registry, which no MockBukkit release up to 4.125.0 has data for, so every MockBukkit test fails on it.

- Breaking (before the first release): the archive format and its settings. Schema version 3 drops the
  development archive (`segment` and `segment_actor`); its segments are discarded, not converted, and
  a warning says so. `archive.batch-events` is removed (a file that still has it loads, and the key is
  dropped when the file is written back); `archive.segment-events` (262144) and
  `archive.settle-seconds` (60) are new; `archive.interval-minutes` now defaults to 1 and
  `archive.after-hours` is when fewer than a segment of events are packed anyway.
- Event ids are issued by the writer on a one-second grid: every event of a second gets that second
  and its rank among the server's events of the second (messages and sessions in capture order, then
  block events by world, position and capture order), so the archive never stores a rank.
- `/hawkeye status` shows the archive's stored size (`<archive-size>`).

### Fixed

- Lava turning into basalt (over soul soil, next to blue ice) is recorded as a fluid change under the
  player who poured the lava (else `#lava`), like stone and obsidian, instead of as natural forming.
- A block a piston destroys is recorded as broken, no longer as moved one block on.
- Flowing water and lava are recorded with their flowing level, so a restore never creates sources.
- Killed mobs are stored with their entity again (Paper 26.3 refuses to serialize a dying mob without
  `EntitySerializationFlag.FORCE`), so a rollback respawns them, at full health and at rest.
- Item and entity snapshots are stored as raw NBT instead of Paper's per-item gzip, and entity
  snapshots are normalized (UUID, motion, fire, timers, spawn bookkeeping, combat memory; position and
  rotation for mobs only), so the archive compresses them together: about 7 bytes per kill and under
  2 bytes per repeated item on a real server. Rows written in Paper's gzip form still read.
- Removed entities (paintings, frames, vehicles, bucketed mobs) are serialized only with
  `rollback.restore-entities` on, as kills already were.
- A transaction that fails with an `Error` (such as running out of memory between two statements) is
  rolled back before the connection's auto-commit is restored; restoring it used to commit the
  statements that had run, so an import segment could be stored without its checkpoint.
- An `Error` on the import thread ends the import as failed and frees it; it used to leave the
  import marked as running until a restart.
- The CoreProtect import refuses a MySQL or ClickHouse host from CoreProtect's `config.yml` that is not a
  host name or IP address, and a database name with anything but letters, digits, `_`, `-` and `$`,
  so neither can add parameters to the JDBC URL.
- The CoreProtect import tells a missing table from a failed query by the engine's catalog: a table it
  cannot read fails the import instead of being skipped as absent, and a database without CoreProtect's
  `block`, `user` and `world` tables is refused before an import node or checkpoint is created.
- A CoreProtect import run again after a `/co purge` of a SQLite database no longer misses rows: the
  purge renumbers every table but `block`, so a stored cursor whose row id now names another row (or none)
  is re-anchored by time, with a warning in the log.
- A CoreProtect import from MySQL/MariaDB or ClickHouse stops two minutes before its start, so rows
  CoreProtect is still writing (an open transaction, a ClickHouse batch published late) are left for the
  next run instead of being passed by. On ClickHouse, read in time order, a row stamped in the far future
  is never consumed: it used to move the table's cursor past every later row.
- An import that leaves rows for the next run says so: the finished reply is followed by a new message,
  `importing.left` (placeholder `<left>`), and the final progress keeps the estimate of those rows
  instead of 0.
- A failing estimate of the rows left (a ClickHouse count that times out) keeps the previous estimate
  instead of failing the import.
- The CoreProtect import rebuilds player heads, and bundles holding them, with their owner and skin. The
  item filter rejected the profile `ConfigurationSerialization` rebuilds from the blob (Paper's
  `CraftPlayerProfile`, outside `org.bukkit`), so every such blob counted as undecodable: all 4,929 that
  Paper 1.21.4 could not rebuild from the newest rows of a production CoreProtect 25 database.
- The CoreProtect import keeps what CoreProtect stores beside an item's meta: the items in a bundle, the
  projectiles loaded in a crossbow and the explosions of a firework rocket or star, which it used to drop.
  An item inside another that the server cannot rebuild, or that the container cannot hold, is left out,
  and the container keeps the rest.
- Items a hopper, hopper minecart or dropper moves into or out of a container while a player has it open
  are no longer recorded as that player's deposits and withdrawals (also with `logging.hoppers` off), so
  rolling the player back no longer duplicates or deletes them. What the player moved before the
  transfer, in the same tick, is still theirs.
- The inspector shows the transactions of a right-clicked lectern, jukebox, chiseled bookshelf, decorated
  pot or shelf, as for any container, instead of the block in front of it.
- An unexpected error in one pass of the writer thread is logged, counted and retried with back-off;
  it used to end the writer for good, so every later flush (and with it every lookup and rollback)
  waited forever and the queue filled up and dropped events.
- `HawkeyeApi#logRemoval` keeps a removed container's items and a sign's text, as Hawkeye's own breaks
  do, so a rollback no longer restores an empty chest or a blank sign; a waterlogged block leaves water
  instead of air.
- A rollback preview ends when its player leaves. It used to survive a relog, so `/hawkeye apply` applied
  a plan the client no longer showed.

### Tests

- MockBukkit, SQLite and opt-in MySQL/PostgreSQL integration tests, an ArchUnit layer check, a
  storage footprint guard, `verifyPluginJar`, and real-server smoke tests (`scripts/smoke`: TNT and
  kill rollbacks).
- Version support: `scripts/compat/api_links.py` checks every Paper call against a server's paper-api jar;
  `scripts/smoke/kill-rollback-basic.txt` and `coreprotect-generate-1.21.txt` run on 1.21.x servers too, and
  `run.py` ignores console colour codes.

[Unreleased]: https://github.com/ericlmao/hawkeye/compare/26.4...HEAD
[26.4]: https://github.com/ericlmao/hawkeye/releases/tag/26.4
