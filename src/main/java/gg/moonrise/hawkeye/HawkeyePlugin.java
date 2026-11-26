package gg.moonrise.hawkeye;

import gg.moonrise.engine.paper.PaperPlugin;
import gg.moonrise.hawkeye.core.diagnostic.Diagnostics;
import gg.moonrise.hawkeye.util.log.LogMarkers;
import gg.moonrise.moss.spring.Disableable;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Plugin entry point on the Moonrise plugin-engine. Moss builds the Spring context in {@code onLoad}
 * from every {@code @SpringComponent} under this package (the relocated engine included), enables
 * {@code Enableable} beans and registers {@code Listener} beans in {@code onEnable}. Nothing is
 * constructed or registered by hand here, except the error log ({@code core.diagnostic}): it opens before
 * the context, so a context that fails to start is written to a crash file and shown once in the console,
 * and the plugin then disables itself instead of throwing. The open {@link Diagnostics} is registered in the
 * context as Moss registers the plugin itself, for the beans that use it.
 *
 * <p>Not {@code final}: MockBukkit loads plugins through a ByteBuddy subclass.
 */
@Slf4j
public class HawkeyePlugin extends PaperPlugin {

    private @Nullable Diagnostics diagnostics;
    private boolean started;

    @Override
    public void onLoad() {
        diagnostics = Diagnostics.open(this);
        started = startup("load", this::startContext);
    }

    @Override
    public void loadInitialComponents(@NotNull AnnotationConfigApplicationContext context) {
        super.loadInitialComponents(context);
        Diagnostics open = Objects.requireNonNull(diagnostics, "the error log opens before the context");
        // No destroy method: Spring would infer close() (AutoCloseable) and close the error log with the context,
        // so the beans stopped after it would log straight to the console. onDisable closes it last.
        context.registerBean(Diagnostics.class, () -> open, definition -> definition.setDestroyMethodName(""));
    }

    /**
     * Builds, refreshes and starts the Spring context and runs every {@code Loadable}: Moss's {@code onLoad}.
     * The test plugin replaces it, because MockBukkit's plugin class sits in another package.
     */
    protected void startContext() {
        super.onLoad();
    }

    @Override
    public void onEnable() {
        if (started) {
            started = startup("enable", super::onEnable);
        }
        if (!started) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        log.info("{} {} enabled", getName(), getPluginMeta().getVersion());
    }

    @Override
    public void onDisable() {
        try {
            super.onDisable();
        } catch (RuntimeException e) {
            log.error("Hawkeye did not stop cleanly", e);
        } finally {
            if (diagnostics != null) {
                diagnostics.close();
                diagnostics = null;
            }
        }
    }

    /**
     * Moss's version ends with {@code Bukkit.getScheduler().cancelTasks(this)}, which throws
     * {@code UnsupportedOperationException} on Folia and so skips the Spring context close that
     * follows in {@code MossPaper.onDisable}. This does the same work with the region-aware
     * schedulers, which exist on Paper and Folia; Paper cancels any legacy scheduler task of a
     * disabled plugin itself. A context that never started has no beans to disable.
     */
    @Override
    public void disableComponents() {
        if (CONTEXT != null && CONTEXT.isActive()) {
            invokeBeans(Disableable.class, Disableable::onDisable, (bean, e) ->
                    log.error("Failed to disable {}", bean.getClass().getSimpleName(), e));
        }
        HandlerList.unregisterAll(this);
        getServer().getGlobalRegionScheduler().cancelTasks(this);
        getServer().getAsyncScheduler().cancelTasks(this);
    }

    /**
     * The error and crash files of this run, while the plugin is loaded.
     *
     * @return the diagnostics, empty before load and after disable
     */
    public @NotNull Optional<Diagnostics> diagnostics() {
        return Optional.ofNullable(diagnostics);
    }

    /**
     * Runs a startup step. Whatever it logs at ERROR, and the exception that ends it, is a crash.
     *
     * @param phase what the step does ({@code load}, {@code enable})
     * @param step  the step
     * @return whether it completed
     */
    private boolean startup(@NotNull String phase, @NotNull Runnable step) {
        Diagnostics open = diagnostics;
        if (open != null) {
            open.startupBegins();
        }
        try {
            step.run();
            return true;
        } catch (RuntimeException | LinkageError e) {
            log.error(LogMarkers.CRASH, "Hawkeye could not {} and is disabled", phase, e);
            return false;
        } finally {
            if (open != null) {
                open.startupEnds();
            }
        }
    }
}
