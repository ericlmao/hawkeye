package gg.moonrise.hawkeye.core.service.query.syntax;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/** A {@code prefix:} in a parameter list, with the names it can be written as. */
public enum QueryPrefix {

    /** {@code u:}: who did it, players by name and sources with {@code #}. */
    USER("u", "user", "users", "p", "player"),
    /** {@code t:}: how far back, or between two ages. */
    TIME("t", "time"),
    /** {@code r:}: how far around the sender, or a world. */
    RADIUS("r", "radius"),
    /** {@code a:}: which actions. */
    ACTION("a", "action", "actions"),
    /** {@code i:}: only these blocks, items or entities. */
    INCLUDE("i", "include", "b", "block", "blocks"),
    /** {@code e:}: except these types or actors. */
    EXCLUDE("e", "exclude"),
    /** {@code s:}: which servers. */
    SERVER("s", "server", "servers");

    private final @NotNull List<String> names;

    private QueryPrefix(@NotNull String @NotNull ... names) {
        this.names = List.of(names);
    }

    /**
     * The short form of this prefix.
     *
     * @return e.g. {@code u:}
     */
    public @NotNull String token() {
        return names.getFirst() + ":";
    }

    /**
     * The prefix a name stands for.
     *
     * @param name e.g. {@code user}, any case, without the colon
     * @return the prefix, or empty when no prefix has that name
     */
    public static @NotNull Optional<QueryPrefix> of(@NotNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (QueryPrefix prefix : values()) {
            if (prefix.names.contains(lower)) {
                return Optional.of(prefix);
            }
        }
        return Optional.empty();
    }
}
