package gg.moonrise.hawkeye.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import gg.moonrise.hawkeye.entity.diagnostic.ProblemKind;
import java.sql.SQLException;
import java.util.concurrent.CompletionException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

public class ProblemTest {

    private static final String TEMPLATE = "The writer loop failed (attempt {}), retrying";

    @Test
    public void theSameRetryWithOtherParametersIsTheSameProblem() {
        Problem third = problem("The writer loop failed (attempt 3), retrying", new SQLException("locked"));
        Problem fourth = problem("The writer loop failed (attempt 4), retrying", new SQLException("busy"));

        assertEquals(third.fingerprint(), fourth.fingerprint());
    }

    @Test
    public void anotherExceptionAtTheSamePlaceIsAnotherProblem() {
        Problem sql = problem("The writer loop failed (attempt 3), retrying", new SQLException("locked"));
        Problem bug = problem("The writer loop failed (attempt 3), retrying", new NullPointerException());
        Problem wrapped = problem("The writer loop failed (attempt 3), retrying",
                new CompletionException(new SQLException("locked")));

        assertNotEquals(sql.fingerprint(), bug.fingerprint());
        assertNotEquals(sql.fingerprint(), wrapped.fingerprint());
    }

    @Test
    public void aCrashIsNotTheSameProblemAsAnError() {
        Problem error = problem("Failed", null);
        Problem crash = new Problem(0, ProblemKind.CRASH, "ERROR", "gg.moonrise.hawkeye.Test", "main", TEMPLATE,
                "Failed", null);

        assertNotEquals(error.fingerprint(), crash.fingerprint());
    }

    @Test
    public void theHeadlineNamesTheInnermostCauseOnOneLine() {
        Problem wrapped = problem("The writer loop failed (attempt 3), retrying",
                new CompletionException(new SQLException("database is\nlocked")));

        assertEquals("The writer loop failed (attempt 3), retrying: SQLException: database is locked",
                wrapped.headline());
    }

    @Test
    public void theHeadlineOfAProblemWithoutExceptionIsItsMessage() {
        assertEquals("Could not connect", problem("Could not connect", null).headline());
        assertEquals("Failed: NullPointerException", problem("Failed", new NullPointerException()).headline());
    }

    @Test
    public void aCauseLoopEnds() {
        Exception first = new Exception("first");
        Exception second = new Exception("second", first);
        first.initCause(second);

        Problem looped = problem("Looped", first);

        assertEquals(looped.fingerprint(), problem("Looped", first).fingerprint());
        assertEquals("Looped: Exception: first", looped.headline());
    }

    @Test
    public void aLongReasonIsCutBeforeASurrogatePair() {
        String smiley = "\uD83D\uDE00";
        Problem problem = problem("Failed", new IllegalStateException("x".repeat(299) + smiley + "tail"));

        assertEquals("Failed: IllegalStateException: " + "x".repeat(299) + "...", problem.headline());
    }

    private static @NotNull Problem problem(@NotNull String message, @Nullable Throwable thrown) {
        return new Problem(0, ProblemKind.ERROR, "ERROR", "gg.moonrise.hawkeye.Test", "main", TEMPLATE, message,
                thrown);
    }
}
