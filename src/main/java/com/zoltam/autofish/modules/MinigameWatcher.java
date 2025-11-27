package com.zoltam.autofish.modules; // <- match your package

import com.zoltam.autofish.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.text.Text;

import java.lang.reflect.Method;
import java.util.*;

public class MinigameWatcher extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // --- settings ---
    private final Setting<Double> radius = sgGeneral.add(new DoubleSetting.Builder()
        .name("detection-radius")
        .description("Only consider ItemDisplays within this radius.")
        .defaultValue(14.0).min(4.0).sliderRange(4.0, 48.0).build());

    private final Setting<Integer> spawnWindowTicks = sgGeneral.add(new IntSetting.Builder()
        .name("spawn-window-ticks")
        .description("Tick window to consider ItemDisplays as part of the same minigame.")
        .defaultValue(20).min(5).sliderRange(5, 60).build());

    private final Setting<Integer> classifyTicks = sgGeneral.add(new IntSetting.Builder()
        .name("classify-for-ticks")
        .description("How long to observe at start (don’t sneak) to classify box vs fish.")
        .defaultValue(30).min(10).sliderRange(10, 80).build());

    private final Setting<Double> stationaryMaxYRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("stationary-y-range")
        .description("Max Y-range to consider as stationary (the box at the bottom).")
        .defaultValue(0.05).min(0.0).sliderRange(0.0, 0.5).build());

    private final Setting<Double> movingMinYRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("moving-y-range")
        .description("Min Y-range to consider as moving (the fish).")
        .defaultValue(0.18).min(0.0).sliderRange(0.0, 1.0).build());

    private final Setting<Boolean> logTicks = sgGeneral.add(new BoolSetting.Builder()
        .name("log-tracking")
        .description("Echo tracking info to chat while active (can be spammy).")
        .defaultValue(true).build());

    private final Setting<Integer> logEveryNTicks = sgGeneral.add(new IntSetting.Builder()
        .name("log-every-n-ticks")
        .description("Only log once every N ticks while tracking.")
        .defaultValue(5).min(1).sliderRange(1, 40).build());

    // --- state ---
    private int tick;
    private boolean sessionActive = false;
    private int sessionStartTick = -1;
    private int classifyUntilTick = -1;
    private Integer boxId = null;
    private Integer fishId = null;

    // Track candidates: Y stats and firstSeen
    private static class Track {
        int id;
        int firstSeenTick;
        int lastSeenTick;
        double firstY;
        double minY, maxY;

        Track(int id, double y, int t) {
            this.id = id;
            this.firstSeenTick = t;
            this.lastSeenTick = t;
            this.firstY = y;
            this.minY = y;
            this.maxY = y;
        }
        void update(double y, int t) {
            lastSeenTick = t;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        double range() { return maxY - minY; }
    }

    // Active tracked entities this cycle
    private final Map<Integer, Track> tracks = new HashMap<>();
    private String lastActionBar = "";

    public MinigameWatcher() {
        super(AddonTemplate.CATEGORY, "minigame-watcher",
            "Watches the fishing minigame, classifies BOX vs FISH, and logs status/results.");
    }

    @Override
    public void onActivate() {
        tick = 0;
        sessionActive = false;
        sessionStartTick = -1;
        classifyUntilTick = -1;
        boxId = null;
        fishId = null;
        tracks.clear();
        lastActionBar = "";
        info("Minigame watcher enabled. Don’t sneak during the first ~" + classifyTicks.get() + " ticks to help classification.");
    }

    @Override
    public void onDeactivate() {
        info("Minigame watcher disabled.");
        tracks.clear();
        sessionActive = false;
        boxId = null;
        fishId = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post e) {
        if (mc == null || mc.world == null || mc.player == null) return;
        tick++;

        // Scan nearby ItemDisplays
        final double r2 = radius.get() * radius.get();
        Set<Integer> present = new HashSet<>();
        for (Entity ent : mc.world.getEntities()) {
            if (!(ent instanceof DisplayEntity.ItemDisplayEntity)) continue;

            double dx = ent.getX() - mc.player.getX();
            double dy = ent.getY() - mc.player.getY();
            double dz = ent.getZ() - mc.player.getZ();
            if (dx*dx + dy*dy + dz*dz > r2) continue;

            present.add(ent.getId());
            tracks.compute(ent.getId(), (id, tr) -> {
                if (tr == null) return new Track(id, ent.getY(), tick);
                tr.update(ent.getY(), tick);
                return tr;
            });
        }

        // Drop tracks that vanished
        tracks.entrySet().removeIf(en -> !present.contains(en.getKey()));

        // Detect session start: 2+ ItemDisplays spawned inside window
        if (!sessionActive) {
            List<Track> recent = new ArrayList<>();
            for (Track tr : tracks.values()) {
                if (tick - tr.firstSeenTick <= spawnWindowTicks.get()) recent.add(tr);
            }
            if (recent.size() >= 2) {
                sessionActive = true;
                sessionStartTick = tick;
                classifyUntilTick = tick + classifyTicks.get();
                boxId = null;
                fishId = null;
                info(String.format("Minigame? %d candidates (ids: %s). Classifying for %d ticks...",
                    recent.size(), idsToString(recent), classifyTicks.get()));
            }
        }

        // Classification window: assume no sneaking — fish moves, box stationary
        if (sessionActive && fishId == null && boxId == null && tick <= classifyUntilTick) {
            classifyIfClear();
        }

        // If classification not done and window passed, try again with best guess
        if (sessionActive && fishId == null && boxId == null && tick > classifyUntilTick) {
            fallbackClassify();
        }

        // If classified, keep logging their positions while present
        if (sessionActive && fishId != null && boxId != null) {
            Track fish = tracks.get(fishId);
            Track box = tracks.get(boxId);

            if (fish == null || box == null) {
                // One or both despawned -> end session
                info("Minigame entities despawned. Ending session.");
                resetSession();
            } else {
                // Periodic log
                if (logTicks.get() && (tick % logEveryNTicks.get() == 0)) {
                    double err = fish.maxY - box.maxY; // not meaningful, just a placeholder readout
                    info(String.format("Track: fish[id=%d] y=%.3f range=%.3f | box[id=%d] y=%.3f range=%.3f",
                        fish.id, avgY(fish), fish.range(), box.id, avgY(box), box.range()));
                }
            }
        }

        // If we have a session but all ItemDisplays gone, end it
        if (sessionActive && tracks.isEmpty()) {
            info("All ItemDisplays gone. Ending session.");
            resetSession();
        }
    }

    // --- Packet listener for action-bar Caught!/Failed! ---
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        String overlay = tryExtractActionBar(event.packet);
        if (overlay == null || overlay.isEmpty()) return;

        lastActionBar = overlay;
        // Simple contains to be robust to emojis
        String lower = overlay.toLowerCase(Locale.ROOT);
        if (lower.contains("caught")) {
            info("Action bar: Caught! ✓");
            // Don’t reset here; let the despawn/end condition handle it
        } else if (lower.contains("failed")) {
            info("Action bar: Failed! ✗");
        } else {
            // Debug (comment out if noisy)
            // info("Action bar: " + overlay);
        }
    }

    // Extract action-bar text across multiple MC versions/mappings
    private String tryExtractActionBar(Object packet) {
        if (packet == null) return null;
        Class<?> cls = packet.getClass();
        String name = cls.getSimpleName();

        try {
            // 1) OverlayMessageS2CPacket (1.20.2+)
            if (name.equals("OverlayMessageS2CPacket")) {
                // method names vary: content() / getContent()
                Text content = (Text) invokeAny(packet, new String[]{"content","getContent"});
                return content != null ? content.getString() : null;
            }

            // 2) GameMessageS2CPacket with overlay=true (older)
            if (name.equals("GameMessageS2CPacket")) {
                Boolean overlay = (Boolean) invokeAny(packet, new String[]{"isOverlay","overlay"});
                if (overlay != null && overlay) {
                    Text content = (Text) invokeAny(packet, new String[]{"content","getContent","getMessage","message"});
                    return content != null ? content.getString() : null;
                }
                return null; // it was a normal chat message
            }

            // 3) TitleS2CPacket (sometimes used by plugins) – treat as overlay-ish
            if (name.equals("TitleS2CPacket")) {
                Text content = (Text) invokeAny(packet, new String[]{"text","getText","content","getContent","title"});
                return content != null ? content.getString() : null;
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private Object invokeAny(Object target, String[] methodNames) {
        for (String mName : methodNames) {
            try {
                Method m = target.getClass().getMethod(mName);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // --- helpers ---
    private void classifyIfClear() {
        // pick the most "stationary" as BOX, the most "moving" as FISH
        List<Track> live = new ArrayList<>(tracks.values());
        if (live.size() < 2) return;

        // sort by range
        live.sort(Comparator.comparingDouble(Track::range));

        Track candidateBox = live.get(0);
        Track candidateFish = live.get(live.size() - 1);

        boolean boxLooksStill = candidateBox.range() <= stationaryMaxYRange.get();
        boolean fishLooksMoving = candidateFish.range() >= movingMinYRange.get();

        if (boxLooksStill && fishLooksMoving && candidateBox.id != candidateFish.id) {
            boxId = candidateBox.id;
            fishId = candidateFish.id;
            info(String.format("Classified: BOX id=%d (range=%.3f), FISH id=%d (range=%.3f).",
                boxId, candidateBox.range(), fishId, candidateFish.range()));
        }
    }

    private void fallbackClassify() {
        // If we didn’t get a clean read (maybe you sneaked early), pick best guess.
        List<Track> live = new ArrayList<>(tracks.values());
        if (live.size() < 2) return;

        live.sort(Comparator.comparingDouble(Track::range));
        Track candidateBox = live.get(0);
        Track candidateFish = live.get(live.size() - 1);

        boxId = candidateBox.id;
        fishId = candidateFish.id;
        info(String.format("Fallback classify: BOX id=%d (range=%.3f), FISH id=%d (range=%.3f).",
            boxId, candidateBox.range(), fishId, candidateFish.range()));
    }

    private void resetSession() {
        sessionActive = false;
        sessionStartTick = -1;
        classifyUntilTick = -1;
        boxId = null;
        fishId = null;
        tracks.clear();
    }

    private static String idsToString(List<Track> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            sb.append(list.get(i).id);
            if (i < list.size() - 1) sb.append(", ");
        }
        return sb.toString();
    }

    private static double avgY(Track t) {
        return (t.minY + t.maxY) * 0.5;
    }
}
