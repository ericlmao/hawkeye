package gg.moonrise.hawkeye.support;

import gg.moonrise.hawkeye.HawkeyePlugin;
import gg.moonrise.moss.spring.Loadable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * {@link HawkeyePlugin} for MockBukkit. MockBukkit 4.119 loads plugins through a ByteBuddy subclass
 * named {@code <SimpleName>Proxy} in the unnamed package, and Moss scans
 * {@code getClass().getPackageName()} for beans, which would then be the whole class path. This
 * subclass does exactly what {@code MossPaper.onLoad} does, with the real base package; the error log,
 * enabling, listener registration and disabling stay {@link HawkeyePlugin}'s, Moss's and plugin-engine's own
 * code.
 */
public class TestHawkeyePlugin extends HawkeyePlugin {

    /** The package {@code MossPaper} scans in production. */
    public static final String BASE_PACKAGE = HawkeyePlugin.class.getPackageName();

    @Override
    protected void startContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        CONTEXT = context;
        context.setClassLoader(TestHawkeyePlugin.class.getClassLoader());
        loadInitialComponents(context);
        context.scan(BASE_PACKAGE);
        context.refresh();
        context.start();
        invokeBeans(Loadable.class, loadable -> loadable.onLoad(context), (loadable, e) -> {
            throw new IllegalStateException("Failed to load " + loadable.getClass().getSimpleName(), e);
        });
    }
}
