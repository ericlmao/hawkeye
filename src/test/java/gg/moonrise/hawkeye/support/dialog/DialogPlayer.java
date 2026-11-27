package gg.moonrise.hawkeye.support.dialog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.dialog.DialogLike;
import org.jetbrains.annotations.NotNull;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A player that remembers the dialogs it was shown: MockBukkit's {@code showDialog} is Adventure's empty
 * default. Add it with {@code server.addPlayer(player)}. Needs {@link FakeDialogProvider} (registered for
 * every test) so the dialogs are the fake's records.
 */
public class DialogPlayer extends PlayerMock {

    private static final long TIMEOUT_SECONDS = 20;

    private final @NotNull List<DialogLike> dialogs = new CopyOnWriteArrayList<>();

    /**
     * A player with a random id.
     *
     * @param server the mocked server
     * @param name   the player's name
     */
    public DialogPlayer(@NotNull ServerMock server, @NotNull String name) {
        this(server, name, UUID.randomUUID());
    }

    /**
     * A player.
     *
     * @param server the mocked server
     * @param name   the player's name
     * @param id     the player's id
     */
    public DialogPlayer(@NotNull ServerMock server, @NotNull String name, @NotNull UUID id) {
        super(server, name, id);
    }

    @Override
    public void showDialog(@NotNull DialogLike dialog) {
        dialogs.add(dialog);
    }

    /**
     * How many dialogs this player was shown.
     *
     * @return the count
     */
    public int dialogCount() {
        return dialogs.size();
    }

    /**
     * The last dialog this player was shown.
     *
     * @return the dialog, or empty when none was
     */
    public @NotNull Optional<ShownDialog> lastDialog() {
        return dialogs.isEmpty() ? Optional.empty() : Optional.of(ShownDialog.of(dialogs.getLast(), this));
    }

    /**
     * Ticks the mocked scheduler until a dialog arrives that was not there when this was called.
     *
     * @param server the mocked server
     * @return the new dialog
     */
    public @NotNull ShownDialog awaitDialog(@NotNull ServerMock server) {
        int seen = dialogs.size();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (dialogs.size() <= seen) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no new dialog for " + getName() + " within " + TIMEOUT_SECONDS
                        + " s; it was shown " + seen);
            }
            server.getScheduler().performOneTick();
            pause();
        }
        return ShownDialog.of(dialogs.getLast(), this);
    }

    private static void pause() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
