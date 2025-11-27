package com.zoltam.autofish.modules; // <-- match your package

import com.zoltam.autofish.AddonTemplate;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.text.Text;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Passive observer:
 * - Detects ItemDisplay minigame, classifies BOX (still) vs FISH (moving) quickly.
 * - NO CONTROL — only logs.
 * - Logs worldY and *local transform Y* (if discoverable) each tick.
 * - Logs recommended action (press/release/hold) based on local Y error (without acting).
 * - Sniffs packets and HUD overlay text and writes everything to a TSV (wiped on enable).
 */
public class MinigameObserver extends Module {
    // -------- Settings --------
    private final SettingGroup sgDetect  = settings.createGroup("Detect");
    private final SettingGroup sgLog     = settings.createGroup("Logging");

    private final Setting<Double> radius = sgDetect.add(new DoubleSetting.Builder()
        .name("radius").description("Only consider ItemDisplays within this radius.")
        .defaultValue(14.0).min(4.0).sliderRange(4.0, 48.0).build());

    private final Setting<Integer> spawnWindowTicks = sgDetect.add(new IntSetting.Builder()
        .name("spawn-window-ticks").description("Window grouping ItemDisplay spawns.")
        .defaultValue(16).min(4).sliderRange(4, 60).build());

    private final Setting<Integer> classifyMinTicks = sgDetect.add(new IntSetting.Builder()
        .name("min-classify-ticks").description("Observe at least this many ticks before classifying.")
        .defaultValue(6).min(3).sliderRange(3, 30).build());

    private final Setting<Double> stationaryMaxRange = sgDetect.add(new DoubleSetting.Builder()
        .name("stationary-y-range").description("Max Y-range to consider stationary (BOX).")
        .defaultValue(0.05).min(0.0).sliderRange(0.0, 0.5).build());

    private final Setting<Double> movingMinRange = sgDetect.add(new DoubleSetting.Builder()
        .name("moving-y-range").description("Min Y-range to consider moving (FISH).")
        .defaultValue(0.15).min(0.0).sliderRange(0.0, 1.0).build());

    // “What would the controller do?” (for logging only)
    private final Setting<Double> upperBand = sgDetect.add(new DoubleSetting.Builder()
        .name("error-hi").description("Recommend PRESS when (fishLocalY - boxLocalY) > this.")
        .defaultValue(0.20).min(0.02).sliderRange(0.05, 0.6).build());

    private final Setting<Double> lowerBand = sgDetect.add(new DoubleSetting.Builder()
        .name("error-lo").description("Recommend RELEASE when (fishLocalY - boxLocalY) < this.")
        .defaultValue(0.08).min(0.0).sliderRange(0.0, 0.5).build());

    private final Setting<Boolean> invertError = sgDetect.add(new BoolSetting.Builder()
        .name("invert-error").description("Flip sign if the visual up/down is inverted vs local Y.")
        .defaultValue(false).build());

    private final Setting<Integer> logEvery = sgLog.add(new IntSetting.Builder()
        .name("log-every-n-ticks").description("How often to sample to the TSV.")
        .defaultValue(2).min(1).sliderRange(1, 20).build());

    private final Setting<Boolean> sniffPackets = sgLog.add(new BoolSetting.Builder()
        .name("packet-sniffer").description("Log incoming packet class names & any Text content found.")
        .defaultValue(true).build());

    private final Setting<Boolean> chatNotes = sgLog.add(new BoolSetting.Builder()
        .name("chat-notes").description("Small chat notes for session/classify events.")
        .defaultValue(true).build());

    // -------- Runtime state --------
    private int tick;
    private boolean sessionActive;
    private Integer boxId, fishId;

    private static class Track {
        final int id;
        final int firstSeenTick;
        int lastSeenTick;
        double minWorldY, maxWorldY, lastWorldY;
        Double minLocalY, maxLocalY, lastLocalY; // nullable until we discover it

        Track(int id, double worldY, Double localY, int t) {
            this.id = id;
            this.firstSeenTick = t;
            this.lastSeenTick = t;
            this.minWorldY = this.maxWorldY = this.lastWorldY = worldY;
            this.minLocalY = localY;
            this.maxLocalY = localY;
            this.lastLocalY = localY;
        }
        void update(double worldY, Double localY, int t) {
            lastSeenTick = t;
            lastWorldY = worldY;
            if (worldY < minWorldY) minWorldY = worldY;
            if (worldY > maxWorldY) maxWorldY = worldY;

            if (localY != null) {
                lastLocalY = localY;
                if (minLocalY == null || localY < minLocalY) minLocalY = localY;
                if (maxLocalY == null || localY > maxLocalY) maxLocalY = localY;
            }
        }
        double worldRange() { return maxWorldY - minWorldY; }
        Double localRange() { return (minLocalY == null || maxLocalY == null) ? null : (maxLocalY - minLocalY); }
    }

    private final Map<Integer, Track> tracks = new HashMap<>();
    private int lastLoggedTick = -1;

    // Overlay/HUD
    private String lastOverlaySeen = "";

    // File logging
    private Path logPath;
    private BufferedWriter writer;

    public MinigameObserver() {
        super(AddonTemplate.CATEGORY, "minigame-observer",
            "Passive: detect, classify, and LOG (worldY + local transform Y). No control.");
    }

    // -------- Lifecycle --------
    @Override
    public void onActivate() {
        tick = 0;
        resetSession();
        setupWriter(); // wipe file
        if (chatNotes.get()) info("Observer active — play normally; I’ll log everything.");
    }

    @Override
    public void onDeactivate() {
        tracks.clear();
        sessionActive = false;
        boxId = fishId = null;
        closeWriter();
        if (chatNotes.get()) info("Observer stopped.");
    }

    // -------- Tick loop --------
    @EventHandler
    private void onTick(TickEvent.Post e) {
        if (mc == null || mc.world == null || mc.player == null) return;
        tick++;

        pollActionBarFromHud(); // log any HUD overlay to file

        // Scan nearby ItemDisplays
        final double r2 = radius.get() * radius.get();
        Set<Integer> present = new HashSet<>();
        for (Entity ent : mc.world.getEntities()) {
            if (!(ent instanceof DisplayEntity.ItemDisplayEntity)) continue;

            double dx = ent.getX() - mc.player.getX();
            double dy = ent.getY() - mc.player.getY();
            double dz = ent.getZ() - mc.player.getZ();
            if (dx*dx + dy*dy + dz*dz > r2) continue;

            Double localY = getDisplayLocalY(ent); // may be null if unknown
            present.add(ent.getId());
            tracks.compute(ent.getId(), (id, tr) -> {
                if (tr == null) return new Track(id, ent.getY(), localY, tick);
                tr.update(ent.getY(), localY, tick);
                return tr;
            });
        }
        // Drop vanished
        tracks.entrySet().removeIf(en -> !present.contains(en.getKey()));

        // Session start if >= 2 candidates spawned nearly together
        if (!sessionActive) {
            List<Track> recent = recentTracks(spawnWindowTicks.get());
            if (recent.size() >= 2) {
                sessionActive = true;
                boxId = null; fishId = null;
                if (chatNotes.get()) info("Minigame? Classifying… candidates=" + idsToString(recent));
                fileLog("detect", "session_start", "candidates=" + idsToString(recent), null);
            }
        }

        // Fast classify (box still vs fish moving) — prefer LOCAL range if available
        if (sessionActive && boxId == null && fishId == null) {
            fastClassify();
        }

        // Tracking log (every N ticks)
        if (sessionActive && tick - lastLoggedTick >= logEvery.get() && boxId != null && fishId != null) {
            lastLoggedTick = tick;

            Track box = tracks.get(boxId);
            Track fish = tracks.get(fishId);

            if (box == null || fish == null) {
                if (chatNotes.get()) info("Entities gone — end session.");
                fileLog("track", "entities_gone", "", null);
                resetSession();
            } else {
                // Compute “what would controller do?” using LOCAL Y if available
                Double boxL = box.lastLocalY, fishL = fish.lastLocalY;
                Double errLocal = (boxL != null && fishL != null)
                    ? (invertError.get() ? (boxL - fishL) : (fishL - boxL))
                    : null;
                double errWorld = (invertError.get() ? (box.lastWorldY - fish.lastWorldY) : (fish.lastWorldY - box.lastWorldY));

                String recommend;
                if (errLocal != null) {
                    if (errLocal > upperBand.get()) recommend = "PRESS";
                    else if (errLocal < lowerBand.get()) recommend = "RELEASE";
                    else recommend = "HOLD";
                } else {
                    // Fallback to world error (less reliable)
                    if (errWorld > upperBand.get()) recommend = "PRESS(world)";
                    else if (errWorld < lowerBand.get()) recommend = "RELEASE(world)";
                    else recommend = "HOLD(world)";
                }

                String details = String.format(Locale.ROOT,
                    "rec=%s errLocal=%s errWorld=%.5f",
                    recommend,
                    errLocal == null ? "NA" : String.format(Locale.ROOT, "%.5f", errLocal),
                    errWorld
                );

                fileLog("track", "tick", details, new Snapshot(box, fish));
            }
        }

        // End if all gone
        if (sessionActive && tracks.isEmpty()) {
            fileLog("track", "all_gone", "", null);
            resetSession();
        }
    }

    // -------- Packets / sniffer --------
    @EventHandler
    private void onPacket(PacketEvent.Receive e) {
        if (!sniffPackets.get()) return;
        String cls = e.packet.getClass().getName();
        String txt = extractAnyText(e.packet);
        fileLog("packet", cls, txt == null ? "" : ("text=" + clean(txt)), null);
    }

    // -------- Classification helpers --------
    private void fastClassify() {
        List<Track> live = new ArrayList<>(tracks.values());
        if (live.size() < 2) return;

        // Observe briefly
        int earliest = Integer.MAX_VALUE;
        for (Track t : live) earliest = Math.min(earliest, t.firstSeenTick);
        int observed = tick - earliest;
        if (observed < classifyMinTicks.get()) return;

        // Prefer LOCAL range if both entities have it; else fallback to WORLD
        live.sort((a, b) -> {
            Double ra = a.localRange();
            Double rb = b.localRange();
            if (ra != null && rb != null) return Double.compare(ra, rb);
            // fallback world
            return Double.compare(a.worldRange(), b.worldRange());
        });

        Track candBox  = live.get(0);
        Track candFish = live.get(live.size() - 1);

        Double boxRLocal = candBox.localRange();
        Double fishRLocal = candFish.localRange();
        double boxRWorld = candBox.worldRange();
        double fishRWorld = candFish.worldRange();

        boolean boxStill = (boxRLocal != null) ? (boxRLocal <= stationaryMaxRange.get()) : (boxRWorld <= stationaryMaxRange.get());
        boolean fishMove = (fishRLocal != null) ? (fishRLocal >= movingMinRange.get()) : (fishRWorld >= movingMinRange.get());

        if (boxStill && fishMove && candBox.id != candFish.id) {
            boxId = candBox.id;
            fishId = candFish.id;
            if (chatNotes.get()) info(String.format(Locale.ROOT,
                "Classified: BOX id=%d (rLocal=%s rWorld=%.3f) | FISH id=%d (rLocal=%s rWorld=%.3f)",
                boxId, rStr(boxRLocal), boxRWorld, fishId, rStr(fishRLocal), fishRWorld));
            fileLog("classify", "done",
                String.format(Locale.ROOT, "box=%d rL=%s rW=%.3f fish=%d rL=%s rW=%.3f",
                    boxId, rStr(boxRLocal), boxRWorld, fishId, rStr(fishRLocal), fishRWorld),
                null);
        }
    }

    private List<Track> recentTracks(int window) {
        List<Track> out = new ArrayList<>();
        for (Track tr : tracks.values()) if (tick - tr.firstSeenTick <= window) out.add(tr);
        return out;
    }

    // -------- Overlay / HUD --------
    private void pollActionBarFromHud() {
        try {
            InGameHud hud = mc.inGameHud;
            if (hud == null) return;
            Text t = (Text) invokeAny(hud, "getOverlayMessage", "overlayMessage");
            if (t == null) return;
            String s = t.getString();
            if (s == null || s.isEmpty()) return;

            if (!s.equals(lastOverlaySeen)) {
                lastOverlaySeen = s;
                fileLog("overlay(hud)", "text", clean(s), null);
            }
        } catch (Throwable ignored) {}
    }

    // -------- Local Transform Y (reflection) --------
    /**
     * Attempt to read the ItemDisplay's *local* translation Y.
     * We try common method names to get a transformation object and then a translation vector.
     */
    private Double getDisplayLocalY(Object displayEntity) {
        // 1) Try getTransformation() / transformation()
        Object transform = invokeAny(displayEntity, "getTransformation", "transformation");
        if (transform != null) {
            // Try getTranslation()/getPosition()/translation()
            Object vec = invokeAny(transform, "getTranslation", "getPosition", "translation", "position");
            Double y = extractY(vec);
            if (y != null) return y;
            // Some MC versions return components via methods x(), y(), z()
            Double y2 = extractY(transform);
            if (y2 != null) return y2;
        }
        // 2) Some displays expose direct getters for components
        Double y3 = extractY(displayEntity);
        if (y3 != null) return y3;

        // 3) As a last resort: try data tracker fields by common getters (rare)
        // Not implemented without mixin accessors; return null
        return null;
    }

    private Double extractY(Object obj) {
        if (obj == null) return null;
        try {
            // Common patterns: getY(), y(), getSecond(), j()… try a bunch
            Object v = invokeAny(obj, "getY", "y", "Y", "getSecond");
            if (v instanceof Number) return ((Number) v).doubleValue();
            // Maybe it returns a Vec3-like with fields x/y/z accessible through methods
            try {
                Method mx = obj.getClass().getMethod("y");
                Object r = mx.invoke(obj);
                if (r instanceof Number) return ((Number) r).doubleValue();
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return null;
    }

    // -------- Reflection helpers --------
    private Object invokeAny(Object target, String... names) {
        for (String n : names) {
            try {
                Method m = target.getClass().getMethod(n);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private String extractAnyText(Object packet) {
        String[] methods = new String[]{"content","getContent","text","getText","message","getMessage","title","getTitle","subtitle","getSubtitle"};
        for (String m : methods) {
            try {
                Object o = packet.getClass().getMethod(m).invoke(packet);
                if (o instanceof Text) return ((Text) o).getString();
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // -------- File logging --------
    private static class Snapshot {
        final double boxWorldY, fishWorldY;
        final Double boxLocalY, fishLocalY;
        Snapshot(Track box, Track fish) {
            this.boxWorldY  = box.lastWorldY;
            this.fishWorldY = fish.lastWorldY;
            this.boxLocalY  = box.lastLocalY;
            this.fishLocalY = fish.lastLocalY;
        }
    }

    private void setupWriter() {
        closeWriter();
        try {
            Path logsDir = FabricLoader.getInstance().getGameDir().resolve("logs");
            Files.createDirectories(logsDir);
            logPath = logsDir.resolve("meteor_observer_log.tsv");
            writer = Files.newBufferedWriter(logPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, // wipe on start
                StandardOpenOption.WRITE);
            header();
            if (chatNotes.get()) info("Writing: " + logPath.getFileName());
        } catch (IOException e) {
            error("Failed to open log file: " + e.getMessage());
            writer = null;
        }
    }

    private void header() {
        writeLine("# Minigame Observer — " + Instant.now());
        writeLine("ts\tick\tphase\tevent\tdetails\tsession\tboxId\tfishId\tboxWorldY\tfishWorldY\tboxLocalY\tfishLocalY\terrLocal\terrWorld\tsneakDown\tpacketOrOverlay");
    }

    private void writeLine(String s) {
        if (writer == null) return;
        try {
            writer.write(s);
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {}
    }

    private void fileLog(String phase, String event, String details, Snapshot snap) {
        String overlay = lastOverlaySeen == null ? "" : clean(lastOverlaySeen);
        String line = String.format(Locale.ROOT, "%d\t%d\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s",
            System.currentTimeMillis(), tick, phase, event, clean(details),
            sessionActive,
            idStr(boxId), idStr(fishId),
            snap == null ? "" : fmt(snap.boxWorldY),
            snap == null ? "" : fmt(snap.fishWorldY),
            snap == null ? "" : fmtD(snap.boxLocalY),
            snap == null ? "" : fmtD(snap.fishLocalY),
            snap == null ? "" : errLocalStr(snap),
            snap == null ? "" : fmt(errWorld(snap)),
            mc.player != null && mc.player.isSneaking(),
            overlay
        );
        writeLine(line);
    }

    private String errLocalStr(Snapshot s) {
        if (s.boxLocalY == null || s.fishLocalY == null) return "";
        double e = invertError.get() ? (s.boxLocalY - s.fishLocalY) : (s.fishLocalY - s.boxLocalY);
        return fmt(e);
    }

    private double errWorld(Snapshot s) {
        double e = invertError.get() ? (s.boxWorldY - s.fishWorldY) : (s.fishWorldY - s.boxWorldY);
        return e;
    }

    private String fmt(double v) { return String.format(Locale.ROOT, "%.5f", v); }
    private String fmtD(Double v) { return v == null ? "" : fmt(v); }
    private String idStr(Integer i) { return i == null ? "" : Integer.toString(i); }
    private String clean(String s) { return s == null ? "" : s.replace('\t',' ').replace('\n',' '); }

    private void closeWriter() {
        if (writer != null) try { writer.flush(); writer.close(); } catch (IOException ignored) {}
        writer = null;
    }

    private static String idsToString(List<Track> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            sb.append(list.get(i).id);
            if (i < list.size() - 1) sb.append(", ");
        }
        return sb.toString();
    }

    private String rStr(Double d) { return d == null ? "NA" : String.format(Locale.ROOT, "%.3f", d); }

    private void resetSession() {
        sessionActive = false;
        boxId = null;
        fishId = null;
        tracks.clear();
        lastLoggedTick = -1;
    }
}
