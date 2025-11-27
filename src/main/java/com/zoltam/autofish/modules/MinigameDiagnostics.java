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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Watch-only diagnostics for the fishing minigame.
 *
 * - No input / no control.
 * - Logs world motion (x/y/z, deltas), and scans DataTracker entries to find a "y-like" value
 *   per entity by observing which entry's numeric Y signal varies most over time.
 * - Identifies likely FISH vs BOX by variance (moving vs still) without committing to local/world axis.
 * - Logs HUD overlay and incoming packet class names + any Text content found.
 *
 * Produces: .minecraft/logs/meteor_minigame_diag.tsv (wiped each enable).
 */
public class MinigameDiagnostics extends Module {
    // Settings
    private final SettingGroup sgDetect = settings.createGroup("Detect");
    private final SettingGroup sgLog    = settings.createGroup("Logging");

    private final Setting<Double> radius = sgDetect.add(new DoubleSetting.Builder()
        .name("radius").description("Only consider ItemDisplays within this radius.")
        .defaultValue(14.0).min(4.0).sliderRange(4.0, 48.0).build());

    private final Setting<Integer> spawnWindow = sgDetect.add(new IntSetting.Builder()
        .name("spawn-window-ticks").description("Window to group ItemDisplay spawns.")
        .defaultValue(16).min(4).sliderRange(4, 60).build());

    private final Setting<Integer> sampleEvery = sgLog.add(new IntSetting.Builder()
        .name("log-every-n-ticks").description("How often to log per-entity samples.")
        .defaultValue(1).min(1).sliderRange(1, 10).build());

    private final Setting<Integer> maxTicksPerSession = sgLog.add(new IntSetting.Builder()
        .name("max-ticks-per-session").description("Cap diagnostics per session to keep files small.")
        .defaultValue(260).min(40).sliderRange(40, 1000).build());

    private final Setting<Boolean> sniffPackets = sgLog.add(new BoolSetting.Builder()
        .name("packet-sniffer").description("Log packet class names and any Text content (debug).")
        .defaultValue(true).build());

    private final Setting<Boolean> chatNotes = sgLog.add(new BoolSetting.Builder()
        .name("chat-notes").description("Small chat notes for session/classify events.")
        .defaultValue(true).build());

    // Runtime
    private int tick;
    private boolean sessionActive;
    private int sessionStartTick = -1;
    private final Map<Integer, Track> tracks = new HashMap<>();

    private String lastOverlaySeen = "";

    // File logging
    private Path logPath;
    private BufferedWriter writer;

    public MinigameDiagnostics() {
        super(AddonTemplate.CATEGORY, "minigame-diagnostics",
            "Logs world motion and DataTracker-derived 'y-like' signals per ItemDisplay. No control.");
    }

    // ---- Lifecycle ----
    @Override
    public void onActivate() {
        tick = 0;
        sessionActive = false;
        sessionStartTick = -1;
        tracks.clear();
        setupWriter(); // wipes file
        if (chatNotes.get()) info("Diagnostics active — play a few minigames; I'll log everything.");
    }

    @Override
    public void onDeactivate() {
        tracks.clear();
        sessionActive = false;
        closeWriter();
        if (chatNotes.get()) info("Diagnostics stopped.");
    }

    // ---- Tick ----
    @EventHandler
    private void onTick(TickEvent.Post e) {
        if (mc == null || mc.world == null || mc.player == null) return;
        tick++;

        pollActionBarFromHud(); // record overlay text if any

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

            Track tr = tracks.computeIfAbsent(ent.getId(), id ->
                new Track(id, tick, ent.getX(), ent.getY(), ent.getZ())
            );
            tr.updateWorld(ent.getX(), ent.getY(), ent.getZ(), tick);

            // Scan DataTracker entries to find y-like numeric values
            scanDataTrackerForYLike((DisplayEntity) ent, tr);
        }

        // Drop vanished tracks
        tracks.keySet().retainAll(present);

        // Session detection: if >=2 candidates recently
        if (!sessionActive) {
            List<Track> recent = recent(spawnWindow.get());
            if (recent.size() >= 2) {
                sessionActive = true;
                sessionStartTick = tick;
                if (chatNotes.get()) info("Minigame? diagnostics started. Candidates: " + ids(recent));
                fileLog("detect", "session_start", "candidates=" + ids(recent), null);
            }
        }

        // Session logging
        if (sessionActive) {
            // Fish/Box likelihood by variance (dominant y-like signal over time)
            List<Track> list = new ArrayList<>(tracks.values());
            if (list.size() >= 2) {
                list.sort(Comparator.comparingDouble((Track t) -> -t.bestVar())); // descending variance
                Track likelyFish = list.get(0);
                Track likelyBox  = list.get(list.size() - 1);

                // Only log per N ticks to keep size sane
                if ((tick - sessionStartTick) % sampleEvery.get() == 0) {
                    for (Track t : list) {
                        YSource ys = t.bestY();
                        String details = String.format(Locale.ROOT,
                            "ent=%d world=(%.3f,%.3f,%.3f) d=(%.3f,%.3f,%.3f) spd=%.3f chosenY=%s y=%.5f dy=%.5f var=%.5f fishLikely=%s boxLikely=%s",
                            t.id, t.x, t.y, t.z, t.dx, t.dy, t.dz, t.speed,
                            ys == null ? "NA" : ys.key(),
                            ys == null || ys.last == null ? Double.NaN : ys.last,
                            ys == null || ys.lastDelta == null ? Double.NaN : ys.lastDelta,
                            ys == null ? Double.NaN : ys.var(),
                            (t.id == likelyFish.id), (t.id == likelyBox.id)
                        );
                        fileLog("sample", "entity", details, null);
                    }
                }
            }

            // Stop session after cap to avoid huge logs
            if (sessionStartTick > 0 && (tick - sessionStartTick) >= maxTicksPerSession.get()) {
                if (chatNotes.get()) info("Diagnostics: session capped; resetting.");
                fileLog("detect", "session_cap", "", null);
                resetSession();
            }
        }

        // End session if all gone
        if (sessionActive && tracks.isEmpty()) {
            fileLog("detect", "entities_gone", "", null);
            resetSession();
        }
    }

    // ---- Packet sniffer ----
    @EventHandler
    private void onPacket(PacketEvent.Receive e) {
        if (!sniffPackets.get()) return;
        String cls = e.packet.getClass().getName();
        String txt = extractAnyText(e.packet);
        fileLog("packet", cls, txt == null ? "" : ("text=" + clean(txt)), null);
    }

    // ---- Helpers: Track & YSource ----
    private static class Track {
        final int id;
        final int firstSeenTick;
        int lastTick;

        // world motion
        double x, y, z;
        double px, py, pz; // previous
        double dx, dy, dz, speed;

        // per-entry sources keyed by (dataKeyId or identity)
        final Map<String, YSource> sources = new HashMap<>();

        Track(int id, int t0, double wx, double wy, double wz) {
            this.id = id; this.firstSeenTick = t0; this.lastTick = t0;
            this.x = this.px = wx; this.y = this.py = wy; this.z = this.pz = wz;
        }

        void updateWorld(double wx, double wy, double wz, int t) {
            this.lastTick = t;
            this.dx = wx - this.x; this.dy = wy - this.y; this.dz = wz - this.z;
            this.speed = Math.sqrt(dx*dx + dy*dy + dz*dz);
            this.px = this.x; this.py = this.y; this.pz = this.z;
            this.x = wx; this.y = wy; this.z = wz;
        }

        void addYSample(String key, double y) {
            YSource ys = sources.computeIfAbsent(key, k -> new YSource(k));
            ys.add(y);
        }

        YSource bestY() {
            YSource best = null;
            for (YSource s : sources.values()) {
                if (s.count < 2) continue;
                if (best == null || s.var() > best.var()) best = s;
            }
            return best;
        }

        double bestVar() {
            YSource b = bestY();
            return b == null ? 0.0 : b.var();
        }
    }

    // Online variance (Welford)
    private static class YSource {
        final String key; // data key id + value class
        long count = 0;
        double mean = 0.0;
        double m2 = 0.0;
        Double last = null;
        Double lastDelta = null;

        YSource(String key) { this.key = key; }

        void add(double v) {
            if (last != null) lastDelta = v - last;
            last = v;
            count++;
            double delta = v - mean;
            mean += delta / count;
            m2 += delta * (v - mean);
        }

        double var() { return count > 1 ? m2 / (count - 1) : 0.0; }

        String key() { return key; }
    }

    // ---- DataTracker scanning ----
    private void scanDataTrackerForYLike(DisplayEntity ent, Track tr) {
        try {
            Object dt = ent.getDataTracker();
            if (dt == null) return;
            Object entries = invokeAny(dt, "getAllEntries", "getEntries", "entries");
            if (!(entries instanceof Iterable<?>)) return;

            for (Object entry : (Iterable<?>) entries) {
                Object dataKey = invokeAny(entry, "getData", "getTrackedData", "data");
                Object value   = invokeAny(entry, "getValue", "value");
                String keyId = makeKeyId(dataKey, value);

                // Try to extract a y-like number from value or its translation vector
                Double vy = extractYLike(value);
                if (vy != null) tr.addYSample(keyId, vy);
            }
        } catch (Throwable ignored) {}
    }

    private String makeKeyId(Object dataKey, Object value) {
        String idPart = "";
        if (dataKey != null) {
            // Try getId(); else identity hash
            Object idObj = invokeAny(dataKey, "getId", "id");
            if (idObj instanceof Number) idPart = "id=" + ((Number) idObj).intValue();
            else idPart = "key@" + System.identityHashCode(dataKey);
        }
        String valPart = value == null ? "null" : value.getClass().getSimpleName();
        return idPart + ":" + valPart;
    }

    private Double extractYLike(Object value) {
        if (value == null) return null;

        // If it's already a vector-like or exposes y directly
        Double direct = readYComponent(value);
        if (direct != null) return direct;

        // Otherwise look for translation/position child
        Object vec = invokeAny(value, "getTranslation", "translation", "getPosition", "position");
        Double child = readYComponent(vec);
        if (child != null) return child;

        // Some values may provide components by index (JOML)
        try {
            Method m = value.getClass().getMethod("get", int.class);
            Object v = m.invoke(value, 1);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignored) {}

        return null;
    }

    private Double readYComponent(Object o) {
        if (o == null) return null;
        try {
            Object r = invokeAny(o, "y", "getY", "component1");
            if (r instanceof Number) return ((Number) r).doubleValue();
        } catch (Throwable ignored) {}
        try {
            Field f = o.getClass().getField("y"); // JOML Vector3f has public y
            Object v = f.get(o);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignored) {}
        return null;
    }

    // ---- Overlay / HUD ----
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

    // ---- Reflection helpers & packet text ----
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

    // ---- File logging ----
    private void setupWriter() {
        closeWriter();
        try {
            Path logsDir = FabricLoader.getInstance().getGameDir().resolve("logs");
            Files.createDirectories(logsDir);
            logPath = logsDir.resolve("meteor_minigame_diag.tsv");
            writer = Files.newBufferedWriter(logPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, // wipe each enable
                StandardOpenOption.WRITE);
            header();
            if (chatNotes.get()) info("Writing: " + logPath.getFileName());
        } catch (IOException e) {
            error("Failed to open log file: " + e.getMessage());
            writer = null;
        }
    }

    private void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {}
            writer = null;
        }
    }

    private void header() {
        writeLine("# Minigame Diagnostics — " + Instant.now());
        writeLine("ts\tick\tphase\tevent\tdetails");
    }

    private void writeLine(String s) {
        if (writer == null) return;
        try {
            writer.write(s);
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {}
    }

    private void fileLog(String phase, String event, String details, Object unused) {
        String line = String.format(Locale.ROOT, "%d\t%d\t%s\t%s\t%s",
            System.currentTimeMillis(), tick, phase, event, clean(details));
        writeLine(line);
    }

    private String clean(String s) { return s == null ? "" : s.replace('\t',' ').replace('\n',' '); }

    private static String ids(List<Track> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) { sb.append(list.get(i).id); if (i < list.size()-1) sb.append(", "); }
        return sb.toString();
    }

    private List<Track> recent(int window) {
        List<Track> out = new ArrayList<>();
        for (Track t : tracks.values()) if (tick - t.firstSeenTick <= window) out.add(t);
        return out;
    }

    private void resetSession() {
        sessionActive = false;
        sessionStartTick = -1;
        tracks.clear();
        if (chatNotes.get()) info("Diagnostics: session reset.");
    }
}
