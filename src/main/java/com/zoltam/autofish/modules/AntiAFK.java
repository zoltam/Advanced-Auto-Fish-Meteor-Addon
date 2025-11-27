package com.zoltam.autofish.modules;

import com.zoltam.autofish.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Periodically nudges the player's view with a smooth, human-like motion and returns it to the
 * original direction to help avoid AFK kicks.
 *
 * <p>Behavior:</p>
 * <ul>
 *   <li>At randomized intervals, choose a small yaw/pitch offset (≤ max-angle).</li>
 *   <li>Animate toward that offset and back to the starting view using a smooth sine curve.</li>
 *   <li>End where you started (no net rotation), so it won’t drift your aim over time.</li>
 * </ul>
 */
public class AntiAFK extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // Interval between camera nudges (randomized between min & max for human-likeness)
    private final Setting<Double> minIntervalS = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-interval-seconds")
        .description("Minimum seconds between view nudges.")
        .defaultValue(40.0)
        .range(1.0, 3600.0)
        .sliderRange(1.0, 300.0)
        .build()
    );

    private final Setting<Double> maxIntervalS = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-interval-seconds")
        .description("Maximum seconds between view nudges.")
        .defaultValue(75.0)
        .range(1.0, 3600.0)
        .sliderRange(1.0, 300.0)
        .build()
    );

    // How long a single out-and-back animation lasts
    private final Setting<Double> moveDurationS = sgGeneral.add(new DoubleSetting.Builder()
        .name("move-duration-seconds")
        .description("Duration of each smooth camera motion (out and back).")
        .defaultValue(1.6)
        .range(0.2, 10.0)
        .sliderRange(0.5, 5.0)
        .build()
    );

    // Maximum angular change (hard-capped to 20° as requested)
    private final Setting<Integer> maxAngleDeg = sgGeneral.add(new IntSetting.Builder()
        .name("max-angle-deg")
        .description("Maximum degrees to look around (both sideways and up/down).")
        .defaultValue(20)
        .range(1, 20) // do not allow values > 20°
        .sliderRange(1, 20)
        .build()
    );

    // Runtime state
    private long nextStartMs = -1L;
    private long animStartMs = -1L;

    private float baseYaw;
    private float basePitch;

    private float deltaYaw;
    private float deltaPitch;

    public AntiAFK() {
        super(AddonTemplate.CATEGORY, "anti-afk",
            "Smoothly moves your camera a bit and returns to the original view to avoid AFK kicks.");
    }

    @Override
    public void onActivate() {
        scheduleNext();
        animStartMs = -1L;
    }

    @Override
    public void onDeactivate() {
        // Ensure we leave the player in a sane state.
        ClientPlayerEntity p = mc.player;
        if (p != null && animStartMs != -1L) {
            // Snap back to starting view if we're mid-animation.
            p.setYaw(baseYaw);
            p.setPitch(basePitch);
        }
        animStartMs = -1L;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        ClientPlayerEntity p = mc.player;
        if (p == null || !p.isAlive()) return;

        final long now = System.currentTimeMillis();

        // If we aren't animating, see if it's time to start a new nudge.
        if (animStartMs == -1L) {
            if (now >= nextStartMs) startNewAnimation(p, now);
            return;
        }

        // We are animating: progress t in [0,1]
        double durationMs = Math.max(0.2, moveDurationS.get()) * 1000.0;
        double t = (now - animStartMs) / durationMs;

        if (t >= 1.0) {
            // End of animation: snap back exactly to base, then schedule the next one.
            p.setYaw(baseYaw);
            p.setPitch(basePitch);
            animStartMs = -1L;
            scheduleNext();
            return;
        }

        // Smooth out-and-back motion using a half-wave sine: 0 -> 1 -> 0
        // offsetFactor = sin(π * t)  (0 at start/end, 1 at midpoint)
        double offsetFactor = Math.sin(Math.PI * t);

        float targetYaw = wrapYaw(baseYaw + (float) (deltaYaw * offsetFactor));
        float targetPitch = clampPitch(basePitch + (float) (deltaPitch * offsetFactor));

        p.setYaw(targetYaw);
        p.setPitch(targetPitch);
    }

    private void startNewAnimation(ClientPlayerEntity p, long nowMs) {
        // Capture the current view as the base
        baseYaw = p.getYaw();
        basePitch = p.getPitch();

        // Choose random deltas within [-max, max], and ensure pitch stays within bounds when applied.
        int max = Math.max(1, Math.min(20, maxAngleDeg.get())); // hard cap at 20°
        float chosenYaw = randomRange(-max, max);
        float chosenPitch = randomRange(-max, max);

        // Keep pitch within sensible limits after applying (vanilla pitch is roughly [-90, 90])
        chosenPitch = clampToAvailablePitch(basePitch, chosenPitch, 85f);

        deltaYaw = chosenYaw;
        deltaPitch = chosenPitch;

        animStartMs = nowMs;
    }

    private void scheduleNext() {
        double minS = Math.max(1.0, minIntervalS.get());
        double maxS = Math.max(minS, maxIntervalS.get()); // ensure max >= min
        double intervalChosenS = randomRangeDouble(minS, maxS);
        nextStartMs = System.currentTimeMillis() + (long) (intervalChosenS * 1000.0);
    }

    // Utilities

    private static float clampPitch(float pitch) {
        // Vanilla clamps close to [-90, 90]; use a small margin to avoid edge issues.
        if (pitch > 90f) return 90f;
        if (pitch < -90f) return -90f;
        return pitch;
    }

    private static float clampToAvailablePitch(float basePitch, float delta, float softLimit) {
        float maxUp = Math.min(softLimit - basePitch, Math.abs(delta));
        float maxDown = Math.min(softLimit + basePitch, Math.abs(delta));
        if (delta > 0) return Math.min(delta, maxUp);
        else return -Math.min(Math.abs(delta), maxDown);
    }

    private static float wrapYaw(float yaw) {
        // Normalize to [-180, 180) to avoid large drift values
        float y = yaw % 360f;
        if (y >= 180f) y -= 360f;
        if (y < -180f) y += 360f;
        return y;
    }

    private static float randomRange(int min, int max) {
        return (float) ThreadLocalRandom.current().nextDouble(min, max);
    }

    private static double randomRangeDouble(double min, double max) {
        return ThreadLocalRandom.current().nextDouble(min, max);
    }
}
