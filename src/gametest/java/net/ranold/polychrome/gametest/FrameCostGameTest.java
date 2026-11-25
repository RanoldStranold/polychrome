package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.InactivityFpsLimit;
import net.ranold.polychrome.Polychrome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

// diagnostic, not listed. tint on vs off, alternating so drift hits both, uncapped fps. real world (seed 1234,
// pregenerated with chunky so worldgen does not compete): terrain by day, torches on the surface at night, a
// torch lit room underground. prints [polychrome-perf] frame cost, asserts nothing
public class FrameCostGameTest implements FabricClientGameTest {

    // frame times are steady (rounds within ~0.05 ms), 15 s a sample is enough
    private static final int FRAMES = 300;
    private static final int ROUNDS = 2;
    private static final int RENDER_DISTANCE = 12;

    @Override
    public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> {
            client.options.framerateLimit().set(260);  // unlimited
            client.options.enableVsync().set(false);
            client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
            client.options.renderDistance().set(RENDER_DISTANCE);
        });
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(settings -> {
                    settings.setWorldType(settings.getNormalPresetList().getFirst());
                    settings.setSeed("1234");
                })
                .create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule advance_weather false");
            server.runCommand("gamemode spectator @a");
            long logStart = logLength();
            server.runCommand("chunky center 0 0");
            server.runCommand("chunky radius " + (RENDER_DISTANCE + 2) * 16);
            server.runCommand("chunky start");
            for (int i = 0; i < 6000 && !logSince(logStart).contains("Task finished for minecraft:overworld"); i += 20) {
                context.waitTicks(20);
            }

            // spectators keep no chunks loaded on the server: heightmap reads and setblock need these
            server.runCommand("forceload add -48 -48 48 48");
            context.waitTicks(40);
            server.runCommand("time set noon");
            server.runCommand("execute positioned 0.5 0 0.5 positioned over world_surface run tp @a ~ ~20 ~ 30 25");
            measure(context, singleplayer, "terrain by day");

            server.runCommand("time set midnight");
            for (int x = -40; x <= 40; x += 8) {
                for (int z = -40; z <= 40; z += 8) {
                    server.runCommand(String.format("execute positioned %d 0 %d positioned over motion_blocking_no_leaves run setblock ~ ~ ~ minecraft:torch", x, z));
                }
            }
            server.runCommand("execute positioned 0.5 0 0.5 positioned over world_surface run tp @a ~ ~12 ~ 45 30");
            measure(context, singleplayer, "torches on the surface at night");

            server.runCommand("fill -23 -34 -23 23 -24 23 minecraft:air");
            for (int x = -20; x <= 20; x += 5) {
                for (int z = -20; z <= 20; z += 5) {
                    server.runCommand(String.format("setblock %d -34 %d minecraft:torch", x, z));
                }
            }
            server.runCommand("setblock 0 -35 0 minecraft:soul_lantern");
            server.runCommand("tp @a 0.5 -28 0.5 45 20");
            measure(context, singleplayer, "torch-lit room underground");
        }
    }

    private static void measure(ClientGameTestContext context, TestSingleplayerContext singleplayer, String view) {
        // waitForChunksRender times out at RD 12; pregenerated, so a fixed wait for loading from disk
        context.waitTicks(200);
        for (int i = 0; i < 600 && context.computeOnClient(client -> Polychrome.colorPending()); i++) {
            context.waitTick();
        }
        context.waitTicks(40);
        context.takeScreenshot("frame-cost-" + view.replace(' ', '-'));
        double[][] medians = new double[2][ROUNDS];
        double[][] means = new double[2][ROUNDS];
        for (int round = 0; round < ROUNDS; round++) {
            for (int on = 1; on >= 0; on--) {
                boolean enabled = on == 1;
                context.runOnClient(client -> Polychrome.setTintEnabled(enabled));
                context.waitTicks(20);
                context.runOnClient(client -> Polychrome.recordFrames(FRAMES));
                context.waitTicks(FRAMES);
                long[] times = context.computeOnClient(client -> Polychrome.recordedFrames());
                Arrays.sort(times);
                medians[on][round] = times.length == 0 ? 0 : times[times.length / 2] / 1e6;
                means[on][round] = Arrays.stream(times).average().orElse(0) / 1e6;
            }
        }
        context.runOnClient(client -> Polychrome.setTintEnabled(true));
        double onMedian = Arrays.stream(medians[1]).average().orElse(0), offMedian = Arrays.stream(medians[0]).average().orElse(0);
        double onMean = Arrays.stream(means[1]).average().orElse(0), offMean = Arrays.stream(means[0]).average().orElse(0);
        System.out.printf("[polychrome-perf] frame cost, %s: median %.3f ms on / %.3f ms off (%+.3f ms, %.0f / %.0f fps); mean %.3f / %.3f ms; rounds on %s off %s%n",
                view, onMedian, offMedian, onMedian - offMedian, 1000 / onMedian, 1000 / offMedian, onMean, offMean,
                Arrays.toString(round(medians[1])), Arrays.toString(round(medians[0])));
    }

    private static String[] round(double[] values) {
        return Arrays.stream(values).mapToObj(v -> String.format("%.3f", v)).toArray(String[]::new);
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
}
