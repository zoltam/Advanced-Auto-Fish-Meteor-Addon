package com.zoltam.autofish.modules; // <- keep your package

import com.zoltam.autofish.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.lang.reflect.Method;

public class ItemDisplayLogger extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> everyNTicks = sgGeneral.add(new IntSetting.Builder()
        .name("every-n-ticks")
        .description("How often to sample (ticks).")
        .defaultValue(5).min(1).sliderRange(1, 40).build());

    private final Setting<Double> radius = sgGeneral.add(new DoubleSetting.Builder()
        .name("radius")
        .description("Only log ItemDisplays within this radius of the player.")
        .defaultValue(18.0).min(2.0).sliderRange(2.0, 64.0).build());

    private final Setting<Boolean> logToChat = sgGeneral.add(new BoolSetting.Builder()
        .name("to-chat").description("Also echo logs to chat (spammy).")
        .defaultValue(false).build());

    private final Setting<Boolean> logToFile = sgGeneral.add(new BoolSetting.Builder()
        .name("to-file").description("Write logs to a TSV file in .minecraft/logs.")
        .defaultValue(true).build());

    private int tickCounter = 0;
    private Path logPath;
    private BufferedWriter writer;
    private final Map<Integer, Double> lastY = new HashMap<>();

    public ItemDisplayLogger() {
        super(AddonTemplate.CATEGORY, "item-display-logger", "Logs ItemDisplay entities (item id, pos, dY, names, tags).");
    }

    @Override
    public void onActivate() {
        tickCounter = 0;
        lastY.clear();

        if (logToFile.get()) {
            try {
                Path logsDir = FabricLoader.getInstance().getGameDir().resolve("logs");
                Files.createDirectories(logsDir);
                logPath = logsDir.resolve("meteor_itemdisplay_log.tsv");
                boolean newFile = Files.notExists(logPath);
                writer = Files.newBufferedWriter(
                    logPath,
                    StandardCharsets.UTF_8,
                    newFile ? new java.nio.file.OpenOption[]{} : new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.APPEND}
                );
                if (newFile) {
                    writeLine("ts\ttick\tplayerSneaking\tid\titemId\tcustomName\ttags\tx\ty\tz\tdY");
                } else {
                    writeLine("");
                    writeLine("# --- New Session: " + Instant.now() + " ---");
                    writeLine("ts\ttick\tplayerSneaking\tid\titemId\tcustomName\ttags\tx\ty\tz\tdY");
                }
                info("Logging to " + logPath.getFileName());
            } catch (IOException e) {
                error("Failed to open log file: " + e.getMessage());
                writer = null;
            }
        } else {
            info("File logging disabled. Enable 'to-file' to write a TSV.");
        }
    }

    @Override
    public void onDeactivate() {
        tryClose();
        info("Logger stopped.");
    }

    private void tryClose() {
        if (writer != null) {
            try { writer.flush(); writer.close(); } catch (IOException ignored) {}
            writer = null;
        }
    }

    private void writeLine(String s) {
        if (writer == null) return;
        try { writer.write(s); writer.newLine(); writer.flush(); } catch (IOException ignored) {}
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc == null || mc.world == null || mc.player == null) return;

        tickCounter++;
        if (tickCounter % everyNTicks.get() != 0) return;

        final double r = radius.get();
        final double r2 = r * r;
        boolean sneaking = mc.player.isSneaking();

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof DisplayEntity.ItemDisplayEntity)) continue;

            double dx = e.getX() - mc.player.getX();
            double dy = e.getY() - mc.player.getY();
            double dz = e.getZ() - mc.player.getZ();
            if (dx*dx + dy*dy + dz*dz > r2) continue;

            // Item stack via tolerant reflection (getItemStack / getStack)
            ItemStack stack = null;
            try { stack = (ItemStack) e.getClass().getMethod("getItemStack").invoke(e); }
            catch (Throwable t1) {
                try { stack = (ItemStack) e.getClass().getMethod("getStack").invoke(e); }
                catch (Throwable ignored) {}
            }

            String itemIdStr = "?";
            if (stack != null) {
                Item item = stack.getItem();
                if (item != null) {
                    Identifier id = Registries.ITEM.getId(item);
                    itemIdStr = (id != null) ? id.toString() : item.toString();
                }
            }

            String customName = (e.hasCustomName() && e.getCustomName() != null) ? e.getCustomName().getString() : "";
            Set<String> tags = getTagsSafe(e); // <-- FIX: handle both mappings

            double x = e.getX(), y = e.getY(), z = e.getZ();
            double prevY = lastY.getOrDefault(e.getId(), y);
            double dY = (y - prevY) / everyNTicks.get();
            lastY.put(e.getId(), y);

            if (logToChat.get()) {
                info(String.format("id=%d item=%s y=%.2f dY=%.3f sneak=%s", e.getId(), itemIdStr, y, dY, sneaking));
            }
            if (logToFile.get()) {
                String line = String.format(
                    "%d\t%d\t%s\t%d\t%s\t%s\t%s\t%.3f\t%.3f\t%.3f\t%.5f",
                    System.currentTimeMillis(), tickCounter, sneaking, e.getId(), itemIdStr,
                    sanitize(customName), sanitize(tags.toString()), x, y, z, dY
                );
                writeLine(line);
            }
        }
    }

    // --- helpers ---
    private String sanitize(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    @SuppressWarnings("unchecked")
    private Set<String> getTagsSafe(Entity e) {
        // Try getScoreboardTags() then getCommandTags()
        try {
            Method m = e.getClass().getMethod("getScoreboardTags");
            Object r = m.invoke(e);
            if (r instanceof Set) return (Set<String>) r;
        } catch (ReflectiveOperationException ignored) {}
        try {
            Method m = e.getClass().getMethod("getCommandTags");
            Object r = m.invoke(e);
            if (r instanceof Set) return (Set<String>) r;
        } catch (ReflectiveOperationException ignored) {}
        return Collections.emptySet();
    }
}
