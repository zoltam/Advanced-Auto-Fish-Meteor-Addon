package com.zoltam.autofish.modules;

import com.zoltam.autofish.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.CookieStorage; // 6-arg connect()
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.network.ClientConnection;
import net.minecraft.text.Text;

/**
 * Advanced Auto Reconnect (MC 1.21.4 / Meteor)
 *
 * - On disconnect: reconnect after 10s, then retry every 60s.
 * - On join: after 10s, runs /joinqueue Nations-Atlas.
 * - Keeps retrying the join command every 60s (useful if sent to hub).
 * - Toggle "reconnect-on-all-disconnects" to also reconnect after manual leaves (Title/Multiplayer).
 * - Test keybind: press to force-disconnect and start the reconnect loop.
 */
public class AdvancedAutoReconnect extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // --- Settings ---

    private final Setting<Double> initialDelayS = sgGeneral.add(new DoubleSetting.Builder()
        .name("initial-reconnect-delay-seconds")
        .description("Delay before the first reconnect attempt after a disconnect.")
        .defaultValue(10.0)
        .min(1.0)
        .build()
    );

    private final Setting<Double> retryIntervalS = sgGeneral.add(new DoubleSetting.Builder()
        .name("retry-interval-seconds")
        .description("Interval between reconnect attempts if connection fails.")
        .defaultValue(60.0)
        .min(5.0)
        .build()
    );

    private final Setting<String> joinCommand = sgGeneral.add(new StringSetting.Builder()
        .name("join-command")
        .description("Command to execute after joining the server.")
        .defaultValue("/joinqueue Nations-Atlas")
        .build()
    );

    private final Setting<Double> joinDelayS = sgGeneral.add(new DoubleSetting.Builder()
        .name("join-command-delay-seconds")
        .description("How long to wait after a successful join before sending the join command.")
        .defaultValue(10.0)
        .min(0.0)
        .build()
    );

    private final Setting<Boolean> keepRetryingJoin = sgGeneral.add(new BoolSetting.Builder()
        .name("keep-retrying-join-every-interval")
        .description("Repeats the join command every retry interval (useful when sent to hub).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> reconnectOnAll = sgGeneral.add(new BoolSetting.Builder()
        .name("reconnect-on-all-disconnects")
        .description("Also reconnect after manual leaves to Title/Multiplayer screen.")
        .defaultValue(true)
        .build()
    );

    // Keybind to force a disconnect & test the reconnect loop
    private final Setting<Keybind> testKeybind = sgGeneral.add(new KeybindSetting.Builder()
        .name("test-keybind")
        .description("Press to force-disconnect and start the reconnect loop.")
        .defaultValue(Keybind.none())
        .build()
    );

    public AdvancedAutoReconnect() {
        super(AddonTemplate.CATEGORY, "advanced-auto-reconnect",
            "Auto-reconnects and re-queues for Nations-Atlas.");
    }

    // --- State ---

    private ServerInfo lastServerInfo;          // Server to reconnect to
    private Screen lastScreen;                  // Parent for ConnectScreen

    private boolean reconnectLoopActive;
    private long nextReconnectAtMs = -1L;

    private boolean firstJoinCommandPending;
    private long nextJoinCommandAtMs = -1L;
    private long nextJoinRepeatAtMs = -1L;

    // edge detector for the test key
    private boolean testKeyWasDown = false;

    @Override
    public void onActivate() {
        lastServerInfo = mc != null ? mc.getCurrentServerEntry() : null;

        reconnectLoopActive = false;
        nextReconnectAtMs = -1L;

        firstJoinCommandPending = false;
        nextJoinCommandAtMs = -1L;
        nextJoinRepeatAtMs = -1L;

        testKeyWasDown = false;
    }

    @Override
    public void onDeactivate() {
        reconnectLoopActive = false;
        nextReconnectAtMs = -1L;

        firstJoinCommandPending = false;
        nextJoinCommandAtMs = -1L;
        nextJoinRepeatAtMs = -1L;

        testKeyWasDown = false;
    }

    // Detect screen changes to start reconnect loop
    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!isActive()) return;

        lastScreen = event.screen;

        // Real disconnect (kicked/timeout/etc.)
        if (event.screen instanceof DisconnectedScreen) {
            startReconnectLoopIfNeeded();
            return;
        }

        // Optional: also reconnect when user manually leaves (Title/Multiplayer)
        if (reconnectOnAll.get() && (event.screen instanceof TitleScreen || event.screen instanceof MultiplayerScreen)) {
            startReconnectLoopIfNeeded();
        }
    }

    // Successful world join: stop reconnect loop, schedule queue command(s)
    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (!isActive()) return;

        lastServerInfo = mc.getCurrentServerEntry();

        reconnectLoopActive = false;
        nextReconnectAtMs = -1L;

        firstJoinCommandPending = true;
        nextJoinCommandAtMs = now() + secs(joinDelayS.get());

        nextJoinRepeatAtMs = keepRetryingJoin.get()
            ? nextJoinCommandAtMs + secs(retryIntervalS.get())
            : -1L;
    }

    // Timers + test keybind
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive()) return;

        long t = now();

        // Test keybind edge detection
        boolean down = testKeybind.get().isPressed();
        if (down && !testKeyWasDown) {
            runTestDisconnect();
        }
        testKeyWasDown = down;

        // Reconnect attempts
        if (reconnectLoopActive && t >= nextReconnectAtMs) {
            attemptReconnect();
            nextReconnectAtMs = t + secs(retryIntervalS.get());
        }

        // First join command after join
        if (firstJoinCommandPending && t >= nextJoinCommandAtMs) {
            sendCommand(joinCommand.get());
            firstJoinCommandPending = false;

            if (keepRetryingJoin.get() && nextJoinRepeatAtMs < 0) {
                nextJoinRepeatAtMs = t + secs(retryIntervalS.get());
            }
        }

        // Keep retrying the join command to escape hub
        if (keepRetryingJoin.get() && mc.player != null && nextJoinRepeatAtMs > 0 && t >= nextJoinRepeatAtMs) {
            sendCommand(joinCommand.get());
            nextJoinRepeatAtMs = t + secs(retryIntervalS.get());
        }
    }

    // --- Helpers ---

    private void startReconnectLoopIfNeeded() {
        if (!reconnectLoopActive) {
            reconnectLoopActive = true;
            nextReconnectAtMs = now() + secs(initialDelayS.get());
        }
    }

    private void runTestDisconnect() {
        if (mc == null) return;

        // Remember current server so we know where to return
        ServerInfo cur = mc.getCurrentServerEntry();
        if (cur != null) lastServerInfo = cur;

        // Start the reconnect loop (respects initial delay)
        startReconnectLoopIfNeeded();

        // Force a clean disconnect that shows DisconnectedScreen
        try {
            if (mc.getNetworkHandler() != null) {
                ClientConnection conn = mc.getNetworkHandler().getConnection();
                if (conn != null) {
                    conn.disconnect(Text.of("Testing AdvancedAutoReconnect"));
                    return;
                }
            }
        } catch (Exception ignored) {}

        // If already disconnected, immediately try a connect once (loop continues)
        attemptReconnect();
    }

    private void attemptReconnect() {
        MinecraftClient client = mc;
        if (client == null) return;

        ServerInfo target = lastServerInfo != null ? lastServerInfo : client.getCurrentServerEntry();
        if (target == null || target.address == null || target.address.isEmpty()) return;

        ServerAddress addr = ServerAddress.parse(target.address);

        Screen parent = lastScreen != null ? lastScreen : client.currentScreen;
        if (parent == null) parent = new Screen(Text.of("Connecting…")) {};

        // 1.21.4: 6-arg overload (parent, client, addr, info, quickPlay, cookieStorage)
        ConnectScreen.connect(parent, client, addr, target, false, (CookieStorage) null);
    }

    private void sendCommand(String raw) {
        if (mc == null || mc.player == null) return;

        if (raw.startsWith("/")) {
            mc.player.networkHandler.sendChatCommand(raw.substring(1));
        } else {
            mc.player.networkHandler.sendChatMessage(raw);
        }
    }

    private static long now() { return System.currentTimeMillis(); }
    private static long secs(double s) { return (long) (s * 1000.0); }
}
