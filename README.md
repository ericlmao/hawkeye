<p align="center">
  <img src="assets/banner.png" alt="Hawkeye: block logging, rollbacks and network sync for Paper and Folia">
</p>

<p align="center">
  <a href="https://modrinth.com/plugin/hawkeye"><img src="https://img.shields.io/modrinth/v/hawkeye?logo=modrinth&label=Modrinth&color=00AF5C" alt="Modrinth"></a>
  <a href="https://modrinth.com/plugin/hawkeye"><img src="https://img.shields.io/modrinth/dt/hawkeye?logo=modrinth&label=Downloads&color=00AF5C" alt="Modrinth downloads"></a>
  <a href="https://ci.moonrise.gg/job/hawkeye/job/main/"><img src="https://img.shields.io/jenkins/build?jobUrl=https%3A%2F%2Fci.moonrise.gg%2Fjob%2Fhawkeye%2Fjob%2Fmain%2F&label=Jenkins&logo=jenkins&logoColor=white" alt="Jenkins build"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/ericlmao/hawkeye?logo=github" alt="License: GPL-3.0"></a>
</p>

**Hawkeye** records what happens on your server (blocks, containers, entities, chat and commands) and
lets your staff look it up, inspect it and roll it back, on one server or across a whole network. It is
a ground-up alternative to CoreProtect: same lookup parameters, one-command import of your CoreProtect
history, and logs that take megabytes instead of gigabytes.

**Download:** [Modrinth](https://modrinth.com/plugin/hawkeye) ·
[GitHub releases](https://github.com/ericlmao/hawkeye/releases) ·
[dev builds](https://ci.moonrise.gg/job/hawkeye/job/main/lastSuccessfulBuild/)

## ► Contents

- [Highlights](#highlights)
- [Getting started](#getting-started)
- [Commands](#commands)
- [Parameters](#parameters)
- [What is recorded](#what-is-recorded)
- [Configuration](#configuration)
- [Error and crash files](#error-and-crash-files)
- [Running several servers](#running-several-servers)
- [Importing from CoreProtect](#importing-from-coreprotect)
- [Server versions](#server-versions)
- [API for other plugins](#api-for-other-plugins)
- [Contributing](#contributing)

## ► Highlights

- **Lookups, an inspector and safe rollbacks**, with preview, restore and undo.
- **CoreProtect's parameters** (`u: t: r: a: i: e:`) and an optional `/co` alias, so staff don't relearn anything.
- **Tiny storage.** Old history is packed into compressed column segments, about 1.1 bytes per event on real
  server history.
- **Built for networks.** Any number of servers on one database, shards, and network-wide lookups and rollbacks.
- **Keeps every event.** Lookups see events up to the moment you run them, and queued events survive a
  database outage or restart.
- **One jar** for Paper and Folia, 1.21.1 to 26.3.

### ➥ Compared with CoreProtect

| | CoreProtect | Hawkeye |
|---|---|---|
| A 13.5 million event database | 1.18 GB | **3.9 MB** after `/he import coreprotect` (303× smaller, imported in 17.5 s) |
| Several servers, one database | One writer only | Any number of writers, plus sharding |
| Network rollbacks | No | `s:<server>` or `s:#network` |
| Newest events in lookups | Misses the last 0.5 s | Always included |
| Database outage | Queued events lost on a crash or restart | Retried, and saved to disk on shutdown |
| Timestamps | 32-bit, overflow in 2038 | 64-bit, good until 2094 |

The ratio depends on your history: that database was 35 minutes of mass edits, which packs best. On a real
server's 1.4 billion events over 59 days, Hawkeye stores 1.085 bytes per event. Lookups, rollbacks and
restores read the compressed data directly. How storage works is in [docs/architecture.md](docs/architecture.md).

## ► Getting started

**Requirements**

- Paper or Folia 1.21.1 to 1.21.11, 26.1.2 or 26.3, on the Java your server needs (21 for 1.21.x, 25 for
  26.x).
- Nothing else for a single server: the default database is an SQLite file in the plugin folder.
- MySQL 8+, MariaDB 10.6+ or PostgreSQL 13+ to share a database between servers. Redis is optional.

**Install**

1. Put `Hawkeye-<version>.jar` in `plugins/` and start the server. On first start Paper downloads Hawkeye's
   libraries, and Hawkeye writes `config.yml`, `messages.yml` and `commands.yml` with every option commented.
2. Coming from CoreProtect? Run `/he import coreprotect` ([details](#importing-from-coreprotect)).
3. Try it: `/he i` and click a block, or `/he lookup u:Alex t:2h`.

## ► Commands

The root command is `/hawkeye`, alias `/he`. Aliases in brackets can be changed in
[`commands.yml`](#command-aliases).

| Command | Permission | What it does |
|---|---|---|
| `/he`, `/he help` | `hawkeye.help` | Version and command overview |
| `/he inspect` (`i`) | `hawkeye.inspect` | Toggles the [inspector](#inspector) |
| `/he lookup <parameters>` (`l`) | `hawkeye.lookup` | Searches the history, newest first |
| `/he near [radius]` | `hawkeye.lookup` | What happened around you |
| `/he page <page>` | `hawkeye.lookup` | Another page of the last lookup or inspection |
| `/he tp <world> <x> <y> <z>` | `hawkeye.teleport` | Teleports to a result (click a location to run it) |
| `/he rollback <parameters>` (`rb`) | `hawkeye.rollback` | Undoes changes; add `#preview` to see them first |
| `/he restore <parameters>` (`rs`) | `hawkeye.rollback` | Redoes rolled-back changes |
| `/he apply`, `/he cancel` | `hawkeye.rollback` | Applies or cancels your preview |
| `/he undo` | `hawkeye.rollback` | Reverts your last rollback or restore |
| `/he purge t:<age> [r:#world]` | `hawkeye.purge` | Deletes data older than the age |
| `/he import coreprotect [source]` | `hawkeye.import` | Imports a CoreProtect database |
| `/he import status`, `/he import cancel` | `hawkeye.import` | Shows or stops the running import |
| `/he status` | `hawkeye.admin` | Queue, writer and database health |
| `/he network` | `hawkeye.network` | Every server on the network and its heartbeat |
| `/he reload` | `hawkeye.admin` | Reloads `config.yml` and `messages.yml` |
| `/he errors` | `hawkeye.errors` | Lists the [error and crash files](#error-and-crash-files) waiting for review |
| `/he errors reviewed` | `hawkeye.errors` | Marks them reviewed (moves them to `reviewed/`) |

All permissions default to operators, and `hawkeye.*` grants them all. `s:` in any command also needs
`hawkeye.network`. Players with `hawkeye.errors` are also told on join when error or crash files wait.

### ➥ Inspector

With `/he inspect` on:

- **Left-click** a block to see its history (the block isn't broken).
- **Right-click** a container, lectern, jukebox, bookshelf, pot or shelf to see its transactions.
- **Right-click** any other block to see the block in front of the clicked face.
- **Place** a block to see that spot (the placement is cancelled).

## ► Parameters

Lookups, rollbacks, restores and purges take the same parameters as CoreProtect.

| Parameter | Meaning | Examples |
|---|---|---|
| `u:` | Players, or sources with `#` | `u:Alex,Steve`, `u:#tnt` |
| `t:` | How far back, or between two ages | `t:2h`, `t:3d12h`, `t:1h-2d` |
| `r:` | Radius around you, a world, or everywhere | `r:10`, `r:10x5` (5 up and down), `r:#world_nether`, `r:#global` |
| `a:` | Actions ([list below](#actions)) | `a:-block`, `a:+container`, `a:kill` |
| `i:` | Only these blocks, items or entities | `i:diamond_ore,chest` |
| `e:` | Except these types or players | `e:stone,Steve` |
| `s:` | Other servers | `s:creative`, `s:#network` |
| `#preview` | Show a rollback or restore to you only | |
| `#count` | Count matches instead of listing them (`#sum` works too) | |

#### › Actions

`+` means added or placed, `-` removed or broken; without a sign, both.

| Group | Actions |
|---|---|
| Blocks | `block`, `+block`, `-block`, `change`, `sign`, `click`, `grow` |
| Containers | `container`, `+container`, `-container` |
| Ender chests and plugin inventories | `virtual`, `+virtual`, `-virtual`, `enderchest`, `vault` |
| Items | `item`, `+item`, `-item`, `craft`, `throw`, `trade` |
| Entities | `kill`, `entity`, `+entity`, `-entity`, `spawn` |
| Players | `chat`, `command`, `book`, `gamemode`, `session`, `+session`, `-session` |
| Inventory snapshots | `snapshot`, `join-snapshot`, `quit-snapshot`, `teleport-snapshot`, `death-snapshot` |

CoreProtect's names (`broke`, `placed`, `login`, `drops`, a leading `#`) work too.

#### › Good to know

- A lookup without `r:` searches every world of this server.
- Rollbacks and restores **need a `t:`**. Without `r:` they use `rollback.default-radius` around you; the
  console must give `r:#world` or `r:#global`.
- A purge takes exactly one `t:<age>` and optionally `r:#world`. In game it can only delete data older
  than `purge.player-minimum-days`.
- Blame follows the cause: TNT a player lit and arrows they shot count as theirs, so
  `/he rollback u:Griefer t:1h` also undoes their TNT damage.
- Pages stay stable while new events arrive, and `/he page` works for `lookup.session-minutes`. On very
  large results the first page shows totals like `160+`; page on, or use `#count`, for the exact number.
- Hover an item in the results to see its name and enchantments, and click a written book to read it.
- Experimental, off by default: with `experimental.dialog-lookup: true`, players get `/he lookup` and `/he near`
  as a dialog with the same lines (hover them, click a location to teleport, click a book to read it), Previous
  and Next buttons, and a Filters form with one field per parameter. The console, the inspector and `/he page`
  keep using chat. It needs Paper 1.21.7 or newer and a 1.21.6 or newer client.

## ► What is recorded

Every category has a switch under `logging` in `config.yml`; everything except hoppers is on by default.
Changes without an obvious cause are blamed on whoever caused them: TNT lit by redstone on the player who
placed it, a wither on its builder, flowing lava on whoever poured it, a torch that falls off a wall on
whoever broke the wall.

| Category | Details |
|---|---|
| Blocks | Breaks (with container contents and sign text), places, multi-block places, buckets and waterlogging |
| Attached blocks | Torches, signs, rails, redstone, flowers and anything else that falls when its support goes |
| Explosions | Every destroyed block, blamed on the player who lit, placed, clicked or hit the cause, else the entity (`#creeper`) |
| Fire, fluids, pistons | Burning, lighting, water and lava flow, stone and obsidian forming, sponges, pistons |
| Natural | Leaf decay, melting, snow and ice, grass/vine/sculk spread, tree and mushroom growth, portals, cauldrons |
| Crop growth | Ripe crops and newly grown blocks as one `#growth` event (`crop-growth-stages` records every stage) |
| Signs | Edits with the text before and after, per side |
| Interactions | Doors, trapdoors, gates, levers, buttons, repeaters, comparators, note blocks, berries, flower pots and more |
| Containers | Chests and other containers, minecarts, chest boats, pack animals, jukeboxes, bookshelves, pots, shelves, lecterns, item frames, armour stands |
| Ender chests and plugin inventories | Ender chests, `/invsee` views and plugin storage such as vaults and backpacks (`virtual-containers`) |
| Dispensers and hoppers | Blocks and liquids a dispenser places or picks up; hopper transfers (off by default, high volume) |
| Entities | Placed, spawned, destroyed and killed entities, bucketed mobs, built golems and withers |
| Entity changes | Endermen, falling blocks, withers, ravagers and silverfish changing blocks, trampled farmland, turtle eggs |
| Entity interactions | Shearing, naming, leashing, taming, dyeing |
| Items | Drops, death drops, pickups, crafting, thrown and shot items, broken tools, villager trades |
| Chat and commands | Chat, player, sign, console and RCON commands (`redacted-commands` are stored without arguments), books |
| Sessions | Joins, quits with the kick reason, game mode changes |
| Inventory snapshots | A player's whole inventory when they join, leave, teleport and die |

**What a rollback reverses:** blocks, container items, item frame and armour stand items, killed and
destroyed entities (respawned), and placed entities (removed). Everything else is history only.

## ► Configuration

`plugins/Hawkeye/` holds `config.yml`, `messages.yml` and `commands.yml`. New options are added to an
existing file on start, and a file with a mistake keeps the previous configuration. `/he reload` applies
everything except `server`, `storage`, `writer`, `network` and `commands.yml`, which need a restart (the
reply tells you).

| Section | Highlights |
|---|---|
| `server` | `name` (unique per database), `world-scope` (shared by servers that share world files) |
| `storage` | `type` (`SQLITE`, `MYSQL`, `MARIADB`, `POSTGRESQL`), connection, `parameters`, `table-prefix`, `pool-size` |
| `writer` | `flush-interval-millis` (500), `batch-size`, `queue-capacity`, `shutdown-timeout-seconds` |
| `logging` | One switch per category, `crop-growth-stages`, `liquid-tracking`, `attached-blocks`, `disabled-worlds`, `redacted-commands` |
| `lookup` | `page-size`, `near-radius`, `max-radius`, session and timeout limits |
| `rollback` | `default-radius`, `max-radius`, `blocks-per-tick`, `concurrent-chunks`, `max-events`, preview expiry |
| `purge` | `retention-days` (0 keeps everything), `player-minimum-days` |
| `network` | `bus` (`NONE` or `REDIS`), `redis-uri`, `poll-interval-seconds`, `shards` |
| `archive` | `enabled`, `segment-events` (262144), `settle-seconds` (60), `after-hours` (24), `interval-minutes` (1) |
| `error-reports` | `webhook-url` (a Discord webhook, empty for none), `webhook-errors`, `webhook-crashes`, `join-notice` |
| `experimental` | `dialog-lookup`: lookups as a dialog (off by default) |

Every player-facing message is a MiniMessage template in `messages.yml`.

### ➥ Database credentials

Credentials come from environment variables by default: `storage.username` and `storage.password` are
`${HAWKEYE_DB_USERNAME}` and `${HAWKEYE_DB_PASSWORD}`, so MySQL, MariaDB or PostgreSQL only need those two
variables set (SQLite needs neither).

- `${NAME}` works anywhere in `username`, `password`, `parameters` (in `storage` and each shard) and
  `network.redis-uri`, e.g. `redis://:${HAWKEYE_REDIS_PASSWORD}@localhost:6379`.
- A value without `${...}` is used as written, so a plain password still works.
- A variable that isn't set fails the load with its name in the error.
- Credentials are never logged or shown; driver errors that would quote them show `<redacted>`.

### ➥ Command aliases

`commands.yml` holds the extra names for `/hawkeye` and its subcommands:

```yaml
root-aliases: [he]       # add co to keep typing /co i, /co l u:Alex, /co rb ...
subcommand-aliases:
  inspect: [i]
  lookup: [l]
  rollback: [rb]
  restore: [rs]
```

An alias needs the same permission as its command, takes a restart, and may use up to 32 of `a-z`, `0-9`,
`_` and `-`. A `/co` alias is only reliable once CoreProtect itself is removed.

## ► Error and crash files

Hawkeye shows each warning or error **once** in the console, as one line without the stack trace that
names the file holding the details:

```
[14:03:05 ERROR]: [gg.moonrise.hawkeye.core.queue.event.EventWriter] The writer loop failed (attempt 1), retrying: SQLException: database is locked (details in plugins/Hawkeye/errors/Error-2026-11-25-14-03.txt)
```

The stack trace and every repeat go only to that file, so a database that is down or a listener that fails
on every event no longer floods the console or `logs/latest.log`.

| File | Holds |
|---|---|
| `plugins/Hawkeye/errors/Error-<year>-<month>-<day>-<hour>-<minute>.txt` | Warnings and errors while Hawkeye runs |
| `plugins/Hawkeye/crash/Crash-<year>-<month>-<day>-<hour>-<minute>.txt` | Hawkeye could not load or enable, or one of its threads stopped |

- Every file starts with the Minecraft version, the server type (Paper, Folia, Purpur, Leaf, ...), Java and
  the plugin list: attach it when you report a problem.
- Problems first seen in the same minute share a file. Repeats go into the file of the first one: one line
  each for the first 100, then one line a minute that counts them.
- "Once" means once per server start. A Hawkeye that cannot start writes a crash file, prints one line and
  disables itself.

### ➥ Review

Players with `hawkeye.errors` (operators by default) see on join how many files wait, for example
`2 crash report(s) and 1 error report(s) wait to be reviewed: /hawkeye errors`. `/he errors` lists them with
the first problem of each, and `/he errors reviewed` moves them to `errors/reviewed/` and `crash/reviewed/`.
Repeats of a reviewed problem go to its reviewed file; only a new problem waits again. Set
`error-reports.join-notice: false` to turn the notice off.

### ➥ Discord webhook

```yaml
error-reports:
  webhook-url: '${HAWKEYE_WEBHOOK_URL}'   # or the URL itself; empty for none
  webhook-errors: true
  webhook-crashes: true
```

The first time of every problem (never its repeats) is posted to the channel with its file attached. The URL
holds the webhook's token: keep it in an environment variable, Hawkeye never logs it. Mentions are off, so
text from the game cannot ping anyone. A failed start is posted too, and `/he reload` applies a change at
once.

## ► Running several servers

1. **One shared database.** Point every server's `storage` at the same MySQL, MariaDB or PostgreSQL
   database with the same `table-prefix`, and give each a unique `server.name`. Each server sees the
   others' events within one flush. `/he lookup s:#network u:Alex` searches everywhere;
   `/he rollback s:#network u:Alex t:1h r:#global` rolls Alex back everywhere.
2. **Shards (optional).** Split a large network over several databases: each server writes to its own
   `storage` and lists the others under `network.shards`. Network lookups read every shard in parallel.
3. **Redis (optional).** With `network.bus: REDIS`, network rollbacks start at once. Without it, servers
   poll the database every `network.poll-interval-seconds`.
4. **Shared worlds.** Servers that share world files (MultiPaper) set the same `server.world-scope`.

Purging and archiving run once per database, so every server can use the same configuration.

## ► Importing from CoreProtect

```
/he import coreprotect [source]
```

`[source]` is CoreProtect's folder, its `config.yml`, or a `.db`/`.duckdb` file (default
`plugins/CoreProtect`). The import runs in the background while players stay online and reports progress
every 30 seconds; `/he import status` shows it any time.

- **Any CoreProtect database:** SQLite, MySQL/MariaDB, DuckDB and ClickHouse. The connection comes from
  CoreProtect's `config.yml`, so you never type a password into a command.
- **Read-only.** Your CoreProtect database is never changed, and nothing in Hawkeye is deleted.
- **Cancel and resume.** `/he import cancel` stops after the current batch; run the command again to
  continue, also after a crash.
- **Catch up later.** Run it again after removing CoreProtect and only the new rows come over. No row is
  ever imported twice.
- **Import right after installing Hawkeye.** If both plugins logged the same period, it is stored twice.
- **DuckDB needs CoreProtect disabled.** The other database types can be read while CoreProtect runs.
- **Same Minecraft version.** Import on a server running the version CoreProtect wrote the history on, so
  item data reads back exactly.

<details>
<summary>What comes over and what doesn't</summary>

**Carried over:** block breaks, places and interactions with full block states, container and item
transactions (with names, enchantments and other components), sign text, kills, entity spawns, paintings,
item frames, armour stands, end crystals, chat, commands, sessions, shearing, leashing, and which rows
CoreProtect had already rolled back.

**Not carried over:** killed entities' data (an imported kill can't be respawned), block-entity extras
(command-block commands, banner patterns, shulker contents, skull owners), sign colours, glow and wax, the
username log, rows from before 2025-01-01, and actions Hawkeye has no equivalent for. The final reply
counts every skipped row by reason.

**Offline servers:** the DuckDB and ClickHouse drivers are downloaded on first use into
`plugins/Hawkeye/drivers/`. Without internet access, put them there yourself:
`duckdb_jdbc-1.4.5.0.jar` (`org.duckdb:duckdb_jdbc:1.4.5.0`) and `clickhouse-jdbc-0.10.0-all.jar`
(`com.clickhouse:clickhouse-jdbc:0.10.0`, classifier `all`).

</details>

## ► Server versions

One jar runs on every supported version. Everything is recorded in full on 26.3; older servers record the
same actions with these differences:

| What | Full from | On older servers |
|---|---|---|
| Mobs released from a bucket by a dispenser | 1.21.4 | Recorded under `#spawn_egg` instead of `#dispenser` |
| Lookup dialog (`experimental.dialog-lookup`) | 1.21.7 | Lookups stay in chat |
| Items on shelves | 1.21.9 | Shelves don't exist |
| Crafted items | 1.21.11 | Counted from the click; a shift-click is an estimate |
| Boats and minecarts destroyed without an attacker | 26.1.2 | Blamed on `#unknown` instead of the cause (`#lava`) |
| Golems and withers built by a player | 26.2 | The used-up pattern blocks aren't recorded as broken |
| Cushions | 26.3 | Cushions don't exist |

Item and entity data are upgraded when a newer server reads them, but an older server can't read data from
a newer one. On a mixed-version network, roll back items and entities on a server at least as new as the
one that recorded them.

## ► API for other plugins

```java
HawkeyeApi hawkeye = Bukkit.getServicesManager().load(HawkeyeApi.class);
hawkeye.logPlacement(Actor.player(player.getUniqueId(), player.getName()), replacedState, placedData);
if (hawkeye.parse("u:Alex t:2h r:10", player.getLocation()) instanceof HawkeyeApi.QueryParse.Parsed parsed) {
    hawkeye.lookup(parsed.query(), 0, 20).thenAccept(events -> { /* newest first */ });
}
```

Record placements, removals, container moves and messages for a player or your own source
(`Actor.source("myplugin")`), and run lookups and counts without blocking. `HawkeyeApi.VERSION` changes on
incompatible changes.

## ► Contributing

```bash
./gradlew build                          # compile, test and check the plugin jar
./gradlew test --tests '<TestClass>'     # one test class
```

The jar lands in `build/libs/`. Before changing anything, read:

- [AGENTS.md](AGENTS.md): the rules every change follows.
- [docs/knowledge/README.md](docs/knowledge/README.md): verified project facts, testing against real
  databases, smoke tests, benchmarks and releases.
- [docs/architecture.md](docs/architecture.md): storage format, threading and the multi-server design.
- [CHANGELOG.md](CHANGELOG.md): changes per release.

Found a bug or have an idea? [Open an issue](https://github.com/ericlmao/hawkeye/issues). Hawkeye is free
software under the [GNU General Public License v3.0](LICENSE).
