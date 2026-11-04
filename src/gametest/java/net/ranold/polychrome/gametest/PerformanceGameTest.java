package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.ranold.polychrome.color.LightColors;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.ranold.polychrome.Polychrome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// join until every light is colored, and placed light color time during and after join work. RD 32.
// prints numbers, fails if a placed light takes more than a few frames once idle
public class PerformanceGameTest implements FabricClientGameTest {

    private static final int RENDER_DISTANCE = 32;
    private static final long JOIN_TIMEOUT_MS = 180_000;

    @Override
    public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> client.options.renderDistance().set(RENDER_DISTANCE));
        TestWorldSave save;
        // pregen with chunky first so the join loads from disk instead of timing worldgen
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(settings -> {
                    settings.setWorldType(settings.getNormalPresetList().getFirst());
                    settings.setSeed("1234");
                })
                .create()) {
            save = singleplayer.getWorldSave();
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set midnight");
            server.runCommand("gamemode spectator @a");
            long logStart = logLength();
            server.runCommand("chunky center 0 0");
            server.runCommand("chunky radius " + (RENDER_DISTANCE + 2) * 16);
            server.runCommand("chunky start");
            long started = System.nanoTime();
            while (!logSince(logStart).contains("Task finished for minecraft:overworld")) {
                if (System.nanoTime() - started > 600_000_000_000L) {
                    throw new AssertionError("Chunky did not finish within 10 minutes");
                }
                context.waitTicks(20);
            }
            server.runCommand("save-all flush");
            context.waitTicks(40);
        }

        long start = System.nanoTime();
        try (TestSingleplayerContext singleplayer = save.open()) {
            long joined = System.nanoTime();
            TestServerContext server = singleplayer.getServer();

            // placed while join work is still running (the slow lava report)
            long busyEdit = placeAndTime(context, server, 3);
            long[] done = waitForColor(context);
            long passes = context.computeOnClient(client -> Polychrome.workerPasses());
            long workMs = context.computeOnClient(client -> Polychrome.workerMillis());
            String timings = context.computeOnClient(client -> Polychrome.workerTimings());
            String stats = context.computeOnClient(client -> Polychrome.volume().stats()
                    + ", chunks " + client.level.getChunkSource().getLoadedChunksCount()
                    + (Polychrome.far() == null ? "" : "; " + Polychrome.far().stats() + ", render thread " + Polychrome.far().frameCost()));

            String missing = audit(context);
            if (!missing.isEmpty()) {
                context.waitTicks(40);  // flowing water/lava may have just changed light
                missing = audit(context);
            }

            long idleEdit = placeAndTime(context, server, 8);

            // pack changes a set with all this loaded: how long the reload screen holds, and is it complete after
            long[] reload = reloadWithPack(context);
            String afterReload = audit(context);
            System.out.printf("[polychrome-perf] color change by resource pack: reload %d ms, of which recoloring every loaded chunk %d ms; lit blocks without color right after: %s%n",
                    reload[0], reload[1], afterReload.isEmpty() ? "none" : afterReload);
            if (!afterReload.isEmpty()) {
                throw new AssertionError("lit blocks without color right after the color reload: " + afterReload);
            }

            System.out.printf("[polychrome-perf] world opened in %d ms; last chunk %d ms after joining; color caught up %d ms after joining; worker busy %d ms for %d section passes; %s%n",
                    ms(joined - start), ms(done[0] - joined), ms(done[1] - joined), workMs, passes, stats);
            System.out.printf("[polychrome-perf] worker time split: %s%n", timings);
            System.out.printf("[polychrome-perf] placed lava colored after %d ms during join work, %d ms when idle%n", busyEdit, idleEdit);
            System.out.printf("[polychrome-perf] lit blocks without color within 64 blocks: %s%n", missing.isEmpty() ? "none" : missing);
            if (!missing.isEmpty()) {
                throw new AssertionError("lit blocks without color: " + missing);
            }
            if (idleEdit < 0 || idleEdit > 500) {
                throw new AssertionError("placed lava took " + idleEdit + " ms to color while the mod was idle");
            }
        }
    }

    // every block within 64 lit by block light (not a source itself) needs color data. returns misses
    private static String audit(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            BlockPos center = client.player.blockPosition();
            int lit = 0, missing = 0;
            StringBuilder examples = new StringBuilder();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int x = -64; x <= 64; x++) {
                for (int z = -64; z <= 64; z++) {
                    for (int y = Math.max(client.level.getMinY(), center.getY() - 64); y <= Math.min(client.level.getMaxY(), center.getY() + 64); y++) {
                        pos.set(center.getX() + x, y, center.getZ() + z);
                        if (client.level.getBrightness(LightLayer.BLOCK, pos) == 0
                                || LightColors.sourceOf(client.level.getBlockState(pos)) != 0) {
                            continue;
                        }
                        lit++;
                        if (Polychrome.volume().packedAt(pos.getX(), pos.getY(), pos.getZ()) == 0) {
                            missing++;
                            if (examples.length() < 300) {
                                examples.append(' ').append(pos.toShortString()).append(';');
                            }
                        }
                    }
                }
            }
            System.out.printf("[polychrome-perf] audit: %d lit blocks, %d without color%n", lit, missing);
            return missing == 0 ? "" : missing + " of " + lit + ":" + examples;
        });
    }

    // lava offset blocks east, 1 up. time until the block 2 further east has color on the GPU, -1 if not within 30s
    private static long placeAndTime(ClientGameTestContext context, TestServerContext server, int offset) {
        BlockPos player = context.computeOnClient(client -> client.player.blockPosition());
        BlockPos lava = player.offset(offset, 1, 0);
        BlockPos probe = lava.offset(2, 0, 0);
        server.runCommand(String.format("fill %d %d %d %d %d %d minecraft:glass", lava.getX() - 1, lava.getY() - 1, lava.getZ() - 1,
                lava.getX() + 3, lava.getY() - 1, lava.getZ() + 1));
        server.runCommand(String.format("fill %d %d %d %d %d %d minecraft:air", lava.getX(), lava.getY(), lava.getZ() - 1,
                lava.getX() + 3, lava.getY() + 1, lava.getZ() + 1));
        context.waitTicks(2);
        long start = System.nanoTime();
        server.runCommand(String.format("setblock %d %d %d minecraft:lava", lava.getX(), lava.getY(), lava.getZ()));
        while (System.nanoTime() - start < 30_000_000_000L) {
            boolean colored = context.computeOnClient(client -> Polychrome.volume().packedAt(probe.getX(), probe.getY(), probe.getZ()) != 0);
            if (colored) {
                return ms(System.nanoTime() - start);
            }
            context.waitTicks(1);
        }
        return -1;
    }

    // waits for chunks to stop (2s no change), then for color work to first run dry.
    // returns {last chunk, ran dry}. flowing water/lava keep it from staying idle long
    private static long[] waitForColor(ClientGameTestContext context) {
        long start = System.nanoTime();
        long lastChange = start;
        int lastChunks = -1;
        int stableTicks = 0;
        while (stableTicks < 40 && System.nanoTime() - start < JOIN_TIMEOUT_MS * 1_000_000L) {
            int chunks = context.computeOnClient(client -> client.level.getChunkSource().getLoadedChunksCount());
            if (chunks != lastChunks) {
                lastChunks = chunks;
                lastChange = System.nanoTime();
                stableTicks = 0;
            } else {
                stableTicks++;
            }
            context.waitTicks(1);
        }
        while (System.nanoTime() - start < JOIN_TIMEOUT_MS * 1_000_000L
                && context.computeOnClient(client -> Polychrome.colorPending())) {
            context.waitTicks(1);
        }
        return new long[] {lastChange, System.nanoTime()};
    }

    private static long logLength() {
        try {
            return Files.size(Path.of("logs", "latest.log"));
        } catch (IOException e) {
            return 0;
        }
    }

    private static String logSince(long offset) {
        try {
            byte[] all = Files.readAllBytes(Path.of("logs", "latest.log"));
            return offset >= all.length ? "" : new String(all, (int) offset, all.length - (int) offset);
        } catch (IOException e) {
            return "";
        }
    }

    // fire set -> green pack on, reload, returns {reload ms, recolor ms}
    private static long[] reloadWithPack(ClientGameTestContext context) {
        Path dir = context.computeOnClient(client -> client.getResourcePackDirectory()).resolve("polychrome_perf_green_fire");
        try {
            Files.createDirectories(dir.resolve("assets/polychrome_perf/polychrome/light_colors"));
            Files.writeString(dir.resolve("pack.mcmeta"), "{ \"pack\": { \"description\": \"test\", \"min_format\": 97, \"max_format\": 97 } }");
            Files.writeString(dir.resolve("assets/polychrome_perf/polychrome/light_colors/fire.json"), "{ \"sets\": { \"fire\": \"#30FF30\", \"lava\": \"#FF3030\" } }");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        long start = System.nanoTime();
        java.util.concurrent.CompletableFuture<Void> reload = context.computeOnClient(client -> {
            client.getResourcePackRepository().reload();
            java.util.List<String> selected = new java.util.ArrayList<>(client.getResourcePackRepository().getSelectedIds());
            client.getResourcePackRepository().getAvailableIds().stream().filter(id -> id.contains("polychrome_perf_green_fire")).forEach(selected::add);
            client.getResourcePackRepository().setSelected(selected);
            return client.reloadResourcePacks();
        });
        for (int i = 0; i < 3000 && !reload.isDone(); i++) {
            context.waitTicks(1);
        }
        long total = ms(System.nanoTime() - start);
        context.waitTicks(1);
        return new long[] {total, context.computeOnClient(client -> Polychrome.lastRecolorMillis())};
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }
}
