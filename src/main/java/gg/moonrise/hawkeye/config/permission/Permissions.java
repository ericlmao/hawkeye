package gg.moonrise.hawkeye.config.permission;

/**
 * The command root and every permission node. All are contracts with operators: change them only as
 * an explicit breaking change, together with {@code paper-plugin.yml}, the README and the changelog.
 */
public final class Permissions {

    /**
     * The root command's name. Its aliases, and those of its subcommands, come from {@code commands.yml}
     * ({@code config.file.CommandsConfig}).
     */
    public static final String ROOT = "hawkeye";

    /** {@code /hawkeye} and {@code /hawkeye help}. */
    public static final String HELP = "hawkeye.help";

    /** {@code /hawkeye inspect}. */
    public static final String INSPECT = "hawkeye.inspect";

    /** {@code /hawkeye lookup}, {@code near} and {@code page}. */
    public static final String LOOKUP = "hawkeye.lookup";

    /** {@code /hawkeye tp}. */
    public static final String TELEPORT = "hawkeye.teleport";

    /** {@code /hawkeye rollback}, {@code restore}, {@code apply}, {@code cancel} and {@code undo}. */
    public static final String ROLLBACK = "hawkeye.rollback";

    /** {@code /hawkeye purge}. */
    public static final String PURGE = "hawkeye.purge";

    /** {@code /hawkeye import coreprotect}, {@code import status} and {@code import cancel}. */
    public static final String IMPORT = "hawkeye.import";

    /** Other servers in parameters ({@code s:}) and {@code /hawkeye network}. */
    public static final String NETWORK = "hawkeye.network";

    /** {@code /hawkeye reload} and {@code /hawkeye status}. */
    public static final String ADMIN = "hawkeye.admin";

    /** {@code /hawkeye errors} and the notice on join about error and crash files waiting for review. */
    public static final String ERRORS = "hawkeye.errors";

    private Permissions() {
    }
}
