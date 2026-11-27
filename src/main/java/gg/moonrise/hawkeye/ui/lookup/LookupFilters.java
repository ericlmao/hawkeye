package gg.moonrise.hawkeye.ui.lookup;

import gg.moonrise.hawkeye.core.service.query.syntax.QueryFlag;
import gg.moonrise.hawkeye.core.service.query.syntax.QueryPrefix;
import gg.moonrise.hawkeye.ui.dialog.DialogAnswers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

/**
 * What the lookup dialog's filter form holds: one text per parameter prefix and the {@code #count} switch. It
 * turns a typed parameter list into fields and the fields back into a parameter list. No field ever holds
 * whitespace (list fields are joined by commas, time and radius lose theirs), so one field cannot add a
 * second parameter to the list.
 *
 * @param players {@code u:} values, comma separated
 * @param time    the {@code t:} value
 * @param radius  the {@code r:} value
 * @param actions {@code a:} values, comma separated
 * @param include {@code i:} values, comma separated
 * @param exclude {@code e:} values, comma separated
 * @param servers {@code s:} values, comma separated
 * @param count   whether {@code #count} is set
 */
public record LookupFilters(@NotNull String players, @NotNull String time, @NotNull String radius,
        @NotNull String actions, @NotNull String include, @NotNull String exclude, @NotNull String servers,
        boolean count) {

    /** The dialog field key of {@link #players()}. */
    public static final String PLAYERS = "players";
    /** The dialog field key of {@link #time()}. */
    public static final String TIME = "time";
    /** The dialog field key of {@link #radius()}. */
    public static final String RADIUS = "radius";
    /** The dialog field key of {@link #actions()}. */
    public static final String ACTIONS = "actions";
    /** The dialog field key of {@link #include()}. */
    public static final String INCLUDE = "include";
    /** The dialog field key of {@link #exclude()}. */
    public static final String EXCLUDE = "exclude";
    /** The dialog field key of {@link #servers()}. */
    public static final String SERVERS = "servers";
    /** The dialog field key of {@link #count()}. */
    public static final String COUNT = "count";

    /** What separates list values. Declared before {@link #NONE}, whose constructor uses it. */
    private static final Pattern LIST_SEPARATOR = Pattern.compile("[\\s,]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** No filter at all. */
    public static final LookupFilters NONE = new LookupFilters("", "", "", "", "", "", "", false);

    /** Normalises every field: list fields joined by commas, time and radius without whitespace. */
    public LookupFilters {
        players = list(players);
        time = compact(time);
        radius = compact(radius);
        actions = list(actions);
        include = list(include);
        exclude = list(exclude);
        servers = list(servers);
    }

    /**
     * The fields of a typed parameter list. Repeated {@code u:}, {@code a:}, {@code i:} and {@code e:} add up;
     * for {@code t:}, {@code r:} and {@code s:} the last one counts, as in the query syntax. Flags other than
     * {@code #count} and tokens that are no parameter are left out.
     *
     * @param parameters e.g. {@code u:Alex t:3d #count}
     * @return the fields
     */
    public static @NotNull LookupFilters of(@NotNull String parameters) {
        Fields fields = new Fields();
        for (String token : WHITESPACE.split(parameters.strip())) {
            fields.accept(token);
        }
        return fields.build();
    }

    /**
     * The fields a player submitted in the filter form.
     *
     * @param answers the form's answers
     * @return the fields
     */
    public static @NotNull LookupFilters of(@NotNull DialogAnswers answers) {
        return new LookupFilters(answers.text(PLAYERS), answers.text(TIME), answers.text(RADIUS),
                answers.text(ACTIONS), answers.text(INCLUDE), answers.text(EXCLUDE), answers.text(SERVERS),
                answers.toggle(COUNT));
    }

    /**
     * The parameter list these fields stand for.
     *
     * @return one {@code prefix:value} per filled field, then {@code #count} when set, separated by spaces;
     *         {@code ""} when nothing is set
     */
    public @NotNull String parameters() {
        List<String> tokens = new ArrayList<>();
        add(tokens, QueryPrefix.USER, players);
        add(tokens, QueryPrefix.TIME, time);
        add(tokens, QueryPrefix.RADIUS, radius);
        add(tokens, QueryPrefix.ACTION, actions);
        add(tokens, QueryPrefix.INCLUDE, include);
        add(tokens, QueryPrefix.EXCLUDE, exclude);
        add(tokens, QueryPrefix.SERVER, servers);
        if (count) {
            tokens.add(QueryFlag.COUNT.token());
        }
        return String.join(" ", tokens);
    }

    private static void add(@NotNull List<String> tokens, @NotNull QueryPrefix prefix, @NotNull String value) {
        if (!value.isEmpty()) {
            tokens.add(prefix.token() + value);
        }
    }

    private static @NotNull String list(@NotNull String value) {
        return Arrays.stream(LIST_SEPARATOR.split(Objects.requireNonNull(value, "value")))
                .filter(part -> !part.isEmpty())
                .collect(Collectors.joining(","));
    }

    private static @NotNull String compact(@NotNull String value) {
        return WHITESPACE.matcher(Objects.requireNonNull(value, "value")).replaceAll("");
    }

    /** Collects the fields of a parameter list token by token. */
    private static final class Fields {

        private String players = "";
        private String time = "";
        private String radius = "";
        private String actions = "";
        private String include = "";
        private String exclude = "";
        private String servers = "";
        private boolean count;

        private void accept(@NotNull String token) {
            Optional<QueryFlag> flag = QueryFlag.of(token);
            if (flag.isPresent()) {
                count |= flag.get() == QueryFlag.COUNT;
                return;
            }
            int colon = token.indexOf(':');
            if (colon <= 0) {
                return;
            }
            String value = token.substring(colon + 1);
            QueryPrefix.of(token.substring(0, colon)).ifPresent(prefix -> accept(prefix, value));
        }

        private void accept(@NotNull QueryPrefix prefix, @NotNull String value) {
            switch (prefix) {
                case USER -> players = join(players, value);
                case TIME -> time = value;
                case RADIUS -> radius = value;
                case ACTION -> actions = join(actions, value);
                case INCLUDE -> include = join(include, value);
                case EXCLUDE -> exclude = join(exclude, value);
                case SERVER -> servers = value;
            }
        }

        private static @NotNull String join(@NotNull String before, @NotNull String value) {
            return before.isEmpty() ? value : before + "," + value;
        }

        private @NotNull LookupFilters build() {
            return new LookupFilters(players, time, radius, actions, include, exclude, servers, count);
        }
    }
}
