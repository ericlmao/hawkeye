package gg.moonrise.hawkeye.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.diagnostic.ProblemKind;
import gg.moonrise.hawkeye.util.log.LogMarkers;
import java.io.StringReader;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.NullConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.slf4j.LoggerFactory;

/** Opening the error log for a plugin on MockBukkit, with the tests' real log4j. */
public class DiagnosticsTest {

    private PluginMock hawkeye;

    @BeforeEach
    public void setUp() {
        MockBukkit.mock();
        hawkeye = MockBukkit.createMockPlugin("Hawkeye");
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void anotherPluginsLogOnTheStartupThreadIsLeftAlone() throws Exception {
        MockBukkit.createMockPlugin("OtherPlugin");
        MockBukkit.loadWith(PluginMock.class, new PluginDescriptionFile(new StringReader(
                "name: ShopPlugin\nversion: '1.0'\nmain: " + PluginMock.class.getName() + "\nprefix: Shop\n")));
        String spring = "org.springframework.context.support.AbstractApplicationContext";
        try (ConsoleLines console = ConsoleLines.attach()) {
            Diagnostics diagnostics = Diagnostics.open(hawkeye);
            try {
                diagnostics.startupBegins();
                try {
                    // Paper logs a listener's failure under the plugin's logger, named after it or its prefix.
                    LogManager.getLogger("OtherPlugin").error("Could not pass event ServiceRegisterEvent to "
                            + "OtherPlugin v1.0", new IllegalStateException("their listener"));
                    LogManager.getLogger("Shop").warn("Their warning");
                    LoggerFactory.getLogger(spring).error("Context initialization failed",
                            new IllegalStateException("no bean"));
                } finally {
                    diagnostics.startupEnds();
                }
                diagnostics.errorLog().flush().get(5, TimeUnit.SECONDS);

                List<LogEvent> theirs = console.of("OtherPlugin");
                assertEquals(1, theirs.size(), theirs.toString());
                assertEquals("Could not pass event ServiceRegisterEvent to OtherPlugin v1.0",
                        theirs.getFirst().getMessage().getFormattedMessage());
                assertNotNull(theirs.getFirst().getThrown(), "logged as it is");
                assertEquals("Their warning", console.of("Shop").getFirst().getMessage().getFormattedMessage());
                String ours = console.of(spring).getFirst().getMessage().getFormattedMessage();
                assertTrue(ours.contains("(details in "), "Spring starting Hawkeye's context is Hawkeye's: " + ours);
            } finally {
                diagnostics.close();
            }
        }
    }

    @Test
    public void aLoggingThatCannotBeHookedLeavesTheErrorLogOff() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        BrokenConfiguration broken = new BrokenConfiguration();
        context.setConfiguration(broken);
        try (ConsoleLines console = ConsoleLines.attach()) {
            broken.broken = true;
            Diagnostics diagnostics = Diagnostics.open(hawkeye);
            broken.broken = false;
            try {
                List<LogEvent> lines = console.of(Diagnostics.class.getName());
                assertEquals(1, lines.size(), lines.toString());
                LogEvent line = lines.getFirst();
                assertEquals(Level.WARN, line.getLevel());
                assertEquals("Hawkeye could not hook into the server's logging; its errors are not written to files",
                        line.getMessage().getFormattedMessage());
                assertTrue(line.getMarker().isInstanceOf(LogMarkers.REPORTED_NAME), "not filed itself");
                assertInstanceOf(IllegalStateException.class, line.getThrown());

                ReportResult result = diagnostics.errorLog().report(new Problem(System.currentTimeMillis(),
                        ProblemKind.ERROR, "ERROR", "gg.moonrise.hawkeye.Test", "main", "failed", "failed", null));
                assertEquals(new ReportResult.Untracked("the error log is closed"), result, "Hawkeye runs without it");
            } finally {
                diagnostics.close();
            }
        } finally {
            broken.broken = false;
            context.reconfigure();
        }
    }

    /** A logging configuration, WARN without appenders, whose root logger cannot be had while {@link #broken}. */
    private static final class BrokenConfiguration extends NullConfiguration {

        private volatile boolean broken;

        private BrokenConfiguration() {
            super.getRootLogger().setLevel(Level.WARN);
        }

        /** Nothing to configure: without this log4j falls back to its default (ERROR to the real console). */
        @Override
        protected void doConfigure() {
        }

        @Override
        public LoggerConfig getRootLogger() {
            if (broken) {
                throw new IllegalStateException("no root logger");
            }
            return super.getRootLogger();
        }
    }
}
