package gg.moonrise.hawkeye.support;

/** {@link TestHawkeyePlugin} whose Spring context fails to start, as with a bean that cannot be created. */
public class FailingHawkeyePlugin extends TestHawkeyePlugin {

    /** The message of the startup failure. */
    public static final String FAILURE = "Error creating bean 'storageManager': the storage settings are broken";

    @Override
    protected void startContext() {
        throw new IllegalStateException(FAILURE, new IllegalArgumentException("storage.type: 'oracle' is unknown"));
    }
}
