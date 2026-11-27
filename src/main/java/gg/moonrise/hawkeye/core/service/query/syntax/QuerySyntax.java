package gg.moonrise.hawkeye.core.service.query.syntax;

import gg.moonrise.hawkeye.config.file.ServerSettings;
import gg.moonrise.hawkeye.core.service.query.catalog.TypeCatalog;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.util.text.Durations;
import gg.moonrise.moss.spring.SpringComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * Parses the parameter list shared by lookup, rollback, restore and purge. The syntax follows
 * CoreProtect's so staff can switch without relearning:
 * <pre>
 *   u:Alex,#tnt       who (players by name, sources with #)
 *   t:2h | t:1h-3d    how far back, or between two ages
 *   r:10 | r:10x5 | r:#world_nether | r:#global
 *   a:-block,+container
 *   i:diamond_ore     only these blocks, items or entities
 *   e:stone,Steve     except these types or actors
 *   s:survival | s:#network
 *   #preview #count
 * </pre>
 * Prefixes are case-insensitive and have long forms ({@code user:}, {@code time:}, {@code radius:},
 * {@code action:}, {@code include:}, {@code exclude:}, {@code server:}).
 */
@SpringComponent
@RequiredArgsConstructor
public final class QuerySyntax {

    /** The parameter prefixes, short form first, for suggestions. */
    public static final List<String> PREFIXES = Arrays.stream(QueryPrefix.values()).map(QueryPrefix::token).toList();

    private static final Pattern PLAYER_NAME = Pattern.compile("[a-z0-9_.]{1,16}");
    private static final Pattern RADIUS = Pattern.compile("(\\d{1,7})(?:x(\\d{1,4}))?");

    private final @NotNull TypeCatalog typeCatalog;

    /**
     * Parses a parameter list.
     *
     * @param input the text after the subcommand, e.g. {@code u:Alex t:2h}
     * @return the parameters, or the first invalid token
     */
    public @NotNull ParseResult parse(@NotNull String input) {
        Builder builder = new Builder();
        for (String token : input.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            Optional<QueryError> error = builder.accept(token);
            if (error.isPresent()) {
                return new ParseResult.Invalid(error.get());
            }
        }
        return new ParseResult.Parsed(builder.build());
    }

    private final class Builder {

        private final Set<String> actors = new HashSet<>();
        private final Set<String> excludedActors = new HashSet<>();
        private final Set<Action> actions = EnumSet.noneOf(Action.class);
        private final Set<String> includedTypes = new HashSet<>();
        private final Set<String> excludedTypes = new HashSet<>();
        private final Set<QueryFlag> flags = EnumSet.noneOf(QueryFlag.class);
        private QueryParameters.TimeSpec time = new QueryParameters.TimeSpec.Any();
        private QueryParameters.RadiusSpec radius = new QueryParameters.RadiusSpec.Unspecified();
        private QueryParameters.ServerSpec servers = new QueryParameters.ServerSpec.Local();

        private @NotNull Optional<QueryError> accept(@NotNull String token) {
            Optional<QueryFlag> flag = QueryFlag.of(token);
            if (flag.isPresent()) {
                flags.add(flag.get());
                return Optional.empty();
            }
            int colon = token.indexOf(':');
            if (colon <= 0) {
                return error(QueryError.Kind.UNKNOWN_PARAMETER, token);
            }
            Optional<QueryPrefix> prefix = QueryPrefix.of(token.substring(0, colon));
            String value = token.substring(colon + 1);
            List<String> values = values(value);
            if (values.isEmpty()) {
                return error(QueryError.Kind.EMPTY_VALUE, token);
            }
            if (prefix.isEmpty()) {
                return error(QueryError.Kind.UNKNOWN_PARAMETER, token);
            }
            return switch (prefix.get()) {
                case USER -> actors(values);
                case TIME -> time(value);
                case RADIUS -> radius(value);
                case ACTION -> actions(values);
                case INCLUDE -> includes(values);
                case EXCLUDE -> excludes(values);
                case SERVER -> servers(values);
            };
        }

        private @NotNull Optional<QueryError> actors(@NotNull List<String> values) {
            for (String value : values) {
                Optional<String> actor = actor(value);
                if (actor.isEmpty()) {
                    return error(QueryError.Kind.EMPTY_VALUE, value);
                }
                actors.add(actor.get());
            }
            return Optional.empty();
        }

        private @NotNull Optional<QueryError> time(@NotNull String value) {
            int dash = value.indexOf('-');
            if (dash < 0) {
                Optional<Duration> age = Durations.parse(value);
                if (age.isEmpty()) {
                    return error(QueryError.Kind.INVALID_TIME, value);
                }
                time = new QueryParameters.TimeSpec.Within(age.get().toMillis());
                return Optional.empty();
            }
            Optional<Duration> first = Durations.parse(value.substring(0, dash));
            Optional<Duration> second = Durations.parse(value.substring(dash + 1));
            if (first.isEmpty() || second.isEmpty()) {
                return error(QueryError.Kind.INVALID_TIME, value);
            }
            long a = first.get().toMillis();
            long b = second.get().toMillis();
            time = new QueryParameters.TimeSpec.Between(Math.min(a, b), Math.max(a, b));
            return Optional.empty();
        }

        private @NotNull Optional<QueryError> radius(@NotNull String value) {
            if (value.equalsIgnoreCase("#global")) {
                radius = new QueryParameters.RadiusSpec.Global();
                return Optional.empty();
            }
            if (value.startsWith("#") && value.length() > 1) {
                radius = new QueryParameters.RadiusSpec.World(value.substring(1));
                return Optional.empty();
            }
            Matcher matcher = RADIUS.matcher(value.toLowerCase(Locale.ROOT));
            if (!matcher.matches()) {
                return error(QueryError.Kind.INVALID_RADIUS, value);
            }
            int horizontal = Integer.parseInt(matcher.group(1));
            int vertical = matcher.group(2) == null ? -1 : Integer.parseInt(matcher.group(2));
            radius = new QueryParameters.RadiusSpec.Blocks(horizontal, vertical);
            return Optional.empty();
        }

        private @NotNull Optional<QueryError> actions(@NotNull List<String> values) {
            for (String value : values) {
                Optional<Set<Action>> matched = ActionAliases.of(value);
                if (matched.isEmpty()) {
                    return error(QueryError.Kind.UNKNOWN_ACTION, value);
                }
                actions.addAll(matched.get());
            }
            return Optional.empty();
        }

        private @NotNull Optional<QueryError> includes(@NotNull List<String> values) {
            for (String value : values) {
                Optional<String> type = typeCatalog.resolve(value);
                if (type.isEmpty()) {
                    return error(QueryError.Kind.UNKNOWN_TYPE, value);
                }
                includedTypes.add(type.get());
            }
            return Optional.empty();
        }

        private @NotNull Optional<QueryError> excludes(@NotNull List<String> values) {
            for (String value : values) {
                Optional<String> type = value.startsWith("#") ? Optional.empty() : typeCatalog.resolve(value);
                if (type.isPresent()) {
                    excludedTypes.add(type.get());
                    continue;
                }
                Optional<String> actor = actor(value);
                if (actor.isEmpty()) {
                    return error(QueryError.Kind.UNKNOWN_TYPE, value);
                }
                excludedActors.add(actor.get());
            }
            return Optional.empty();
        }

        private @NotNull Optional<QueryError> servers(@NotNull List<String> values) {
            if (values.stream().anyMatch(value -> value.equalsIgnoreCase("#network")
                    || value.equalsIgnoreCase("#all"))) {
                servers = new QueryParameters.ServerSpec.Network();
                return Optional.empty();
            }
            Set<String> names = new HashSet<>();
            for (String value : values) {
                String name = value.toLowerCase(Locale.ROOT);
                if (!ServerSettings.NAME.matcher(name).matches()) {
                    return error(QueryError.Kind.INVALID_SERVER, value);
                }
                names.add(name);
            }
            servers = new QueryParameters.ServerSpec.Named(names);
            return Optional.empty();
        }

        private @NotNull QueryParameters build() {
            return new QueryParameters(actors, excludedActors, time, radius, actions, includedTypes, excludedTypes,
                    servers, flags);
        }
    }

    private static @NotNull Optional<String> actor(@NotNull String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith(String.valueOf(Actor.SOURCE_PREFIX))) {
            return lower.length() > 1 ? Optional.of(lower) : Optional.empty();
        }
        return PLAYER_NAME.matcher(lower).matches() ? Optional.of(lower) : Optional.empty();
    }

    private static @NotNull List<String> values(@NotNull String value) {
        List<String> values = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                values.add(part.trim());
            }
        }
        return values;
    }

    private static @NotNull Optional<QueryError> error(@NotNull QueryError.Kind kind, @NotNull String value) {
        return Optional.of(new QueryError(kind, value));
    }
}
