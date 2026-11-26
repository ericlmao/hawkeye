![Hawkeye](https://raw.githubusercontent.com/ericlmao/hawkeye/main/assets/banner.png)

[![Modrinth](https://img.shields.io/modrinth/v/hawkeye?logo=modrinth&label=Modrinth&color=00AF5C)](https://modrinth.com/plugin/hawkeye)
[![Modrinth downloads](https://img.shields.io/modrinth/dt/hawkeye?logo=modrinth&label=Downloads&color=00AF5C)](https://modrinth.com/plugin/hawkeye)
[![License: GPL-3.0](https://img.shields.io/github/license/ericlmao/hawkeye?logo=github)](https://github.com/ericlmao/hawkeye/blob/main/LICENSE)
[![Jenkins build](https://img.shields.io/jenkins/build?jobUrl=https%3A%2F%2Fci.moonrise.gg%2Fjob%2Fhawkeye%2Fjob%2Fmain%2F&label=Jenkins&logo=jenkins&logoColor=white)](https://ci.moonrise.gg/job/hawkeye/job/main/)

**Hawkeye** records what happens on your server (blocks, containers, entities, chat and commands) and
lets your staff look it up, inspect it and roll it back, on one server or across a whole network.

It is a ground-up alternative to CoreProtect. Your staff keep the lookup parameters they already know,
your CoreProtect history comes over with one command, and months of logs take megabytes instead of
gigabytes.

| Quick Links | |
| --- | --- |
| Source code | [github.com/ericlmao/hawkeye](https://github.com/ericlmao/hawkeye) |
| Full documentation | [README](https://github.com/ericlmao/hawkeye#readme) |
| Bug reports and ideas | [GitHub issues](https://github.com/ericlmao/hawkeye/issues) |
| Development builds | [ci.moonrise.gg](https://ci.moonrise.gg/job/hawkeye/job/main/lastSuccessfulBuild/) |
| Changelog | [CHANGELOG.md](https://github.com/ericlmao/hawkeye/blob/main/CHANGELOG.md) |

## ► At a Glance

- Lookups, an inspector, and rollbacks with a preview, restores and undo
- CoreProtect's parameters (`u: t: r: a: i: e:`) and an optional `/co` alias, so nobody has to relearn anything
- Imports your CoreProtect database (SQLite, MySQL/MariaDB, DuckDB or ClickHouse) while the server keeps running
- Old history is packed into compressed column segments: about 1.2 bytes per event on real server history
- Any number of servers on one database, plus shards, network-wide lookups and network-wide rollbacks
- Every lookup sees events up to the moment you run it, and queued events survive a database outage
- One jar for Paper and Folia, 1.21.1 to 26.3
- Works out of the box on a single server (SQLite); use MySQL, MariaDB or PostgreSQL when you need them
- Every message is a MiniMessage template you can change
- Free and open source under GPL-3.0

## ► Switching from CoreProtect

Install Hawkeye and run `/he import coreprotect`. Hawkeye reads CoreProtect's own `config.yml` to find the
database, so you never type a host or password into a command. The import runs in the background while
players stay online, reports its progress every 30 seconds, and can be cancelled and resumed. Your
CoreProtect database is opened read-only and is never changed.

Here is one CoreProtect SQLite database of 13.5 million rows (35 minutes of mass edits), before and after:

| | CoreProtect | Hawkeye after the import |
| --- | --- | --- |
| Database file | 1.18 GB | **3.9 MB** (303 times smaller) |
| Per block event | 87 bytes | **0.29 bytes** |
| Rows imported | | **All 13,509,310**, none skipped |
| Import time | | **17.5 seconds** (about 760,000 rows a second) |

How much you save depends on your history: mass edits pack best. On a real server's 1.4 billion events
over 59 days, Hawkeye stores 1.085 bytes per event. Lookups, rollbacks and restores read the
compressed archive directly, with nothing to unpack first.

Add `co` to `root-aliases` in `commands.yml` and your staff keep typing `/co i`, `/co l u:Alex` and
`/co rb ...` after CoreProtect is gone.

<details>
<summary>What the import carries over, and what it can't</summary>

**Carried over:** block breaks, places and interactions with their full block states, container and
item transactions (with names, enchantments and other components), sign text, kills, entity spawns,
paintings, item frames, armour stands, end crystals, chat, commands, sessions, shearing, leashing, and
which rows CoreProtect had already rolled back.

**Not carried over:** a killed entity's data (so an imported kill can't be respawned by a rollback),
block-entity extras such as command-block commands and banner patterns, sign colours, glow and wax,
CoreProtect's username log, rows from before 2025-01-01, and actions Hawkeye has no equivalent for. The
final reply counts every skipped row by reason.

**Good to know:**

- Run it again after removing CoreProtect and only the new rows come over. No row is ever imported twice.
- Import right after installing Hawkeye. If both plugins logged the same period, that period is stored twice.
- A DuckDB database can only be read with CoreProtect disabled. SQLite, MySQL/MariaDB and ClickHouse can be
  read while CoreProtect runs.
- Import on a server running the Minecraft version CoreProtect wrote the history on, so item data reads back
  exactly.

</details>

## ► Features

### ➥ Lookups and the inspector

Turn on the inspector with `/he i`. Left-click a block to see its history, right-click a container to see
who took and added what, and place a block to see what happened at that spot. Nothing is broken or placed
while you inspect.

Search the whole history with `/he lookup u:Alex t:2h r:10 a:-block`. Click a location in the results to
teleport there. Hover an item to see its name and enchantments, and click a written book to read it. New
events never shift the pages you're reading, and `#count` gives the exact number of matches.

### ➥ Rollbacks you can trust

- **Preview first.** `#preview` shows a rollback to you alone; `/he apply` or `/he cancel` it.
- **Undo.** `/he undo` reverts your last rollback or restore.
- **Safe defaults.** Rollbacks need a time (`t:`), use a default radius around you, and are capped by a
  maximum radius and event count. Blocks are changed a few at a time per tick, so the server keeps running
  smoothly.
- **Blame follows the cause.** TNT lit by a player, arrows they shot and lava they poured are recorded under
  that player, so `/he rollback u:Griefer t:1h` also undoes their TNT damage.
- **More than blocks.** Rollbacks restore container items (also in minecarts, chest boats and pack
  animals), item frame and armour stand items, killed mobs and destroyed entities, and remove entities a
  player placed.

### ➥ Built for networks

- **One shared database.** Point every server at the same MySQL, MariaDB or PostgreSQL database and give
  each a unique name. Event ids never collide, and each server sees the others' events within one flush
  (half a second by default).
- **Network lookups and rollbacks.** `/he lookup s:#network u:Alex` searches every server, and
  `/he rollback s:#network u:Alex t:1h r:#global` rolls Alex back everywhere. Network rollbacks run as
  durable jobs on each target server.
- **Shards.** Large networks can split servers over several databases. Network lookups read every shard
  in parallel and merge the results.
- **Redis (optional).** Network jobs start at once with Redis. Without it, servers poll the database for
  their jobs. Either way, the database is the source of truth.
- **MultiPaper and shared worlds.** Servers that share world files share their history too.
- **Configure once.** Maintenance such as purging and archiving runs once per database, so every server
  can use the same configuration.

### ➥ Storage that stays small and fast

Capturing an event never touches the database: one background thread writes events in batches. Recent
events stay in a regular table. Once they settle, they are packed into immutable segments of 262,144
events, stored column by column and compressed with zstd. A lookup about one place reads only a segment's
header and a small part of it, so a block's history comes back quickly even after months of logging.

If the database goes down, events stay queued and are retried. On shutdown, anything that couldn't be
written yet is saved to disk and written on the next start.

### ➥ A quiet console

Hawkeye shows each of its warnings and errors once, as one line that names a text file. The stack trace
and every repeat go into that file in `plugins/Hawkeye/errors`, so a database that is down no longer
floods your console. If Hawkeye can't start, you get a crash file in `plugins/Hawkeye/crash` and one
console line. Each file starts with your server version, server type, Java version and plugin list, so
you can attach it to a bug report as it is.

- Staff with `hawkeye.errors` are told on join when files wait. `/he errors` lists them, and
  `/he errors reviewed` moves them out of the way.
- Set `error-reports.webhook-url` to a Discord webhook to get the first time of every problem in a
  channel, with its file attached. Repeats are never sent, and mentions are turned off.

<details>
<summary>What is recorded</summary>

Every category has its own switch in `config.yml`, and everything except hoppers is on by default.

| Category | Details |
| --- | --- |
| Blocks | Breaks (with container contents and sign text), places, multi-block places, buckets and waterlogging |
| Attached blocks | Torches, signs, rails, redstone, flowers and anything else that falls when its support goes, blamed on whoever removed the support |
| Explosions | Every destroyed block, blamed on the player who lit, placed, clicked or hit the cause |
| Fire, fluids, pistons | Burning, lighting, lava and water flow (blamed on whoever poured it), stone and obsidian forming, sponges, pistons |
| Natural changes | Leaf decay, melting, snow and ice, spread of grass, vines and sculk, tree and mushroom growth, portals, crop growth |
| Signs | Edits with the text before and after, per side |
| Interactions | Doors, trapdoors, gates, levers, buttons, repeaters, comparators, note blocks and more |
| Containers | Items in and out of chests, barrels, shulkers, minecarts, chest boats, pack animals, item frames, armour stands, jukeboxes, chiseled bookshelves, decorated pots, shelves and lecterns |
| Ender chests and plugin inventories | Ender chests, another player's inventory (an `/invsee` view) and plugin storage such as player vaults and backpacks |
| Dispensers and hoppers | Blocks and liquids a dispenser places or picks up; hopper transfers (off by default) |
| Entities | Spawned, placed, destroyed and killed entities, mobs caught or released from buckets, built golems and withers |
| Entity changes | Endermen, falling blocks, withers, ravagers and silverfish changing blocks, trampled farmland, sheep eating grass |
| Entity interactions | Shearing, naming, leashing, taming, dyeing |
| Items | Drops, death drops, pickups, crafting, thrown and shot items, broken tools, villager trades |
| Chat and commands | Chat, player, sign, console and RCON commands (with a list of commands to store without their arguments), written books |
| Sessions | Joins, quits with the kick reason, game mode changes |
| Inventory snapshots | A copy of a player's whole inventory when they join, leave, teleport and die |

</details>

<details>
<summary>Commands</summary>

The root command is `/hawkeye`, with the alias `/he`. You can change every alias in `commands.yml`.

| Command | Permission | What it does |
| --- | --- | --- |
| `/he inspect` (`i`) | `hawkeye.inspect` | Turns the inspector on or off |
| `/he lookup <parameters>` (`l`) | `hawkeye.lookup` | Searches the history, newest first |
| `/he near [radius]` | `hawkeye.lookup` | Shows what happened around you |
| `/he page <page>` | `hawkeye.lookup` | Shows another page of the last lookup |
| `/he tp <world> <x> <y> <z>` | `hawkeye.teleport` | Teleports to a result |
| `/he rollback <parameters>` (`rb`) | `hawkeye.rollback` | Undoes changes |
| `/he restore <parameters>` (`rs`) | `hawkeye.rollback` | Redoes rolled-back changes |
| `/he apply`, `/he cancel` | `hawkeye.rollback` | Applies or cancels your preview |
| `/he undo` | `hawkeye.rollback` | Reverts your last rollback or restore |
| `/he purge t:<age> [r:#world]` | `hawkeye.purge` | Deletes data older than the age |
| `/he import coreprotect [source]` | `hawkeye.import` | Imports a CoreProtect database |
| `/he import status`, `/he import cancel` | `hawkeye.import` | Shows or stops the running import |
| `/he status` | `hawkeye.admin` | Shows queue, writer and database health |
| `/he network` | `hawkeye.network` | Lists every server on the network and its heartbeat |
| `/he reload` | `hawkeye.admin` | Reloads the configuration and messages |
| `/he errors` | `hawkeye.errors` | Lists the error and crash files waiting for review |
| `/he errors reviewed` | `hawkeye.errors` | Marks them reviewed |

All permissions default to operators, and `hawkeye.*` grants them all. Players with `hawkeye.errors` are
also told on join when error or crash files wait for review.

</details>

<details>
<summary>Parameters</summary>

Lookups, rollbacks, restores and purges take the same parameters as CoreProtect.

| Parameter | Meaning | Examples |
| --- | --- | --- |
| `u:` | Players, or sources with `#` | `u:Alex,Steve`, `u:#tnt` |
| `t:` | How far back, or between two ages | `t:2h`, `t:3d12h`, `t:1h-2d` |
| `r:` | Radius around you, a world, or everywhere | `r:10`, `r:10x5`, `r:#world_nether`, `r:#global` |
| `a:` | Actions | `block`, `-block`, `+container`, `kill`, `chat`, `command`, `session`, `snapshot` |
| `i:` | Only these blocks, items or entities | `i:diamond_ore,chest` |
| `e:` | Except these types or players | `e:stone,Steve` |
| `s:` | Other servers | `s:creative`, `s:#network` |
| `#preview` | Show a rollback or restore to you only | |
| `#count` | Count matches instead of listing them | |

CoreProtect's action names such as `broke`, `placed` and `login` work too. See the
[full list](https://github.com/ericlmao/hawkeye#parameters).

</details>

<details>
<summary>Configuration</summary>

`config.yml`, `messages.yml` and `commands.yml` are written on first start with every default and a
comment for each option. Options added in a newer version are filled in for you, and a file with a
mistake keeps the previous configuration instead of breaking the plugin. `/he reload` applies logging,
lookup, rollback, purge, archive and error report settings and every message.

Database credentials come from environment variables by default (`${HAWKEYE_DB_USERNAME}` and
`${HAWKEYE_DB_PASSWORD}`), and the Redis URI and connection parameters can use `${NAME}` too, so no
password has to sit in a config file. Hawkeye never logs them.

```yaml
server:
  name: survival # unique per database
storage:
  type: MYSQL # SQLITE (the default), MYSQL, MARIADB or POSTGRESQL
  host: localhost
  database: hawkeye
  username: ${HAWKEYE_DB_USERNAME}
  password: ${HAWKEYE_DB_PASSWORD}
purge:
  retention-days: 90 # delete events older than 90 days; 0 (the default) keeps everything
network:
  bus: REDIS # NONE (the default) polls the database instead
  redis-uri: redis://:${HAWKEYE_REDIS_PASSWORD}@localhost:6379
```

See [every option](https://github.com/ericlmao/hawkeye#configuration).

</details>

<details>
<summary>API for developers</summary>

```java
HawkeyeApi hawkeye = Bukkit.getServicesManager().load(HawkeyeApi.class);
hawkeye.logPlacement(Actor.player(player.getUniqueId(), player.getName()), replacedState, placedData);
if (hawkeye.parse("u:Alex t:2h r:10", player.getLocation()) instanceof HawkeyeApi.QueryParse.Parsed parsed) {
    hawkeye.lookup(parsed.query(), 0, 20).thenAccept(events -> { /* newest first */ });
}
```

Record placements, removals, container moves and messages on behalf of a player or your own plugin,
and run lookups and counts without blocking the caller.

</details>

## ► Requirements

- Paper or Folia 1.21.1 to 1.21.11, 26.1.2 or 26.3, all from the same jar, on the Java version your server
  needs (21 for 1.21.x, 25 for 26.x)
- Nothing else for a single server
- MySQL 8+, MariaDB 10.6+ or PostgreSQL 13+ to share a database between servers, and Redis (optional) for
  instant network jobs

On first start, the server downloads Hawkeye's libraries through Paper's library loader, so the jar stays
small. A few actions are recorded differently on older Minecraft versions; see
[Server versions](https://github.com/ericlmao/hawkeye#server-versions).

## ► Support

Found a bug or have an idea? [Open an issue on GitHub](https://github.com/ericlmao/hawkeye/issues).

Hawkeye is free software under the
[GNU General Public License v3.0](https://github.com/ericlmao/hawkeye/blob/main/LICENSE). It is made by
Moonrise Studios.
