package gg.moonrise.hawkeye.core.diagnostic;

/**
 * A hook in the server's logging that sends Hawkeye's problems to the {@link ErrorLog}. An interface so the code
 * that holds one never loads the log4j classes of {@link Log4jCapture} itself.
 */
public interface ConsoleCapture extends AutoCloseable {

    /** Removes the hook: everything is logged as it would be without the error log. */
    @Override
    void close();
}
