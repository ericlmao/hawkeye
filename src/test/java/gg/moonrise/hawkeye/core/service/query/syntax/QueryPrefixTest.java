package gg.moonrise.hawkeye.core.service.query.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class QueryPrefixTest {

    /** Every name a prefix can be written as, the parser's aliases before the enum existed. */
    private static final Map<QueryPrefix, List<String>> NAMES = Map.of(
            QueryPrefix.USER, List.of("u", "user", "users", "p", "player"),
            QueryPrefix.TIME, List.of("t", "time"),
            QueryPrefix.RADIUS, List.of("r", "radius"),
            QueryPrefix.ACTION, List.of("a", "action", "actions"),
            QueryPrefix.INCLUDE, List.of("i", "include", "b", "block", "blocks"),
            QueryPrefix.EXCLUDE, List.of("e", "exclude"),
            QueryPrefix.SERVER, List.of("s", "server", "servers"));

    @Test
    public void everyAliasResolvesInAnyCase() {
        assertEquals(QueryPrefix.values().length, NAMES.size());
        NAMES.forEach((prefix, names) -> {
            for (String name : names) {
                assertEquals(prefix, QueryPrefix.of(name).orElseThrow(), name);
                assertEquals(prefix, QueryPrefix.of(name.toUpperCase(Locale.ROOT)).orElseThrow(), name);
                String mixed = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                assertEquals(prefix, QueryPrefix.of(mixed).orElseThrow(), mixed);
            }
        });
    }

    @Test
    public void tokensInDeclarationOrderAreTheSuggestedPrefixes() {
        assertEquals(List.of("u:", "t:", "r:", "a:", "i:", "e:", "s:"),
                Arrays.stream(QueryPrefix.values()).map(QueryPrefix::token).toList());
        assertEquals(QuerySyntax.PREFIXES, Arrays.stream(QueryPrefix.values()).map(QueryPrefix::token).toList());
    }

    @Test
    public void anUnknownNameIsEmpty() {
        assertTrue(QueryPrefix.of("x").isEmpty());
        assertTrue(QueryPrefix.of("").isEmpty());
        assertTrue(QueryPrefix.of("u:").isEmpty());
        assertTrue(QueryPrefix.of("users2").isEmpty());
    }
}
