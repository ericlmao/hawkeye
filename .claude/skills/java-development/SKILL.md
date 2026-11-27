---
name: java-development
description: >
  Java engineering baseline: imports, dependency injection, code structure, Lombok,
  logging, unit testing, visibility, commands for Paper plugins, null safety, records,
  threading, explicit results, and layered package structure. Use this automatically
  for every task that reads, writes, edits, reviews, refactors, or tests Java source in
  any repository — services, libraries, Paper/Bukkit/Velocity/BungeeCord plugins, bots,
  and test sources alike.
  Load it before writing the first line of Java, not after.
user-invocable: false
---

## Scope and activation

This skill is model-activated, not a slash command. Apply it whenever a task touches
`*.java`, including small edits, generated code, and test sources.

**Project rules win.** If the repository's `CLAUDE.md`, `AGENTS.md`, `docs/knowledge/`,
or a project skill sets a different Java convention, follow the project and treat this
file as the fallback for everything the project leaves unsaid. Some sections below apply
only when the project uses that technology (Spring, Lombok, Paper, Cloud); skip a section
whose stack the project does not use rather than introducing that stack.

When delegating Java work to a subagent, restate the rules that govern the delegated
change in the subagent prompt, or tell the subagent to read
`~/.claude/skills/java-development/SKILL.md` first.

# Java Development

All new and modified Java code must follow these rules. Match existing code only when it
already satisfies this baseline; do not preserve an outdated pattern merely for local
consistency.

# No Inline Imports
Do not use fully qualified class names inline. Always import classes at the top of the
file. The only exception is a genuine name clash between two imported types.

Bad:
```java
public class Example {
    public void doSomething() {
        java.util.List<String> list = new java.util.ArrayList<>();
    }
}
```

Good:
```java
import java.util.ArrayList;
import java.util.List;

public class Example {
    public void doSomething() {
        List<String> list = new ArrayList<>();
    }
}
```

# Dependency Injection
Use constructor injection with `final` fields, preferably generated with Lombok's
`@RequiredArgsConstructor`. In Spring projects, mark beans with a stereotype annotation
(`@Component`, `@Service`, or the project's own meta-annotation if it defines one).
Never use `@Autowired` field injection, never manually construct or register objects the
container already discovers and wires (beans, listeners, commands), and never use static
mutable singletons.

Bad:
```java
@Component
public class Example {
    @Autowired
    private SomeService someService;
}
```

Good:
```java
@Component
@RequiredArgsConstructor
public class Example {
    private final SomeService someService;
}
```

Do not create multiple constructors for container-managed beans, and do not `new` up a
dependency inside a constructor when it can be injected.

Bad:
```java
@Component
public class Example {
    private final SomeService someService;
    private final AnotherService anotherService;

    public Example(SomeService someService) {
        this.someService = someService;
        this.anotherService = new AnotherService();
    }

    public Example(SomeService someService, AnotherService anotherService) {
        this.someService = someService;
        this.anotherService = anotherService;
    }
}
```

Good:
```java
@Component
@RequiredArgsConstructor
public class Example {
    private final SomeService someService;
    private final AnotherService anotherService;
}
```

# Boilerplate Avoidance
Avoid boilerplate code. Use the following practices to reduce boilerplate and improve
code readability.

Before writing a utility at all, check whether it already exists: search the codebase,
its shared modules, and the libraries it already depends on (including whether a newer
release of one of those libraries provides it). If the project has a shared library or
common module, a helper that more than one module needs belongs there, not copied between
modules, and a bug in a shared library is fixed in that library rather than worked around
in a consumer.

## Utility Classes
Use utility classes for common operations instead of duplicating code. Utility classes
live in a `util` package, are `final`, and have a private constructor to prevent
instantiation.

Bad:
```java
public class Example {
    public void doSomething() {
        // Some common operation
    }
}
public class AnotherExample {
    public void doSomethingElse() {
        // Same common operation duplicated
    }
}
```

Good:
```java
public class Example {
    public void doSomething() {
        CommonUtils.commonOperation();
    }
}
public class AnotherExample {
    public void doSomethingElse() {
        CommonUtils.commonOperation();
    }
}
public final class CommonUtils {
    private CommonUtils() {
    }

    public static void commonOperation() {
        // Implementation of the common operation
    }
}
```

## Inheritance and Composition
Favor composition over inheritance. Use interfaces to define common behavior, and
delegate to composed objects rather than extending classes unnecessarily.

Bad:
```java
public class Example extends BaseClass {
    // Some specific behavior
}
public class AnotherExample extends BaseClass {
    // Some other specific behavior
}
```

Good:
```java
public interface CommonBehavior {
    void performAction();
}

@RequiredArgsConstructor
public class Example implements CommonBehavior {
    private final SomeService someService;

    @Override
    public void performAction() {
        someService.doSomething();
    }
}

@RequiredArgsConstructor
public class AnotherExample implements CommonBehavior {
    private final AnotherService anotherService;

    @Override
    public void performAction() {
        anotherService.doSomethingElse();
    }
}
```

## Lombok
When the project uses Lombok, use its annotations to remove hand-written getters,
setters, constructors, and loggers. Do not add Lombok to a project that does not already
use it without asking.

Bad:
```java
public class Example {
    private String name;
    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }
}
```

Good:
```java
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Example {
    private String name;
}
```

## Logging
Use the project's logging framework (SLF4J via Lombok's `@Slf4j` by default) and never
`System.out.println` or `printStackTrace()`. Use appropriate levels (DEBUG, INFO, WARN,
ERROR), parameterized messages rather than string concatenation, and pass the exception
as the last argument so the stack trace is kept.

Bad:
```java
public class Example {
    public void doSomething() {
        System.out.println("Doing something for " + user);
    }
}
```

Good:
```java
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Example {
    public void doSomething() {
        log.info("Doing something for {}", user);
    }
}
```

# Unit Testing
All new and modified code must be accompanied by unit tests that cover the expected
behavior, using the project's test framework (JUnit 5 by default). Tests must be
isolated, repeatable, and make meaningful assertions. Run the affected tests before
calling the work done.

For Paper/Bukkit plugins, use MockBukkit rather than a real server; a real server makes
tests flaky and slow. Use the MockBukkit artifact and package the project already depends
on (`org.mockbukkit.mockbukkit` in v4+, `be.seeseemelk.mockbukkit` in older versions).

Bad:
```java
public class ExampleTest {
    @Test
    public void testDoSomething() {
        Example example = new Example();
        example.doSomething();
        // No assertions, test does not verify behavior
    }
}
```

Good:
```java
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ExampleTest {
    @Test
    public void testDoSomething() {
        Example example = new Example();
        String result = example.doSomething();
        assertEquals("Expected Result", result);
    }
}
```

Good MockBukkit example:
```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExamplePluginTest {
    private ServerMock server;
    private ExamplePlugin plugin;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(ExamplePlugin.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void testPluginEnable() {
        assertTrue(plugin.isEnabled());
    }
}
```

# Method and Class Visibility
Explicitly declare every production class and method `public`, `protected`, or `private`
according to its API boundary. Do not leave a class or method at default
(package-private) visibility. This makes the intended access level clear and prevents
accidental misuse. Interface methods are implicitly public and need no modifier.

Bad:
```java
class Example {
    void doSomething() {
        // Some implementation
    }
}
public class AnotherExample {
    void doSomethingElse() {
        // Some other implementation
    }
}
```

Good:
```java
public class Example {
    public void doSomething() {
        // Some implementation
    }
}
public class AnotherExample {
    private void doSomethingElse() {
        // Some other implementation
    }
}
public interface ExampleInterface {
    void performAction(); // implicitly public
}
```

# Writing Commands in Paper
For Paper plugins, use the Cloud command framework (v2) with its annotations module.
Avoid the legacy Bukkit `CommandExecutor` system. Use annotations to define commands,
subcommands, arguments, and permissions. Register command classes through the project's
existing mechanism: if it has a command marker interface or a registry that discovers
command beans, implement that; otherwise parse the instance with Cloud's
`AnnotationParser` at startup.

Bad:
```java
public class ExamplePlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        this.getCommand("example").setExecutor(new ExampleCommand());
    }
}
public class ExampleCommand implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Command implementation
        return true;
    }
}
```

Good:
```java
public class MyCommand {
    @Command("mycommand")
    @CommandDescription("My example command")
    @Permission("myplugin.mycommand")
    public void exampleMain(CommandSourceStack source) {
        // Command implementation
    }

    @Command("mycommand subcommand")
    @CommandDescription("My example subcommand")
    @Permission("myplugin.mycommand.subcommand")
    public void exampleSub(CommandSourceStack source) {
        // Command implementation
    }

    @Command("mycommand subcommandarg <arg>")
    @CommandDescription("My example command with an argument")
    @Permission("myplugin.mycommand.arg")
    public void exampleArg(
            CommandSourceStack source,
            @Argument("arg") String name
    ) {
        // Command implementation
    }
}

// Registration, when the project has no registry of its own:
annotationParser.parse(new MyCommand());
```

For tab completion, use an `@Suggestions` method that returns the suggestions for the
argument. Match with a case-insensitive `contains` rather than `startsWith`, so partial
input anywhere in the name matches.

Bad:
```java
@Suggestions("players")
public List<String> playerSuggestions(CommandContext<CommandSourceStack> context, String input) {
    return Bukkit.getOnlinePlayers().stream()
            .map(Player::getName)
            .filter(name -> name.startsWith(input))
            .toList();
}
```

Good:
```java
@Command("example <player>")
public void exampleCommand(
        CommandSourceStack source,
        @Argument(value = "player", suggestions = "players") String name
) {
    // Command implementation
}

@Suggestions("players")
public List<String> playerSuggestions(CommandContext<CommandSourceStack> context, String input) {
    String needle = input.toLowerCase(Locale.ROOT);
    return Bukkit.getOnlinePlayers().stream()
            .map(Player::getName)
            .filter(name -> name.toLowerCase(Locale.ROOT).contains(needle))
            .toList();
}
```

# Null Safety
Return `Optional` for values that may be absent instead of returning `null`. Use
`@NotNull` only when a value can never be null.

Annotate method parameters with `@NotNull` or `@Nullable` (the annotation library the
project already uses, such as JetBrains annotations or JSpecify) so null contracts are
explicit and checkable by static analysis. Do not use `Optional` for fields or
parameters.

Bad:
```java
public class Example {
    public String getValue() {
        return null; // Returning null is not safe
    }
    public void setValue(String value) {
        // No null contract, potential NullPointerException
    }
}
```

Good:
```java
import java.util.Optional;

import org.jetbrains.annotations.NotNull;

public class Example {
    public Optional<String> getValue() {
        return Optional.empty(); // Absence is explicit in the type
    }
    public void setValue(@NotNull String value) {
        // Null contract is stated by the annotation
    }
}
```

# Record Usage
Use records for immutable data carriers instead of hand-written classes with final
fields, constructors, and accessors.

Bad:
```java
public class Example {
    private final String name;
    public Example(String name) {
        this.name = name;
    }
    public String getName() {
        return name;
    }
}
```

Good:
```java
public record Example(@NotNull String name) {
}
```

# Thread Safety
Code must be thread-safe wherever it can run on more than one thread: servers, request
handlers, schedulers, and event-driven runtimes such as a game server. Shared mutable
state needs a clear owner thread, a concurrent collection, or explicit synchronization.

## Scheduling (Paper and Folia)
On Paper, schedule work tied to an entity or a location on the region-aware schedulers,
not the legacy `BukkitScheduler`, so the code is also correct on Folia. If the project
wraps these in its own scheduler utility, use the wrapper.

Bad:
```java
public class Example {
    public void scheduleTask() {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            player.sendMessage("Hello world!");
        }, 20L); // always the main thread; wrong region on Folia
    }
}
```

Good:
```java
public class Example {
    public void scheduleTask() {
        // Global work not tied to an entity or location
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> Bukkit.broadcast(Component.text("Hello world!")));

        // Runs on the thread that owns this entity; the second callback runs if the
        // entity is removed before the task executes.
        player.getScheduler().run(plugin, task -> player.sendMessage("Hello world!"), () -> {
            // Player left; nothing else needs this message.
        });

        // Work tied to a block or location
        Bukkit.getRegionScheduler().run(plugin, location, task -> location.getBlock().setType(Material.STONE));
    }
}
```

## CompletableFuture Usage
Use `CompletableFuture` for asynchronous work instead of creating threads by hand. This
gives control over execution, composition, and error handling.

Always pass an executor you control. Do not run long-running or blocking work on the
default `ForkJoinPool.commonPool()`; it causes thread starvation. Inject the executor (or
own it in a component with a lifecycle) and shut it down when the application or plugin
stops. Handle failures with `exceptionally`/`handle`/`whenComplete` so errors are logged
rather than lost.

Bad:
```java
public class Example {
    public void performAsyncOperation() {
        new Thread(() -> {
            // Some long-running operation
        }).start();

        CompletableFuture.supplyAsync(this::loadData); // runs on the common pool
    }
}
```

Good:
```java
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@RequiredArgsConstructor
public class Example {
    private final Executor ioExecutor; // bounded pool owned and shut down elsewhere

    public CompletableFuture<Data> performAsyncOperation() {
        return CompletableFuture.supplyAsync(this::loadData, ioExecutor)
                .whenComplete((data, error) -> {
                    if (error != null) {
                        log.error("Failed to load data", error);
                    }
                });
    }
}
```

# Result-Driven Development
Define the possible outcomes of an operation first, then build the implementation
around them. Sealed interfaces with record implementations make each result explicit,
type-safe, and exhaustively handled by `switch`. Prefer this over `boolean` returns,
magic values, or exceptions for expected outcomes.

Bad:
```java
public class PurchaseService {
    public boolean buyItem(int balance, int price) {
        return balance >= price;
    }
}

// Caller cannot tell why it failed
if (service.buyItem(100, 150)) {
    log.info("Purchase complete");
} else {
    log.warn("Something went wrong");
}
```

Good:
```java
public sealed interface PurchaseResult {
    record Success(int remainingBalance) implements PurchaseResult {}
    record InsufficientFunds(int missing) implements PurchaseResult {}
}

public class PurchaseService {
    public PurchaseResult buyItem(int balance, int price) {
        if (balance < price) {
            return new PurchaseResult.InsufficientFunds(price - balance);
        }
        return new PurchaseResult.Success(balance - price);
    }
}

// Caller handles every outcome; the compiler flags a missing case
String message = switch (service.buyItem(100, 50)) {
    case PurchaseResult.Success success -> "Purchase complete, " + success.remainingBalance() + " left";
    case PurchaseResult.InsufficientFunds failure -> "You need " + failure.missing() + " more";
};
```

# Maintainable and Readable Code
Code should be easy to read, understand, and maintain. Avoid large methods that perform
several unrelated tasks. Prefer small, single-purpose methods and extract reusable logic
into utility classes where appropriate.

Bad:
```java
public class RewardService {
    public void processAccount(Account account) {
        if (account == null) {
            return;
        }

        String name = account.getName().trim().toLowerCase();
        System.out.println("Processing account: " + name);

        if (account.getBalance() < 0) {
            account.setBalance(0);
        }

        int reward = account.getLevel() * 100;
        if (reward > 1000) {
            reward = 1000;
        }

        account.deposit(reward);
        System.out.println("Gave " + reward + " coins to " + name);
    }
}
```

Good:
```java
@Slf4j
public class RewardService {
    public void processAccount(@NotNull Account account) {
        clearNegativeBalance(account);

        int reward = RewardUtils.calculateReward(account.getLevel());
        account.deposit(reward);

        log.info("Gave {} coins to {}", reward, account.getName());
    }

    private void clearNegativeBalance(Account account) {
        if (account.getBalance() < 0) {
            account.setBalance(0);
        }
    }
}

public final class RewardUtils {
    private RewardUtils() {
    }

    public static int calculateReward(int level) {
        return Math.min(level * 100, 1000);
    }
}
```

# No God Classes
A class approaching 1000 lines should be reviewed and split into smaller, focused
classes. When a facade is useful, keep it thin and have it hand out the focused services
rather than implementing everything itself.

Bad:
```java
public class MyService {
    public void serviceMethodOne() { /* ... */ }
    public void serviceMethodTwo() { /* ... */ }
    public void serviceMethodThree() { /* ... */ }
    // ...hundreds more lines of unrelated responsibilities
}
```

Good:
```java
@RequiredArgsConstructor
public class MyService {
    private final OneService oneService;
    private final TwoService twoService;
    private final ThreeService threeService;

    public OneService one() {
        return this.oneService;
    }

    public TwoService two() {
        return this.twoService;
    }

    public ThreeService three() {
        return this.threeService;
    }
}
```

Each focused service can grow on its own without making any one file hard to navigate.

# Layered Architecture
Organise packages by layer, and let each layer use only the layers below it. Code in a
lower layer never imports code from a higher one. Use plain package names; do not name
packages `presentation`, `application`, `domain` or `infrastructure`.

| Layer (top to bottom) | Packages | Holds |
|---|---|---|
| Loader | `loader` | Bootstrap code such as the Paper plugin loader |
| Player-facing | `command`, `listener`, `ui`, `type`, `indicator`, `integration.<plugin>` | Entry points and output: commands, Bukkit listeners, menus, goal/quest-type handlers, boss bars and chat output, third-party plugin hooks that react to events |
| Service | `core.service` (split into feature subpackages such as `core.service.quest`) | Business logic and use cases |
| Backend | `core.repository`, `core.sql`, `core.time`, `currency` | Repositories and SQL, clocks, adapters for outside systems |
| Bottom | `entity`, `config`, `util` | Data model, config model, pure helpers |

The plugin's main class stays in the root package and may use any layer.

Rules:
- **Services never run SQL.** All `Connection`, `PreparedStatement` and transaction code
  lives in a repository in `core.repository`. The service calls the repository and keeps
  the business rules.
- **Services never touch menus, commands or listeners.** When a service must drive
  something player-facing, put an interface in the service layer and the implementation in
  the player-facing package, then inject it (for example a `QuestProgressIndicator`
  interface in `core.service.indicator`, implemented in `indicator`).
- **Player-facing code never touches the backend directly.** A command that needs a
  reload, a database call or a currency calls a service; it does not use `SqlProvider` or a
  repository.
- **Repositories return entities, not their own nested types.** A result type that a
  service or menu needs belongs in `entity`, not inside the repository class.
- **Event listeners are entry points.** A Bukkit listener, including one for a third-party
  plugin's events, belongs in the player-facing layer even when it is an "integration".
- **Split large packages by concern.** No package should be a flat dump of dozens of
  classes. Split `config` into `config.file`, `config.menu`, `config.message`,
  `config.quest` and so on, and split `core.service` by feature.
- **Tests mirror the package** of the class they test. Shared test helpers live next to
  the code they set up (SQL harnesses in `core.sql`).

Bad:
```java
package gg.example.core.service;

import gg.example.ui.StreakMenuUI;          // service depends on a menu (upward)

@RequiredArgsConstructor
public class StreakService {
    private final SqlProvider sqlProvider;

    public CompletableFuture<StreakState> load(UUID playerId) {
        return sqlProvider.supplyAsync(playerId, () -> {
            try (Connection connection = sqlProvider.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT ...")) {
                // SQL inside a service
            }
        });
    }

    public void open(Player player) {
        new StreakMenuUI(this).open(player);
    }
}
```

Good:
```java
package gg.example.core.repository;

@RequiredArgsConstructor
public class StreakRepository {
    private final SqlProvider sqlProvider;

    public CompletableFuture<Optional<StreakState>> load(UUID playerId) {
        return sqlProvider.supplyAsync(playerId, () -> readState(playerId));
    }
}

package gg.example.core.service.streak;

@RequiredArgsConstructor
public class StreakService {
    private final StreakRepository streakRepository;

    public CompletableFuture<StreakState> load(UUID playerId) {
        return streakRepository.load(playerId)
                .thenApply(state -> state.orElseGet(() -> StreakState.empty(playerId)));
    }
}

package gg.example.ui;

@RequiredArgsConstructor
public class StreakMenuUI {
    private final StreakService streakService;   // menus call down into services
}
```

When the project already has ArchUnit, or a refactor touches package structure, add a
`layeredArchitecture()` test that encodes these rules. Also add a rule that every plugin
class sits in a layer, because the layer rule ignores classes outside all layers. That way
a new top-level package cannot slip past the check.

## Hawkeye's layers

In this repository the layers are these packages under `gg.moonrise.hawkeye`.
`ArchitectureTest` encodes exactly this table and fails on a class outside every layer, so a
new top-level package (or a new `core.*` package) goes into a layer there in the same change.

| Layer | Packages | Holds |
|---|---|---|
| Plugin | the root package | `HawkeyePlugin` only; may use any layer |
| Loader | `loader` | `HawkeyePluginLoader` (runtime libraries) |
| Player-facing | `command` (`command.registry`, `command.query`), `listener.<family>`, `ui.<feature>`, `integration.api` (`type`, `indicator` are reserved) | Cloud commands, Bukkit listeners, chat output, the `HawkeyeApi` implementation other plugins call |
| Service | `core.service.<feature>` (`capture`, `dev`, `diagnostic`, `importer`, `inspect`, `lookup`, `network`, `purge`, `query`, `rollback`, `status`, `thread`) | Use cases: recording, imports, fake data (dev mode), lookups, rollbacks, purges, network jobs, status |
| Backend | `core.repository.<table group>`, `core.sql`, `core.storage.<part>`, `core.queue.<part>`, `core.bus`, `core.scheduler`, `core.time`, `core.diagnostic` | Repositories and SQL, the storage engine, the write queue and spool, the cross-server bus, executors, the clock, the error and crash files |
| Bottom | `entity.<concept>`, `config.<part>` (`env`, `file`, `message`, `provider`, `permission`), `util.<part>` (`codec`, `codec.binary`, `compat` (server API gates, server software), `event`, `log` (log markers), `nbt`, `position`, `redact`, `serialize`, `text`), `api` | Data model, config model, pure helpers, the public API types |

Packages split by concern, never a flat mix (a package with classes and subpackages keeps only
the feature's entry points and its own result types at its root). `core`, `core.service`, `core.storage`,
`core.repository`, `core.repository.coreprotect`, `core.queue`, `config` and `util` hold only
subpackages; `ArchitectureTest` checks that list:

| Package | Subpackages |
|---|---|
| `core.storage` | `shard` (`StorageManager`, `StorageState`, `Shard`, `Dictionaries`), `event` (`EventStore`, `QueryResolver`), `archive` (`Archive`, `Archiver`, `SegmentCache`), `segment` (archive format 2 codec), `index` (`SegmentIndex`, `PlaceIndex`, `ActorIndex`), `index.place` (place index row codecs), `importer` (`ImportStore`) |
| `core.repository` | one per table group: `event`, `dictionary`, `archive` (`segment`, `segment_flag`), `index` (segment index tables), `network` (`server`, `job`, `lease`), `importer` (`import_state`); plus `coreprotect` (reading a CoreProtect database, split below) |
| `core.repository.coreprotect` | `source` (`CoreProtectSources`, `CoreProtectSource`, `SourceLocator`), `schema` (`SourceDialect`, `SourceSchema`), `driver` (`DriverArtifact`, `DriverResolver`, `SourceDrivers`), `reader` (`TableScan`, `RowidScan`, `TimeScan`, `SourceRowMapper`, `SourceNamesReader`, `SpawnUuids`) |
| `core.queue` | `event` (`EventQueue`, `EventWriter`), `spool` (`EventSpool`, `SpoolReplay`), `id` (`IdAssigner`) |
| `core.service.capture` | root: `EventRecorder`, `Actors`, `CauseTracker`, `KillCauses`; `block`, `container`, `text` |
| `core.service.importer` | root: `ImportService` and its outcome, progress, listener; `pipeline` (the run loop, table merge, segment writer), `mapping` (root: `RowMapper`, `RowBase`, `MappedRow`, `SourceNameResolver`; `block`, `item`, `sign`) |
| `core.service.dev` | root: `DevDataService` and its request, world, outcome, progress, listener; `pipeline` (the run loop); `fake` (the fake server model, no server or database) |
| `core.service.lookup` | root: `LookupService`, `LookupMerge` and the shard results, outcome, total, warning; `page` (`LookupPager`, sessions, page views) |
| `core.service.query` | root: `QueryBuilder`, `QueryContext`, `QueryTargets`, `BuildResult`; `syntax` (`QuerySyntax`, `QueryParameters`, `QueryFlag`, `QueryError`, `ActionAliases`), `catalog` (`TypeCatalog`) |
| `command` | root: the `PaperCommand` beans only; `registry` (`HawkeyeCommandRegistry`), `query` (`QueryInput`). Permission nodes are `config.permission.Permissions` (also used by listeners) |
| `entity.importer` | root: `ImportState`, `CheckpointResult`, `SkipReason`; `source` (the CoreProtect side: `SourceRow`, `SourceTable`, `SourceSettings`, ...) |
| `core.service.rollback` | root: `RollbackService` and its request, outcome, mode, issuer, listener, previews; `plan` (planning, no server or database), `apply` (writing a plan into the world) |

A binary format codec is not a repository: it lives with the storage part that owns it
(`core.storage.segment`, `core.storage.index.place`). Test-only benchmarks live in
`core.storage.benchmark` (test sources).

Hawkeye specifics:
- **Services use backend components, not backend data types.** A service may call a backend
  bean (`StorageManager`, `EventStore`, `EventWriter`, `JobRepository`) and catch its
  `SQLException`. A plain value it receives or passes (a count, a scope, totals, writer
  counters) is an entity: `entity.query.EventCount`, `entity.query.ShardScope`,
  `entity.storage.SegmentTotals`, `entity.storage.WriterStats`. Only a type that carries a
  backend handle stays in the backend (`core.storage.shard.Shard` and `StorageState` hold a
  `Database` and `Dictionaries`). `ArchitectureTest` checks this too.
- **Player-facing code reaches storage through a service.** No class in `command`,
  `listener`, `ui` or `integration` imports a `core.*` package other than `core.service`.
- **A callback a service drives is a service-layer interface** implemented above it:
  `core.service.rollback.RollbackListener` is implemented by `RollbackCommand`. Despite the
  name it is not a Bukkit listener.
