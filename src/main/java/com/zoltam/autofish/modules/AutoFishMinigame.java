package com.zoltam.autofish.modules; // <-- match your package

import com.zoltam.autofish.AddonTemplate;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * AutoFishMinigame
 * - Robustly detects your own FishingBobberEntity on 1.21.4 (no more instant re-cast spam)
 * - Adds a tick-based "cast resolution" deadline so a fresh cast waits long enough for the bobber to spawn
 * - Keeps your original minigame classifier + training logic intact
 * - NEW: Settings to control the ranges for reeling delay (after bite) and cooldown before recast
 */
public class AutoFishMinigame extends Module {
    private final SettingGroup sgGeneral  = settings.createGroup("General");
    private final SettingGroup sgLog      = settings.createGroup("Chat Log");
    private final SettingGroup sgVisual   = settings.createGroup("Visual");
    private final SettingGroup sgTraining = settings.createGroup("Training");

    private final Random rng = new Random();

    // ---- General ----
    private final Setting<Double> radius = sgGeneral.add(new DoubleSetting.Builder()
        .name("radius").defaultValue(14.0).min(4).sliderRange(4, 48).build());

    // Auto loop & humanized delays (simple, small jitter)
    private final Setting<Boolean> autoLoop = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-loop").description("Automatically cast, wait for bite, reel, play minigame, and repeat.")
        .defaultValue(true).build());
    private final Setting<Boolean> humanizeDelays = sgGeneral.add(new BoolSetting.Builder()
        .name("humanize-delays").description("Add small human-like random delays to cast and reel.")
        .defaultValue(true).build());

    // NEW: User-configurable delay ranges
    private final Setting<Integer> reelDelayMin = sgGeneral.add(new IntSetting.Builder()
        .name("reel-delay-min-ms")
        .description("Minimum delay after a bite before reeling in (ms). Used when humanize-delays is on.")
        .defaultValue(110).min(0).sliderRange(0, 1500).build());
    private final Setting<Integer> reelDelayMax = sgGeneral.add(new IntSetting.Builder()
        .name("reel-delay-max-ms")
        .description("Maximum delay after a bite before reeling in (ms). Used when humanize-delays is on.")
        .defaultValue(360).min(0).sliderRange(0, 1500).build());
    private final Setting<Integer> recastCooldownMin = sgGeneral.add(new IntSetting.Builder()
        .name("recast-cooldown-min-ms")
        .description("Minimum cooldown before auto recasting (ms). Used when humanize-delays is on.")
        .defaultValue(250).min(0).sliderRange(0, 3000).build());
    private final Setting<Integer> recastCooldownMax = sgGeneral.add(new IntSetting.Builder()
        .name("recast-cooldown-max-ms")
        .description("Maximum cooldown before auto recasting (ms). Used when humanize-delays is on.")
        .defaultValue(700).min(0).sliderRange(0, 3000).build());

    // ---- Chat Log ----
    private final Setting<Boolean> chatLog = sgLog.add(new BoolSetting.Builder()
        .name("chat-log").defaultValue(true).build());
    private final Setting<Integer> logEvery = sgLog.add(new IntSetting.Builder()
        .name("chat-log-every-n-ticks").defaultValue(5).min(1).sliderRange(1, 40).build());

    // ---- Visual ----
    private final Setting<Boolean> showVisuals = sgVisual.add(new BoolSetting.Builder()
        .name("show-visuals").description("Show visual indicators for fish and box locations").defaultValue(true).build());
    private final Setting<SettingColor> fishColor = sgVisual.add(new ColorSetting.Builder()
        .name("fish-color").description("Color for fish indicator").defaultValue(new Color(0, 255, 0, 150)).build());
    private final Setting<SettingColor> boxColor = sgVisual.add(new ColorSetting.Builder()
        .name("box-color").description("Color for box indicator").defaultValue(new Color(255, 0, 0, 150)).build());
    private final Setting<SettingColor> unclassifiedColor = sgVisual.add(new ColorSetting.Builder()
        .name("unclassified-color").description("Color for unclassified entities").defaultValue(new Color(255, 255, 0, 100)).build());
    private final Setting<Double> indicatorSize = sgVisual.add(new DoubleSetting.Builder()
        .name("indicator-size").description("Size of the visual indicators").defaultValue(0.5).min(0.1).max(2.0).sliderRange(0.1, 2.0).build());

    // ---- Training / Model selection ----
    private final Setting<Boolean> useDefaultModel = sgTraining.add(new BoolSetting.Builder()
        .name("use-default-model")
        .description("Use the built-in default model. Disable to train and use your own model from this instance’s config/autofish folder.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> trainingMode = sgTraining.add(new BoolSetting.Builder()
        .name("training-mode")
        .description("Enable training mode to collect manual input data and save a model (only when default model is disabled).")
        .defaultValue(false)
        .visible(() -> !useDefaultModel.get())
        .build());

    // Hidden constants
    private static final int SPAWN_WINDOW = 16;
    private static final int CLASSIFY_MIN = 6;
    private static final int CLASSIFY_FALLBACK_EXTRA = 8;
    private static final double BOX_STILL_LOCAL_RANGE = 0.02;
    private static final double FISH_MOVE_LOCAL_RANGE = 0.12;
    private static final double BOX_STILL_WORLD_RANGE = 0.05;
    private static final double FISH_MOVE_WORLD_RANGE = 0.18;
    private static final double ERR_HI = 0.15;
    private static final double ERR_LO = 0.05;
    private static final int MIN_PRESS = 4;
    private static final int MIN_RELEASE = 3;
    private static final int JITTER_MS = 30;
    private static final boolean INVERT_ERROR = false;
    private static final boolean USE_KEYBIND = true;
    private static final double PREDICTION_WEIGHT = 0.6;
    private static final double SMOOTHING_FACTOR = 0.3;
    private static final double MOMENTUM_THRESHOLD = 0.03;
    private static final int HISTORY_SIZE = 5;

    // Auto loop timing (defaults preserved for settings above)
    private static final int CAST_DELAY_MIN_MS = 120, CAST_DELAY_MAX_MS = 380; // still internal "cast jitter"
    private static final int REEL_DELAY_MIN_MS = 110, REEL_DELAY_MAX_MS = 360; // defaults -> settings
    private static final int POST_COOLDOWN_MIN_MS = 250, POST_COOLDOWN_MAX_MS = 700; // defaults -> settings
    private static final int FAIL_RETRY_DELAY_MS = 500;

    // Bite detection heuristics
    private static final double BITE_VEL_DOWN_THR = -0.14;
    private static final double BITE_DROP_THR = 0.20;
    private static final int   BITE_WINDOW_TICKS = 3;
    private static final int   BITE_MIN_TICKS_AFTER_CAST = 10;

    // Give bobber time to spawn after cast to avoid instant re-cast loops
    private static final int CAST_SPAWN_GRACE_TICKS = 12; // ~0.6s
    private static final int CAST_RESOLVE_DEADLINE_EXTRA_TICKS = 28; // total ~2s before we declare cast failed

    // Default built-in Logistic model
    private static LogisticRegressionModel DEFAULT_MODEL = new LogisticRegressionModel(
        new double[]{2.941484, 10.936916, -20.971402},
        -0.375856,
        0.7041420118343196
    );

    // Arm bite detection only after this many ticks post-cast (~0.9s at 20tps)
    private static final int BITE_ARM_TICKS = 40;
    private static final int NO_BITE_TIMEOUT_TICKS = 60 * 20; // 60 seconds at 20 tps

    // runtime
    private int biteArmedAtTick = -1;

    // ---- Runtime: tracking/ML state ----
    private static class Track {
        final int id; final int firstSeenTick;
        int lastSeenTick;
        double lastWorldY, minWorldY, maxWorldY;
        Double lastLocalY = null;
        double minLocalY = Double.POSITIVE_INFINITY, maxLocalY = Double.NEGATIVE_INFINITY;

        Track(int id, double worldY, Double localY, int t) {
            this.id = id; this.firstSeenTick = t; this.lastSeenTick = t;
            this.lastWorldY = worldY;
            this.minWorldY = worldY; this.maxWorldY = worldY;
            updateLocal(localY);
        }
        void update(double worldY, Double localY, int t) {
            lastSeenTick = t;
            lastWorldY = worldY;
            if (worldY < minWorldY) minWorldY = worldY;
            if (worldY > maxWorldY) maxWorldY = worldY;
            updateLocal(localY);
        }
        void updateLocal(Double y) {
            if (y == null) return;
            lastLocalY = y;
            if (y < minLocalY) minLocalY = y;
            if (y > maxLocalY) maxLocalY = y;
        }
        double localRange()  { return (lastLocalY == null) ? 0.0 : (maxLocalY - minLocalY); }
        double worldRange()  { return maxWorldY - minWorldY; }
        double effectiveRange() {
            double lr = localRange();
            if (lastLocalY != null && lr > 1e-5) return lr;
            return worldRange();
        }
        boolean hasLocal() { return lastLocalY != null; }
    }

    private enum Phase { IDLE, CASTING, WAIT_BITE, REELING, MINIGAME, COOLDOWN }

    private Phase phase = Phase.IDLE;
    private long nextActionAtMs = 0L;
    private int lastCastTick = -10000;
    private int castResolveDeadlineTick = -10000;
    private int lastReelTick = -10000;

    private Integer bobberId = null;
    private final Deque<Double> bobberYHist = new ArrayDeque<>();
    private final Deque<Integer> bobberTickHist = new ArrayDeque<>();

    private int tick;
    private boolean sessionActive;
    private Integer boxId, fishId;
    private int sessionStartTick = -1;
    private final Map<Integer, Track> tracks = new HashMap<>();

    private boolean sneakDown = false;
    private int lastPressTick = -1000, lastReleaseTick = -1000;
    private long delayUntilMs = 0L;
    private String lastOverlaySeen = "";

    private final LinkedList<Double> fishPositionHistory = new LinkedList<>();
    private final LinkedList<Double> boxPositionHistory = new LinkedList<>();
    private final LinkedList<Integer> tickHistory = new LinkedList<>();
    private double smoothedError = 0.0;
    private double lastFishVelocity = 0.0;
    private double lastBoxVelocity = 0.0;

    private final List<TrainingDataPoint> trainingData = new ArrayList<>();
    private TrainedModel currentModel = null;
    private boolean wasTrainingMode = false;
    private boolean wasUsingDefaultModel = true;
    private int lastTrainingLogTick = 0;

    private static class TrainingDataPoint {
        final double diff;
        final double fishVel;
        final double boxVel;
        final int shiftState;

        TrainingDataPoint(double diff, double fishVel, double boxVel, int shiftState) {
            this.diff = diff; this.fishVel = fishVel; this.boxVel = boxVel; this.shiftState = shiftState;
        }
        String toCsvRow() {
            return String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%d", diff, fishVel, boxVel, shiftState);
        }
    }

    private static abstract class TrainedModel {
        abstract boolean predict(double diff, double fishVel, double boxVel);
        abstract String getModelType();
        abstract double getAccuracy();
    }

    private static class LogisticRegressionModel extends TrainedModel {
        private final double[] weights;
        private final double bias;
        private final double accuracy;

        LogisticRegressionModel(double[] weights, double bias, double accuracy) {
            this.weights = weights; this.bias = bias; this.accuracy = accuracy;
        }
        @Override boolean predict(double diff, double fishVel, double boxVel) {
            double z = bias + weights[0]*diff + weights[1]*fishVel + weights[2]*boxVel;
            return 1.0 / (1.0 + Math.exp(-z)) > 0.5;
        }
        @Override String getModelType() { return "Logistic"; }
        @Override double getAccuracy() { return accuracy; }
    }

    private static class DecisionTreeModel extends TrainedModel {
        private final double diffThreshold;
        private final double fishVelThreshold;
        private final double accuracy;
        DecisionTreeModel(double diffThreshold, double fishVelThreshold, double accuracy) {
            this.diffThreshold = diffThreshold; this.fishVelThreshold = fishVelThreshold; this.accuracy = accuracy;
        }
        @Override boolean predict(double diff, double fishVel, double boxVel) {
            return diff > diffThreshold && fishVel > fishVelThreshold;
        }
        @Override String getModelType() { return "DecisionTree"; }
        @Override double getAccuracy() { return accuracy; }
    }

    public AutoFishMinigame() {
        super(AddonTemplate.CATEGORY, "auto-fish-minigame",
            "Classifies & controls the fishing minigame and now handles full cast→bite→reel→minigame loop with small human-like delays.");
    }

    @Override public void onActivate() {
        tick = 0; sessionActive = false; sessionStartTick = -1; boxId = fishId = null; tracks.clear(); releaseSneak(true);

        biteArmedAtTick = -1;

        fishPositionHistory.clear();
        boxPositionHistory.clear();
        tickHistory.clear();
        smoothedError = 0.0;
        lastFishVelocity = 0.0;
        lastBoxVelocity = 0.0;

        phase = Phase.IDLE; nextActionAtMs = 0L;
        bobberId = null; bobberYHist.clear(); bobberTickHist.clear();
        lastCastTick = -10000;
        castResolveDeadlineTick = -10000;

        wasUsingDefaultModel = useDefaultModel.get();
        if (useDefaultModel.get()) {
            currentModel = DEFAULT_MODEL;
            if (chatLog.get()) info("Using built-in default model (Logistic, accuracy ~70.4 pct).");
            trainingData.clear();
        } else {
            loadModelFromDisk();
            loadTrainingDataFromCsv();
        }
        if (chatLog.get()) info("Watching for minigame…");
    }

    @Override public void onDeactivate() {
        releaseSneak(true);
        tracks.clear();
        sessionActive = false;
        boxId = fishId = null;

        biteArmedAtTick = -1;

        fishPositionHistory.clear();
        boxPositionHistory.clear();
        tickHistory.clear();
        smoothedError = 0.0;
        lastFishVelocity = 0.0;
        lastBoxVelocity = 0.0;

        phase = Phase.IDLE; nextActionAtMs = 0L;
        bobberId = null; bobberYHist.clear(); bobberTickHist.clear();
        lastCastTick = -10000;
        castResolveDeadlineTick = -10000;

        try { mc.options.useKey.setPressed(false); } catch (Throwable ignored) {}
    }

    // -------- TICK --------
    @EventHandler
    private void onTick(TickEvent.Post e) {
        if (mc == null || mc.world == null || mc.player == null) return;
        tick++;

        if (useDefaultModel.get() != wasUsingDefaultModel) {
            wasUsingDefaultModel = useDefaultModel.get();
            trainingMode.set(false);
            if (useDefaultModel.get()) {
                currentModel = DEFAULT_MODEL;
                if (chatLog.get()) info("Switched to built-in default model.");
            } else {
                currentModel = null;
                loadModelFromDisk();
                loadTrainingDataFromCsv();
                if (chatLog.get()) info("Default model disabled. Using saved/trained model if available.");
            }
        }

        pollActionBarFromHud();

        if (autoLoop.get()) runLoopFsm();

        final double r2 = radius.get() * radius.get();
        Set<Integer> present = new HashSet<>();
        for (Entity ent : mc.world.getEntities()) {
            if (!(ent instanceof DisplayEntity.ItemDisplayEntity)) continue;
            double dx = ent.getX() - mc.player.getX();
            double dy = ent.getY() - mc.player.getY();
            double dz = ent.getZ() - mc.player.getZ();
            if (dx*dx + dy*dy + dz*dz > r2) continue;

            Double localY = getLocalYFromDataTracker((DisplayEntity) ent);
            present.add(ent.getId());
            tracks.compute(ent.getId(), (id, tr) -> {
                if (tr == null) return new Track(id, ent.getY(), localY, tick);
                tr.update(ent.getY(), localY, tick);
                return tr;
            });
        }
        tracks.keySet().retainAll(present);

        if (!sessionActive) {
            List<Track> recent = recent(SPAWN_WINDOW);
            if (recent.size() >= 2) {
                sessionActive = true; boxId = fishId = null; sessionStartTick = tick;
                if (chatLog.get()) info("Minigame detected. Classifying…");
                phase = Phase.MINIGAME;
            }
        }

        if (sessionActive && boxId == null && fishId == null) fastClassify();

        if (!useDefaultModel.get() && (trainingMode.get() != wasTrainingMode)) {
            if (!trainingMode.get() && wasTrainingMode) {
                trainModelFromData();
            } else if (trainingMode.get() && !wasTrainingMode) {
                trainingData.clear();
                lastTrainingLogTick = 0;
                if (chatLog.get()) info("Training mode enabled. Manual control active - collecting data...");
            }
            wasTrainingMode = trainingMode.get();
        }

        if (sessionActive && boxId != null && fishId != null) {
            Track box = tracks.get(boxId), fish = tracks.get(fishId);
            if (box == null || fish == null) { stopCycle(); return; }

            Double bL = box.lastLocalY, fL = fish.lastLocalY;
            boolean usedLocal = (bL != null && fL != null);

            double currentFishPos = usedLocal ? fL : fish.lastWorldY;
            double currentBoxPos  = usedLocal ? bL : box.lastWorldY;

            updatePositionHistory(currentFishPos, currentBoxPos);

            double fishVelocity = calculateVelocity(fishPositionHistory);
            double boxVelocity  = calculateVelocity(boxPositionHistory);

            double diff = INVERT_ERROR ? (currentBoxPos - currentFishPos) : (currentFishPos - currentBoxPos);

            if (!useDefaultModel.get() && trainingMode.get()) {
                collectTrainingData(diff, fishVelocity, boxVelocity);
            } else {
                controlWithModelOrFallback(diff, fishVelocity, boxVelocity, currentFishPos, currentBoxPos);
            }

            if (chatLog.get() && tick % logEvery.get() == 0) {
                String mode = (!useDefaultModel.get() && trainingMode.get()) ? "TRAINING"
                    : (currentModel != null ? "MODEL" : "PD");
                if (usedLocal) {
                    info("%s", String.format(Locale.ROOT, "[%s] L: fish=%.3f(v=%.3f) box=%.3f(v=%.3f) diff=%.3f | sneak=%s",
                        mode, fL, fishVelocity, bL, boxVelocity, diff, sneakDown));
                } else {
                    info("%s", String.format(Locale.ROOT, "[%s] W: fish=%.3f(v=%.3f) box=%.3f(v=%.3f) diff=%.3f | sneak=%s",
                        mode, fish.lastWorldY, fishVelocity, box.lastWorldY, boxVelocity, diff, sneakDown));
                }
            }
        }

        if (sessionActive && tracks.isEmpty()) stopCycle();

        try { mc.options.useKey.setPressed(false); } catch (Throwable ignored) {}
    }

    // -------- WHOLE-LOOP FSM --------
    private void runLoopFsm() {
        long now = System.currentTimeMillis();
        if (now < nextActionAtMs) return;

        if (phase != Phase.MINIGAME) trackOwnBobber();

        switch (phase) {
            case IDLE -> {
                if (sessionActive) { phase = Phase.MINIGAME; break; }

                // If we already have a bobber out, just wait for bite
                if (hasBobberOut()) {
                    phase = Phase.WAIT_BITE;
                    break;
                }

                if (ensureRodEquipped()) {
                    phase = Phase.CASTING;
                    pressUseWithDelay(true); // cast
                    lastCastTick = tick;
                    biteArmedAtTick = tick + BITE_ARM_TICKS;   // << disarm bite detection until this tick
                    castResolveDeadlineTick = lastCastTick + CAST_SPAWN_GRACE_TICKS + CAST_RESOLVE_DEADLINE_EXTRA_TICKS;
                    scheduleNext(humanizeDelays.get() ? rnd(CAST_DELAY_MIN_MS, CAST_DELAY_MAX_MS) : 0);
                    if (chatLog.get()) info("Casting fishing rod.");
                    phase = Phase.WAIT_BITE;
                } else {
                    scheduleNext(750);
                }
            }
            case CASTING -> {
                // Immediately move to wait-for-bite
                phase = Phase.WAIT_BITE;
            }
            case WAIT_BITE -> {
                if (sessionActive) { phase = Phase.MINIGAME; break; }

                // Always give bobber time to spawn after the cast before deciding it failed
                if (!hasBobberOut()) {
                    if (tick <= castResolveDeadlineTick) {
                        // keep waiting a bit more
                        scheduleNext(60);
                        break;
                    }
                    // Past deadline: treat as failed cast and retry
                    phase = Phase.IDLE;
                    scheduleNext(FAIL_RETRY_DELAY_MS);
                    break;
                }

                if (lastCastTick > 0 && (tick - lastCastTick) >= NO_BITE_TIMEOUT_TICKS) {
                    if (chatLog.get()) info("No bite for 60 seconds, recasting.");
                    pressUseWithDelay(true);
                    lastReelTick = tick;
                    phase = Phase.REELING;
                    scheduleNext(120);
                    break;
                }

                boolean bite = detectBite();
                if (bite && (tick - lastCastTick) >= BITE_MIN_TICKS_AFTER_CAST) {
                    phase = Phase.REELING;
                    int delay = humanizeDelays.get() ? rndSetting(reelDelayMin, reelDelayMax) : 0;
                    scheduleNext(delay);
                    if (chatLog.get()) info("Bite detected! Reeling in (%d ms).", delay);
                    pressUseWithDelay(true); // reel
                    lastReelTick = tick;
                    scheduleNext(120); // small grace
                } else {
                    scheduleNext(60);
                }
            }
            case REELING -> {
                if (!sessionActive) {
                    // If the bobber is gone we finished reeling, go back to idle and allow cooldown
                    if (!hasBobberOut()) {
                        phase = Phase.IDLE;
                        scheduleNext(FAIL_RETRY_DELAY_MS);
                    } else {
                        // Still out -> keep waiting for bite
                        phase = Phase.WAIT_BITE;
                        scheduleNext(60);
                    }
                } else {
                    phase = Phase.MINIGAME;
                }
            }
            case MINIGAME -> { /* minigame tick handled elsewhere */ }
            case COOLDOWN -> {
                phase = Phase.IDLE;
            }
        }
    }

    private void scheduleNext(int ms) {
        nextActionAtMs = System.currentTimeMillis() + Math.max(0, ms);
    }

    private int rnd(int a, int b) { return (b <= a) ? a : a + rng.nextInt(b - a + 1); }

    // NEW: helper to draw from a (min,max) setting pair safely
    private int rndSetting(Setting<Integer> min, Setting<Integer> max) {
        int a = min.get();
        int b = max.get();
        if (b < a) { int t = a; a = b; b = t; }
        return rnd(a, b);
    }

    // === perform a REAL item use instead of key-toggle ===
    private void pressUseWithDelay(boolean clickNow) {
        if (!clickNow) return;
        doUseItemOnce();
    }

    private void doUseItemOnce() {
        if (mc == null || mc.player == null) return;

        Hand hand = getRodHand();
        if (hand == null) {
            safeRightClickFallback();
            return;
        }

        try {
            if (mc.interactionManager != null) {
                try {
                    // 1.21.x signature
                    mc.interactionManager.interactItem(mc.player, hand);
                    return;
                } catch (Throwable ignore) {
                    // Fallback to reflective call in case of mappings difference
                    try {
                        Method m = mc.interactionManager.getClass()
                            .getMethod("useItem", net.minecraft.entity.player.PlayerEntity.class, Hand.class);
                        m.setAccessible(true);
                        m.invoke(mc.interactionManager, mc.player, hand);
                        return;
                    } catch (Throwable ignore2) {
                        // fall through
                    }
                }
            }
        } catch (Throwable ignore) {}

        safeRightClickFallback();
    }

    private void safeRightClickFallback() {
        try { Utils.rightClick(); } catch (Throwable ignored) {}
    }

    private Hand getRodHand() {
        try {
            Item main = mc.player.getMainHandStack().getItem();
            if (isFishingRodItem(main)) return Hand.MAIN_HAND;
            Item off  = mc.player.getOffHandStack().getItem();
            if (isFishingRodItem(off))  return Hand.OFF_HAND;
        } catch (Throwable ignored) {}
        return null;
    }
    // === end of casting fix ===

    private boolean ensureRodEquipped() {
        try {
            if (getRodHand() != null) return true; // already in hand

            // search hotbar for rod and select
            for (int slot = 0; slot < 9; slot++) {
                Item it = mc.player.getInventory().getStack(slot).getItem();
                if (isFishingRodItem(it)) {
                    mc.player.getInventory().selectedSlot = slot;
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isFishingRodItem(Item it) {
        if (it == null) return false;
        try { if (it == Items.FISHING_ROD) return true; } catch (Throwable ignored) {}
        String n = it.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        return n.contains("fishing") && n.contains("rod");
    }

    // --- Bobber tracking & bite detection ---
    private void trackOwnBobber() {
        Entity bob = getOwnBobber();
        if (bob == null) {
            bobberId = null; bobberYHist.clear(); bobberTickHist.clear(); return;
        }
        double y = bob.getY();
        bobberId = bob.getId();
        bobberYHist.addLast(y);
        bobberTickHist.addLast(tick);
        while (bobberYHist.size() > 6) { bobberYHist.removeFirst(); bobberTickHist.removeFirst(); }
    }

    private Entity getOwnBobber() {
        if (mc == null || mc.world == null || mc.player == null) return null;

        // Prefer the player's direct handle if available/mapped
        try {
            if (mc.player.fishHook != null) return mc.player.fishHook;
        } catch (Throwable ignored) { /* mappings changed in some versions */ }

        // Otherwise, reliably scan world entities for FishingBobberEntity owned by our player
        FishingBobberEntity mine = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof FishingBobberEntity f)) continue;

            PlayerEntity owner;
            try {
                owner = f.getPlayerOwner(); // 1.21.x
            } catch (Throwable t) {
                // Defensive: fall back to reflective getOwner()
                owner = null;
                try {
                    Object o = invokeAny(f, "getOwner");
                    if (o instanceof PlayerEntity p) owner = p;
                } catch (Throwable ignored2) {}
            }

            if (owner == mc.player) {
                double d = e.squaredDistanceTo(mc.player);
                if (d < bestDist) { bestDist = d; mine = f; }
            }
        }

        // If we already had an id, prefer it if it still exists
        if (bobberId != null && mine == null) {
            for (Entity e : mc.world.getEntities()) {
                if (e.getId() == bobberId && e instanceof FishingBobberEntity) return e;
            }
        }
        return mine;
    }

    private boolean hasBobberOut() {
        // Use robust detection only
        return getOwnBobber() != null;
    }

    private boolean detectBite() {
        if (tick < biteArmedAtTick) return false;        // still disarmed right after cast
        if (bobberYHist.size() < 2) return false;

        // Only ignore when clearly still in-flight. If either condition says "on/near water or calm", allow detection.
        boolean ready = isBobberInWater() || bobberLooksSettled();
        if (!ready) return false;

        Double[] ys = bobberYHist.toArray(new Double[0]);
        int n = ys.length;

        // Downward tug
        double vy = ys[n - 1] - ys[n - 2];
        if (vy < BITE_VEL_DOWN_THR) return true;

        // Sudden drop over a short window
        double maxY = ys[n - 1], minY = ys[n - 1];
        int start = Math.max(0, n - BITE_WINDOW_TICKS);
        for (int i = start; i < n; i++) {
            if (ys[i] > maxY) maxY = ys[i];
            if (ys[i] < minY) minY = ys[i];
        }
        return (maxY - minY) > BITE_DROP_THR && ys[n - 1] < ys[n - 2];
    }


    /** True if the bobber reports BOBBING state, or (fallback) looks “settled” on water by velocity. */
    /** Treat as "bobbing" when the bobber is in water and has settled (low recent vertical motion). */
    /** Treat as "ready to bite-detect" when either in water OR looks settled (not in-flight). */
    private boolean isBobberBobbingNow() {
        // Loosened from (inWater && settled) -> (inWater || settled)
        return isBobberInWater() || bobberLooksSettled();
    }


    /** True if the bobber is in water by fluid tag or the vanilla touch check. */
    private boolean isBobberInWater() {
        Entity e = getOwnBobber();
        if (!(e instanceof net.minecraft.entity.projectile.FishingBobberEntity f)) return false;

        // Primary
        try {
            if (f.isTouchingWater()) return true;
        } catch (Throwable ignored) {}

        // Fallback: fluid tag at bobber block
        try {
            var pos = net.minecraft.util.math.BlockPos.ofFloored(f.getX(), f.getY(), f.getZ());
            var fs  = mc.world.getFluidState(pos);
            return fs != null && fs.isIn(net.minecraft.registry.tag.FluidTags.WATER);
        } catch (Throwable ignored) {}

        return false;
    }


    /** Heuristic: last few ticks show small vertical change => in water, not in-flight. */
    private boolean bobberLooksSettled() {
        if (bobberYHist.size() < 4) return false;
        double[] y = bobberYHist.stream().mapToDouble(d -> d).toArray();
        int n = y.length;

        // small absolute velocities over last 3 steps
        double v1 = Math.abs(y[n-1] - y[n-2]);
        double v2 = Math.abs(y[n-2] - y[n-3]);
        double v3 = Math.abs(y[n-3] - y[n-4]);

        // and low short-window range (not plummeting)
        double min = Math.min(Math.min(y[n-1], y[n-2]), Math.min(y[n-3], y[n-4]));
        double max = Math.max(Math.max(y[n-1], y[n-2]), Math.max(y[n-3], y[n-4]));
        double range = max - min;

        return v1 < 0.06 && v2 < 0.06 && v3 < 0.06 && range < 0.12;
    }

    // -------- 3D RENDERING --------
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!showVisuals.get() || mc == null || mc.world == null || mc.player == null) return;
        if (tracks.isEmpty()) return;

        double size = indicatorSize.get();

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof DisplayEntity.ItemDisplayEntity)) continue;

            Track track = tracks.get(entity.getId());
            if (track == null) continue;

            Vec3d pos = entity.getPos();
            SettingColor color;

            if (boxId != null && track.id == boxId) color = boxColor.get();
            else if (fishId != null && track.id == fishId) color = fishColor.get();
            else color = unclassifiedColor.get();

            event.renderer.box(
                pos.x - size/2, pos.y - size/2, pos.z - size/2,
                pos.x + size/2, pos.y + size/2, pos.z + size/2,
                color, color, ShapeMode.Both, 0
            );

            event.renderer.line(
                pos.x, pos.y - size, pos.z,
                pos.x, pos.y + size * 2, pos.z,
                color
            );
        }
    }

    // -------- PACKETS (fallback action-bar & sound hook) --------
    @EventHandler
    private void onPacket(PacketEvent.Receive e) {
        try {
            String cls = e.packet.getClass().getSimpleName();
            if (cls.contains("PlaySound")) {
                Object sound = invokeAny(e.packet, "getSound", "getEvent", "getSoundEvent");
                String sid = null;
                if (sound != null) {
                    Object value = invokeAny(sound, "value", "getValue");
                    Object id = value != null ? invokeAny(value, "getId") : invokeAny(sound, "getId");
                    if (id != null) sid = id.toString().toLowerCase(Locale.ROOT);
                    else sid = sound.toString().toLowerCase(Locale.ROOT);
                }
                if (sid != null && sid.contains("fishing") && sid.contains("splash")) {
                    if (phase == Phase.WAIT_BITE
                        && tick >= biteArmedAtTick
                        && (tick - lastCastTick) >= BITE_MIN_TICKS_AFTER_CAST)  // keep this
                    {
                        int delay = humanizeDelays.get() ? rndSetting(reelDelayMin, reelDelayMax) : 0;
                        scheduleNext(delay);
                        pressUseWithDelay(true);    // reel now (we log the humanized "intent")
                        lastReelTick = tick;
                        if (chatLog.get()) info("Splash sound -> reeling (%d ms).", delay);
                        phase = Phase.REELING;
                    }
                }


            }
        } catch (Throwable ignored) {}

        String overlay = tryExtractActionBar(e.packet);
        if (overlay == null || overlay.isEmpty()) return;

        if (!overlay.equals(lastOverlaySeen)) {
            lastOverlaySeen = overlay;
            String low = overlay.toLowerCase(Locale.ROOT);
            if (low.contains("caught") || low.contains("failed")) {
                if (chatLog.get()) info("Overlay: " + overlay);
                stopCycle();
            }
        }
    }

    private String tryExtractActionBar(Object packet) {
        if (packet == null) return null;
        String name = packet.getClass().getSimpleName();
        try {
            if (name.equals("OverlayMessageS2CPacket")) {
                Text content = (Text) invokeAny(packet, "content","getContent","text","getText","message","getMessage");
                return content != null ? content.getString() : null;
            }
            if (name.equals("GameMessageS2CPacket")) {
                Boolean overlay = (Boolean) invokeAny(packet, "isOverlay","overlay");
                if (overlay != null && overlay) {
                    Text content = (Text) invokeAny(packet, "content","getContent","text","getText","message","getMessage");
                    return content != null ? content.getString() : null;
                }
                return null;
            }
            if (name.equals("TitleS2CPacket")) {
                Text content = (Text) invokeAny(packet, "text","getText","content","getContent","title");
                return content != null ? content.getString() : null;
            }
        } catch (Throwable ignored) {}
        return null;
    }

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
                String low = s.toLowerCase(Locale.ROOT);
                if (low.contains("caught") || low.contains("failed")) {
                    if (chatLog.get()) info("Overlay(HUD): " + s);
                    stopCycle();
                }
            }
        } catch (Throwable ignored) {}
    }

    // -------- classify helpers --------
    private void fastClassify() {
        List<Track> live = new ArrayList<>(tracks.values());
        if (live.size() < 4) return;

        int earliest = live.stream().mapToInt(t -> t.firstSeenTick).min().orElse(tick);
        int observed = tick - earliest;
        if (observed < CLASSIFY_MIN) return;

        live.sort(Comparator.comparingDouble(t -> t.minWorldY));

        List<Track> fishAndBoxCandidates = new ArrayList<>();
        if (live.size() >= 4) {
            fishAndBoxCandidates.add(live.get(0));
            fishAndBoxCandidates.add(live.get(1));
        } else if (live.size() >= 2) {
            fishAndBoxCandidates.addAll(live);
        }

        if (fishAndBoxCandidates.size() < 2) return;

        Track candFish = null;
        Track candBox = null;

        for (Track candidate : fishAndBoxCandidates) {
            boolean hasMovement = candidate.hasLocal()
                ? candidate.localRange() >= FISH_MOVE_LOCAL_RANGE
                : candidate.worldRange() >= FISH_MOVE_WORLD_RANGE;

            if (hasMovement && candFish == null) candFish = candidate;
            else if (!hasMovement && candBox == null) candBox = candidate;
        }

        if (candFish == null || candBox == null) {
            fishAndBoxCandidates.sort(Comparator.comparingDouble(Track::effectiveRange));
            candBox = fishAndBoxCandidates.get(0);
            candFish = fishAndBoxCandidates.get(1);
        }

        if (candBox != null && candFish != null && candBox.id != candFish.id) {
            boxId = candBox.id;
            fishId = candFish.id;
            if (chatLog.get()) info("%s", String.format(Locale.ROOT,
                "Classified: BOX id=%d Y=%.3f range=%.3f | FISH id=%d Y=%.3f range=%.3f | (obs=%d)",
                boxId, candBox.minWorldY, candBox.effectiveRange(),
                fishId, candFish.minWorldY, candFish.effectiveRange(), observed));
        }
    }

    private List<Track> recent(int window) {
        List<Track> out = new ArrayList<>();
        for (Track t : tracks.values()) if (tick - t.firstSeenTick <= window) out.add(t);
        return out;
    }

    private void stopCycle() {
        if (chatLog.get()) info("Cycle end.");
        releaseSneak(true);
        sessionActive = false;
        boxId = fishId = null;
        tracks.clear();

        fishPositionHistory.clear();
        boxPositionHistory.clear();
        tickHistory.clear();
        smoothedError = 0.0;
        lastFishVelocity = 0.0;
        lastBoxVelocity = 0.0;

        phase = autoLoop.get() ? Phase.COOLDOWN : Phase.IDLE;
        if (autoLoop.get()) {
            int cd = humanizeDelays.get() ? rndSetting(recastCooldownMin, recastCooldownMax) : 0;
            scheduleNext(cd);
            if (chatLog.get()) info("Cooldown %d ms before next cast.", cd);
        }
    }

    private void updatePositionHistory(double fishPos, double boxPos) {
        fishPositionHistory.addLast(fishPos);
        boxPositionHistory.addLast(boxPos);
        tickHistory.addLast(tick);

        int maxSize = HISTORY_SIZE;
        while (fishPositionHistory.size() > maxSize) {
            fishPositionHistory.removeFirst();
            boxPositionHistory.removeFirst();
            tickHistory.removeFirst();
        }
    }

    private double calculateVelocity(LinkedList<Double> positionHistory) {
        if (positionHistory.size() < 2) return 0.0;
        int n = positionHistory.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            double x = i;
            double y = positionHistory.get(i);
            sumX += x; sumY += y; sumXY += x * y; sumX2 += x * x;
        }
        double denominator = n * sumX2 - sumX * sumX;
        if (Math.abs(denominator) < 1e-10) return 0.0;
        return (n * sumXY - sumX * sumY) / denominator;
    }

    // -------- Sneak I/O --------
    private void pressSneak() {
        if (sneakDown) return;
        sneakDown = true; lastPressTick = tick;
        if (USE_KEYBIND) {
            try { mc.options.sneakKey.setPressed(true); } catch (Throwable ignored) {}
        } else {
            sendSneakPacket(true);
        }
        if (chatLog.get()) info("sneak: PRESS");
    }

    private void releaseSneak(boolean force) {
        if (!sneakDown && !force) return;
        sneakDown = false; lastReleaseTick = tick;
        if (USE_KEYBIND) {
            try { mc.options.sneakKey.setPressed(false); } catch (Throwable ignored) {}
        } else {
            sendSneakPacket(false);
        }
        if (chatLog.get()) info("sneak: RELEASE");
    }

    private void sendSneakPacket(boolean press) {
        try {
            Class<?> pktCls  = Class.forName("net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket");
            Class<?> modeCls = Class.forName("net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket$Mode");

            Object chosenMode = null;
            for (Object c : modeCls.getEnumConstants()) {
                String n = c.toString();
                if (press && (n.equals("PRESS_SHIFT_KEY") || n.equals("PRESS_SNEAK_KEY"))) { chosenMode = c; break; }
                if (!press && (n.equals("RELEASE_SHIFT_KEY") || n.equals("RELEASE_SNEAK_KEY"))) { chosenMode = c; break; }
            }
            if (chosenMode == null) return;

            Object packet = pktCls.getConstructor(net.minecraft.entity.player.PlayerEntity.class, modeCls)
                .newInstance(mc.player, chosenMode);

            Object nh = mc.getNetworkHandler();
            if (nh == null) return;
            for (Method m : nh.getClass().getMethods()) {
                if (m.getName().equals("sendPacket") && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    m.invoke(nh, packet);
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    // -------- Local Y from DataTracker --------
    private Double getLocalYFromDataTracker(DisplayEntity display) {
        try {
            Object dt = display.getDataTracker();
            if (dt == null) return null;

            Object entries = invokeAny(dt, "getAllEntries", "getEntries", "entries");
            if (entries instanceof Iterable<?>) {
                for (Object entry : (Iterable<?>) entries) {
                    Object value = invokeAny(entry, "getValue", "value");
                    Double y = extractLocalYFromTransform(value);
                    if (y != null) return y;
                }
            }
        } catch (Throwable ignored) {}
        try {
            Object transform = invokeAny(display, "getTransformation", "transformation");
            Double y = extractLocalYFromTransform(transform);
            if (y != null) return y;
        } catch (Throwable ignored) {}
        return null;
    }

    private Double extractLocalYFromTransform(Object transform) {
        if (transform == null) return null;
        Double yDirect = readYComponent(transform);
        if (yDirect != null) return yDirect;
        Object vec = invokeAny(transform, "getTranslation", "translation", "getPosition", "position");
        return readYComponent(vec);
    }

    private Double readYComponent(Object o) {
        if (o == null) return null;
        try {
            Object r = invokeAny(o, "y", "getY", "component1");
            if (r instanceof Number) return ((Number) r).doubleValue();
        } catch (Throwable ignored) {}
        try {
            Field f = o.getClass().getField("y");
            Object v = f.get(o);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignored) {}
        try {
            Method m = o.getClass().getMethod("get", int.class);
            Object v = m.invoke(o, 1);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignored) {}
        return null;
    }

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

    // -------- Training Mode Methods --------
    private void collectTrainingData(double diff, double fishVel, double boxVel) {
        int shiftState = 0;
        try {
            if (mc.options.sneakKey.isPressed()) shiftState = 1;
        } catch (Throwable ignored) {
            if (mc.player != null && mc.player.isSneaking()) shiftState = 1;
        }
        trainingData.add(new TrainingDataPoint(diff, fishVel, boxVel, shiftState));

        if (trainingData.size() % 50 == 0 && tick > lastTrainingLogTick + 10) {
            lastTrainingLogTick = tick;
            if (chatLog.get()) info("%s", String.format(Locale.ROOT, "Training data collected: %d rows", trainingData.size()));
        }
    }

    private void trainModelFromData() {
        if (useDefaultModel.get()) {
            if (chatLog.get()) info("Default model is enabled; disable it to train your own model.");
            return;
        }

        if (trainingData.isEmpty()) {
            if (chatLog.get()) info("No training data available to train model.");
            return;
        }

        if (trainingData.size() < 10) {
            if (chatLog.get()) info("Insufficient training data (need at least 10 samples, have " + trainingData.size() + ").");
            return;
        }

        try {
            TrainedModel logisticModel = trainLogisticRegression();
            if (logisticModel != null && logisticModel.getAccuracy() > 0.6) {
                currentModel = logisticModel;
            } else {
                currentModel = trainDecisionTree();
            }

            saveModelToDisk();
            saveTrainingDataToCsv();

            if (chatLog.get()) {
                info("%s", String.format(Locale.ROOT, "Training complete: %d rows, model type=%s, accuracy=%.1f pct",
                    trainingData.size(), currentModel.getModelType(), currentModel.getAccuracy() * 100));
            }

        } catch (Exception e) {
            if (chatLog.get()) info("Training failed: " + e.getMessage());
            currentModel = null;
        }
    }

    private TrainedModel trainLogisticRegression() {
        double[] weights = {0.0, 0.0, 0.0}; // diff, fishVel, boxVel
        double bias = 0.0;
        double learningRate = 0.01;
        int epochs = 100;

        double[] diffValues = trainingData.stream().mapToDouble(d -> d.diff).toArray();
        double[] fishVelValues = trainingData.stream().mapToDouble(d -> d.fishVel).toArray();
        double[] boxVelValues  = trainingData.stream().mapToDouble(d -> d.boxVel).toArray();

        double diffMean = Arrays.stream(diffValues).average().orElse(0.0);
        double fishVelMean = Arrays.stream(fishVelValues).average().orElse(0.0);
        double boxVelMean  = Arrays.stream(boxVelValues).average().orElse(0.0);

        double diffStd = Math.sqrt(Arrays.stream(diffValues).map(x -> (x - diffMean) * (x - diffMean)).average().orElse(1.0));
        double fishVelStd = Math.sqrt(Arrays.stream(fishVelValues).map(x -> (x - fishVelMean) * (x - fishVelMean)).average().orElse(1.0));
        double boxVelStd  = Math.sqrt(Arrays.stream(boxVelValues).map(x -> (x - boxVelMean) * (x - boxVelMean)).average().orElse(1.0));

        if (diffStd < 1e-6) diffStd = 1.0;
        if (fishVelStd < 1e-6) fishVelStd = 1.0;
        if (boxVelStd  < 1e-6) boxVelStd  = 1.0;

        for (int epoch = 0; epoch < epochs; epoch++) {
            for (TrainingDataPoint point : trainingData) {
                double normDiff    = (point.diff    - diffMean)    / diffStd;
                double normFishVel = (point.fishVel - fishVelMean) / fishVelStd;
                double normBoxVel  = (point.boxVel  - boxVelMean)  / boxVelStd;

                double z = bias + weights[0]*normDiff + weights[1]*normFishVel + weights[2]*normBoxVel;
                double prediction = 1.0 / (1.0 + Math.exp(-z));
                double error = prediction - point.shiftState;

                bias       -= learningRate * error;
                weights[0] -= learningRate * error * normDiff;
                weights[1] -= learningRate * error * normFishVel;
                weights[2] -= learningRate * error * normBoxVel;
            }
        }

        int correct = 0;
        for (TrainingDataPoint point : trainingData) {
            double normDiff    = (point.diff    - diffMean)    / diffStd;
            double normFishVel = (point.fishVel - fishVelMean) / fishVelStd;
            double normBoxVel  = (point.boxVel  - boxVelMean)  / boxVelStd;

            double z = bias + weights[0]*normDiff + weights[1]*normFishVel + weights[2]*normBoxVel;
            boolean prediction = (1.0 / (1.0 + Math.exp(-z))) > 0.5;
            if ((prediction ? 1 : 0) == point.shiftState) correct++;
        }

        double accuracy = (double) correct / trainingData.size();

        double[] finalWeights = {
            weights[0] / diffStd,
            weights[1] / fishVelStd,
            weights[2] / boxVelStd
        };
        double finalBias = bias - weights[0] * diffMean / diffStd - weights[1] * fishVelMean / fishVelStd - weights[2] * boxVelMean / boxVelStd;

        return new LogisticRegressionModel(finalWeights, finalBias, accuracy);
    }

    private TrainedModel trainDecisionTree() {
        double bestDiffThreshold = 0.0;
        double bestFishVelThreshold = 0.0;
        double bestAccuracy = 0.0;

        double[] diffCandidates = trainingData.stream().mapToDouble(d -> d.diff).distinct().sorted().toArray();
        double[] fishVelCandidates = trainingData.stream().mapToDouble(d -> d.fishVel).distinct().sorted().toArray();

        for (double diffThresh : diffCandidates) {
            for (double fishVelThresh : fishVelCandidates) {
                int correct = 0;
                for (TrainingDataPoint point : trainingData) {
                    boolean prediction = point.diff > diffThresh && point.fishVel > fishVelThresh;
                    if ((prediction ? 1 : 0) == point.shiftState) correct++;
                }
                double accuracy = (double) correct / trainingData.size();
                if (accuracy > bestAccuracy) {
                    bestAccuracy = accuracy;
                    bestDiffThreshold = diffThresh;
                    bestFishVelThreshold = fishVelThresh;
                }
            }
        }
        return new DecisionTreeModel(bestDiffThreshold, bestFishVelThreshold, bestAccuracy);
    }

    private void controlWithModelOrFallback(double diff, double fishVel, double boxVel, double fishPos, double boxPos) {
        long now = System.currentTimeMillis();
        if (now < delayUntilMs) return;

        boolean shouldSneak = false;

        if (currentModel != null) {
            shouldSneak = currentModel.predict(diff, fishVel, boxVel);
            if (sessionStartTick == tick - 1 && chatLog.get()) info("Using trained model to control box.");
        } else {
            if (sessionStartTick == tick - 1 && chatLog.get()) info("No trained model available, falling back to PD controller.");
            double error = Math.abs(diff);
            if (!sneakDown) shouldSneak = error > ERR_HI && (tick - lastReleaseTick) >= MIN_RELEASE;
            else shouldSneak = !(error < ERR_LO && (tick - lastPressTick) >= MIN_PRESS);
        }

        if (shouldSneak && !sneakDown) {
            pressSneak();
            delayUntilMs = now + rng.nextInt(Math.max(1, JITTER_MS));
        } else if (!shouldSneak && sneakDown) {
            releaseSneak(false);
            delayUntilMs = now + rng.nextInt(Math.max(1, JITTER_MS));
        }
    }

    // ====== Storage helpers ======
    private Path getConfigDir() {
        return FabricLoader.getInstance().getGameDir().resolve("config").resolve("autofish");
    }
    private Path getModelFile() { return getConfigDir().resolve("auto_fish_model.json"); }
    private Path getCsvFile()   { return getConfigDir().resolve("auto_fish_training_data.csv"); }

    private void saveModelToDisk() {
        if (useDefaultModel.get()) return;
        try {
            Path configDir = getConfigDir();
            Files.createDirectories(configDir);
            Path modelFile = getModelFile();

            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"type\": \"").append(currentModel.getModelType()).append("\",\n");
            json.append("  \"accuracy\": ").append(currentModel.getAccuracy()).append(",\n");
            json.append("  \"trainingSize\": ").append(trainingData.size()).append(",\n");

            if (currentModel instanceof LogisticRegressionModel lr) {
                json.append("  \"weights\": [")
                    .append(String.format(Locale.ROOT, "%.6f, %.6f, %.6f", lr.weights[0], lr.weights[1], lr.weights[2]))
                    .append("],\n");
                json.append("  \"bias\": ").append(String.format(Locale.ROOT, "%.6f", lr.bias)).append("\n");
            } else if (currentModel instanceof DecisionTreeModel dt) {
                json.append("  \"diffThreshold\": ").append(String.format(Locale.ROOT, "%.6f", dt.diffThreshold)).append(",\n");
                json.append("  \"fishVelThreshold\": ").append(String.format(Locale.ROOT, "%.6f", dt.fishVelThreshold)).append("\n");
            }

            json.append("}\n");

            Files.writeString(modelFile, json.toString());
            if (chatLog.get()) info("Model saved to: " + modelFile);
        } catch (Exception e) {
            if (chatLog.get()) info("Failed to save model to disk: " + e.getMessage());
        }
    }

    private void saveTrainingDataToCsv() {
        if (useDefaultModel.get()) return;
        try {
            Path configDir = getConfigDir();
            Files.createDirectories(configDir);
            Path csvFile = getCsvFile();

            StringBuilder csv = new StringBuilder();
            csv.append("diff,fish_vel,box_vel,shift_state\n");
            for (TrainingDataPoint point : trainingData) csv.append(point.toCsvRow()).append("\n");

            Files.writeString(csvFile, csv.toString());
            if (chatLog.get()) info("Training data saved to: " + csvFile);
        } catch (Exception e) {
            if (chatLog.get()) info("Failed to save training data CSV: " + e.getMessage());
        }
    }

    private void loadModelFromDisk() {
        if (useDefaultModel.get()) return;
        try {
            Path modelFile = getModelFile();
            if (!Files.exists(modelFile)) {
                if (chatLog.get()) info("No saved model found at: " + modelFile);
                currentModel = null; return;
            }

            String content = Files.readString(modelFile);
            Map<String, String> json = parseSimpleJsonSafe(content);

            String type = json.get("type");
            double accuracy = parseDoubleSafe(json.get("accuracy"), 0.0);

            if ("Logistic".equals(type)) {
                String weightsStr = json.get("weights");
                String biasStr    = json.get("bias");
                double[] weights  = parseDoubleArray(weightsStr, 3);
                double bias       = parseDoubleSafe(biasStr, 0.0);
                currentModel      = new LogisticRegressionModel(weights, bias, accuracy);
            } else if ("DecisionTree".equals(type)) {
                double diffThreshold     = parseDoubleSafe(json.get("diffThreshold"), 0.0);
                double fishVelThreshold  = parseDoubleSafe(json.get("fishVelThreshold"), 0.0);
                currentModel             = new DecisionTreeModel(diffThreshold, fishVelThreshold, accuracy);
            } else currentModel = null;

            if (currentModel != null && chatLog.get()) info("Loaded model from: " + modelFile);
        } catch (Exception e) {
            if (chatLog.get()) info("Failed to load model from disk: " + e.getMessage());
            currentModel = null;
        }
    }

    private void loadTrainingDataFromCsv() {
        if (useDefaultModel.get()) return;
        try {
            Path csvFile = getCsvFile();
            if (!Files.exists(csvFile)) {
                if (chatLog.get()) info("No saved training data found at: " + csvFile);
                return;
            }

            List<String> lines = Files.readAllLines(csvFile);
            if (lines.size() <= 1) {
                if (chatLog.get()) info("Training data file is empty.");
                return;
            }

            trainingData.clear();
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(",");
                if (parts.length == 4) {
                    double diff     = parseDoubleSafe(parts[0], 0.0);
                    double fishVel  = parseDoubleSafe(parts[1], 0.0);
                    double boxVel   = parseDoubleSafe(parts[2], 0.0);
                    int shiftState  = parseIntSafe(parts[3], 0);
                    trainingData.add(new TrainingDataPoint(diff, fishVel, boxVel, shiftState));
                }
            }

            if (chatLog.get()) info("Loaded " + trainingData.size() + " training data points from: " + csvFile);
        } catch (Exception e) {
            if (chatLog.get()) info("Failed to load training data from CSV: " + e.getMessage());
            trainingData.clear();
        }
    }

    // -------- Robust mini JSON helpers --------
    private Map<String, String> parseSimpleJsonSafe(String json) {
        Map<String, String> out = new HashMap<>();
        if (json == null) return out;

        String s = json.trim();
        if (s.startsWith("{")) s = s.substring(1);
        if (s.endsWith("}")) s = s.substring(0, s.length() - 1);

        List<String> pairs = splitTopLevel(s, ',');
        for (String pair : pairs) {
            List<String> kv = splitTopLevel(pair, ':');
            if (kv.size() < 2) continue;
            String key = stripQuotes(kv.get(0).trim());
            String value = kv.get(1).trim();
            for (int i = 2; i < kv.size(); i++) value += ":" + kv.get(i);
            out.put(key, value.trim());
        }
        return out;
    }

    private List<String> splitTopLevel(String s, char sep) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depthBrace = 0, depthBracket = 0;
        boolean inQuotes = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inQuotes = !inQuotes;
            if (!inQuotes) {
                if (c == '{') depthBrace++;
                else if (c == '}') depthBrace--;
                else if (c == '[') depthBracket++;
                else if (c == ']') depthBracket--;
                else if (c == sep && depthBrace == 0 && depthBracket == 0) {
                    parts.add(cur.toString());
                    cur.setLength(0);
                    continue;
                }
            }
            cur.append(c);
        }
        parts.add(cur.toString());
        return parts;
    }

    private String stripQuotes(String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) return s.substring(1, s.length() - 1);
        return s;
    }

    private double parseDoubleSafe(String s, double def) {
        if (s == null) return def;
        try { return Double.parseDouble(stripQuotes(s)); } catch (Exception ignored) { return def; }
    }

    private int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(stripQuotes(s)); } catch (Exception ignored) { return def; }
    }

    private double[] parseDoubleArray(String s, int expected) {
        if (s == null) return new double[expected];
        String t = s.trim();
        if (t.startsWith("[")) t = t.substring(1);
        if (t.endsWith("]")) t = t.substring(0, t.length() - 1);
        String[] parts = t.split("\\s*,\\s*");
        double[] arr = new double[expected];
        for (int i = 0; i < Math.min(expected, parts.length); i++) arr[i] = parseDoubleSafe(parts[i], 0.0);
        return arr;
    }
}

