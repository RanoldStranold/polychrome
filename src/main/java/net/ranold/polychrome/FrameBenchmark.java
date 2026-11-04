package net.ranold.polychrome;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Arrays;

// dev only, J key. same view with tint on / off / near off / distant off, 3 rounds in rotating order,
// median frame time to chat. stand still ~10s
final class FrameBenchmark {

    private static final String[] MODES = {"tint on", "tint off", "near off", "distant off"};
    private static final int ROUNDS = 3;
    private static final int WARMUP = 60;
    private static final int FRAMES = 600;

    private static boolean running;
    private static int segment;
    private static int frame;
    private static long last;
    private static final long[] times = new long[FRAMES];
    private static final double[][] medians = new double[MODES.length][ROUNDS];

    private FrameBenchmark() {
    }

    static void start() {
        running = true;
        segment = 0;
        frame = 0;
        last = 0;
        say("Polychrome benchmark: hold still...");
    }

    static boolean running() {
        return running;
    }

    // 0 on, 1 off, 2 near off, 3 distant off
    static int mode() {
        int round = segment / MODES.length;
        return (segment + round) % MODES.length;
    }

    // call at frame start
    static void onFrame() {
        if (!running) {
            return;
        }
        long now = System.nanoTime();
        if (frame >= WARMUP && last != 0) {
            times[frame - WARMUP] = now - last;
        }
        last = now;
        if (++frame < WARMUP + FRAMES + 1) {
            return;
        }
        long[] sorted = times.clone();
        Arrays.sort(sorted);
        medians[mode()][segment / MODES.length] = sorted[FRAMES / 2] / 1e6;
        frame = 0;
        last = 0;
        if (++segment < MODES.length * ROUNDS) {
            return;
        }
        running = false;
        StringBuilder text = new StringBuilder("Polychrome benchmark (median frame time):");
        double off = Arrays.stream(medians[1]).average().orElse(0);
        for (int m = 0; m < MODES.length; m++) {
            double ms = Arrays.stream(medians[m]).average().orElse(0);
            text.append(String.format(" %s %.3f ms (%.0f fps%s);", MODES[m], ms, 1000 / ms,
                    m == 1 ? "" : String.format(", %+.3f ms vs off", ms - off)));
        }
        Polychrome.LOGGER.info("{} rounds: {}", text, Arrays.deepToString(medians));
        say(text.toString());
    }

    private static void say(String text) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendSystemMessage(Component.literal(text));
        }
    }
}
