package gg.moonrise.hawkeye.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class NoDialogScreensTest {

    private ServerMock server;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void anOldServerShowsNothing() {
        PlayerMock alex = server.addPlayer("Alex");
        DialogScreen screen = new DialogScreen(Component.text("Title"), List.of(Component.text("line")), 200,
                List.of(), new DialogScreen.Buttons.Choice(new DialogScreen.Press(Component.text("Yes"), answers -> {
                }), new DialogScreen.Close(Component.text("No"))));
        NoDialogScreens screens = new NoDialogScreens();

        assertFalse(screens.supported());
        assertFalse(screens.show(alex, screen));
    }
}
