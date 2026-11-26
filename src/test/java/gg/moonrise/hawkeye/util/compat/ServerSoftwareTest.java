package gg.moonrise.hawkeye.util.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

public class ServerSoftwareTest {

    @Test
    public void theMostSpecificSoftwareWins() {
        assertEquals(ServerSoftware.SPIGOT, detect(ServerSoftware.SPIGOT));
        assertEquals(ServerSoftware.PAPER, detect(ServerSoftware.PAPER, ServerSoftware.SPIGOT));
        assertEquals(ServerSoftware.PURPUR, detect(ServerSoftware.PURPUR, ServerSoftware.PUFFERFISH,
                ServerSoftware.PAPER, ServerSoftware.SPIGOT));
        assertEquals(ServerSoftware.LEAF, detect(ServerSoftware.LEAF, ServerSoftware.GALE, ServerSoftware.PURPUR,
                ServerSoftware.PAPER, ServerSoftware.SPIGOT));
        assertEquals(ServerSoftware.FOLIA, detect(ServerSoftware.FOLIA, ServerSoftware.PURPUR, ServerSoftware.PAPER,
                ServerSoftware.SPIGOT));
    }

    @Test
    public void noMarkerIsUnknown() {
        assertEquals(ServerSoftware.UNKNOWN, detect());
    }

    @Test
    public void theTestClassPathHasNoServerClasses() {
        // paper-api and MockBukkit ship none of the server-only marker classes.
        assertEquals(ServerSoftware.UNKNOWN, ServerSoftware.detect(ServerSoftwareTest.class.getClassLoader()));
    }

    private static @NotNull ServerSoftware detect(@NotNull ServerSoftware... present) {
        Set<String> classes = Set.of(Arrays.stream(present).map(ServerSoftware::markerClass)
                .toArray(String[]::new));
        return ServerSoftware.detect(classes::contains);
    }
}
