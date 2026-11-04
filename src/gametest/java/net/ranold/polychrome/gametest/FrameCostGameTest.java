package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.InactivityFpsLimit;
import net.ranold.polychrome.Polychrome;

import java.util.Arrays;

// diagnostic, not listed. tint on vs off (K), alternating so drift hits both, uncapped fps.
// terrain from above + torch lit room underground. prints [polychrome-perf] frame cost, asserts nothing
public class FrameCostGameTest implements FabricClientGameTest {

    private static final int FRAMES = 1500;
    private static final int ROUNDS = 4;

    @Override
    public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> {
            client.options.framerateLimit().set(260);  // unlimited
            client.options.enableVsync().set(false);
            client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
            client.options.renderDistance().set(12);
        });
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamemode spectator @a");

            server.runCommand("time set noon");
            server.runCommand("tp @a 0.5 140 0.5 30 25");
            measure(context, singleplayer, "terrain from above, day");

            server.runCommand("time set midnight");
            server.runCommand("fill -24 -34 -24 24 -24 24 minecraft:air");
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
        // RD 12 worldgen is slow and waiting for every chunk can time out, so just wait a fixed bit
        context.waitTicks(300);
        for (int i = 0; i < 600 && context.computeOnClient(client -> Polychrome.colorPending()); i++) {
            context.waitTick();
        }
        context.waitTicks(40);
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
}
