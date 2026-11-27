package gg.moonrise.hawkeye.ui.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.ui.dialog.DialogAnswers;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class LookupFiltersTest {

    private static final LookupFilters FILLED = new LookupFilters("Alex,Steve", "1h-3d", "10x5", "-block,kill",
            "stone,dirt", "#tnt", "survival", true);

    @Test
    public void theParametersFollowThePrefixOrder() {
        assertEquals("u:Alex,Steve t:1h-3d r:10x5 a:-block,kill i:stone,dirt e:#tnt s:survival #count",
                FILLED.parameters());
    }

    @Test
    public void blankFieldsAreLeftOut() {
        assertEquals("t:3d", new LookupFilters("", "3d", " ", "", "", "", "", false).parameters());
        assertEquals("u:alex #count", new LookupFilters("alex", "", "", "", "", "", "", true).parameters());
        assertEquals("", LookupFilters.NONE.parameters());
    }

    @Test
    public void listFieldsAreJoinedByCommasAndTimeAndRadiusLoseTheirSpaces() {
        LookupFilters filters = new LookupFilters(" alex,  steve bob ", "1h - 3d", " 10 x 5 ", ", -block ,, kill",
                "\tstone\n", "", " survival , creative", false);
        assertEquals("alex,steve,bob", filters.players());
        assertEquals("1h-3d", filters.time());
        assertEquals("10x5", filters.radius());
        assertEquals("-block,kill", filters.actions());
        assertEquals("stone", filters.include());
        assertEquals("", filters.exclude());
        assertEquals("survival,creative", filters.servers());
    }

    @Test
    public void aFieldCannotAddASecondParameter() {
        LookupFilters filters = new LookupFilters("alex t:1s", "1h r:#global", "", "", "", "", "", false);
        assertEquals("u:alex,t:1s t:1hr:#global", filters.parameters());
        assertFalse(filters.parameters().contains(" t:1s"), filters.parameters());
    }

    @Test
    public void aTypedListBecomesTheFields() {
        LookupFilters filters = LookupFilters.of("user:Alex u:steve T:3d r:5 r:10 a:-block #sum #preview");
        assertEquals(new LookupFilters("Alex,steve", "3d", "10", "-block", "", "", "", true), filters);
    }

    @Test
    public void everyPrefixHasItsField() {
        LookupFilters filters = LookupFilters.of("  p:Alex  include:stone b:dirt exclude:#tnt e:Bob s:a server:b "
                + "action:kill  ");
        assertEquals(new LookupFilters("Alex", "", "", "kill", "stone,dirt", "#tnt,Bob", "b", false), filters);
    }

    @Test
    public void unknownTokensAndOtherFlagsAreIgnored() {
        assertEquals(LookupFilters.NONE, LookupFilters.of("q:stone :x stone #preview u:"));
        assertEquals(LookupFilters.NONE, LookupFilters.of(""));
        assertTrue(LookupFilters.of("#COUNT").count());
    }

    @Test
    public void theParametersReadBackAsTheSameFields() {
        assertEquals(FILLED, LookupFilters.of(FILLED.parameters()));
        assertEquals(LookupFilters.NONE, LookupFilters.of(LookupFilters.NONE.parameters()));
    }

    @Test
    public void theFormsAnswersBecomeTheFields() {
        DialogAnswers answers = new DialogAnswers(Map.of(LookupFilters.PLAYERS, "alex steve",
                LookupFilters.TIME, "1h", LookupFilters.RADIUS, "5", LookupFilters.ACTIONS, "kill",
                LookupFilters.INCLUDE, "zombie", LookupFilters.EXCLUDE, "#fire", LookupFilters.SERVERS, "#network"),
                Map.of(LookupFilters.COUNT, true));
        assertEquals(new LookupFilters("alex,steve", "1h", "5", "kill", "zombie", "#fire", "#network", true),
                LookupFilters.of(answers));
        assertEquals(LookupFilters.NONE, LookupFilters.of(new DialogAnswers(Map.of(), Map.of())));
    }
}
