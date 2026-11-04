package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.color.LightColors;
import net.ranold.polychrome.field.Rgb9e5;

// rainbow test lights each in their own bay, floor has to be their color. test_lights.json writes them
// every way a pack can (hex, set, set chain, texture, region, full path). swapped red/blue would turn yellow cyan
public class TestLightsGameTest implements FabricClientGameTest {

    private static final int GROUND = -60;
    private static final String[] COLORS = {"red", "orange", "yellow", "green", "cyan", "blue", "purple", "magenta", "white"};
    // texture ones = the texture's own color
    private static final int[] EXPECTED = {0xFF2020, 0xFF8000, 0xFFE020, 0x20FF40, 0x20E0FF, 0x2040FF, 0x9030FF, 0xFF30D0, 0xFFFFFF};

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set midnight");
            server.runCommand("gamemode spectator @a");
            // bays 6 apart with walls so each floor patch is one color
            for (int i = 0; i < COLORS.length; i++) {
                int x = i * 6;
                server.runCommand(String.format("fill %d %d -4 %d %d 0 minecraft:stone", x + 3, GROUND, x + 3, GROUND + 3));
                server.runCommand(String.format("setblock %d %d -3 polychrome-testblocks:%s_test_light", x, GROUND, COLORS[i]));
            }
            server.runCommand(String.format("fill -3 %d -4 -3 %d 0 minecraft:stone", GROUND, GROUND + 3));
            server.runCommand(String.format("tp @a 24 %d 9 180 35", GROUND + 9));
            singleplayer.getConnection().waitForChunksRender();
            context.waitTicks(20);
            for (int i = 0; i < 400 && context.computeOnClient(client -> Polychrome.colorPending()); i++) {
                context.waitTicks(1);
            }
            context.waitTicks(5);
            context.takeScreenshot("test-lights");

            StringBuilder failures = new StringBuilder();
            for (int i = 0; i < COLORS.length; i++) {
                int x = i * 6;
                String color = COLORS[i];
                String line = context.computeOnClient(client -> {
                    int source = LightColors.sourceOf(client.level.getBlockState(new net.minecraft.core.BlockPos(x, GROUND, -3)));
                    int packed = Polychrome.volume().packedAt(x, GROUND, -1);
                    float[] rgb = packed == 0 ? new float[3] : Rgb9e5.unpack(packed);
                    float max = Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
                    return String.format("%s: source #%06X, floor %s", color, source & 0xFFFFFF,
                            max <= 0 ? "none" : String.format("#%02X%02X%02X", Math.round(rgb[0] / max * 255), Math.round(rgb[1] / max * 255), Math.round(rgb[2] / max * 255)));
                });
                System.out.println("[polychrome-test] " + line);
                int source = context.computeOnClient(client -> LightColors.sourceOf(client.level.getBlockState(new net.minecraft.core.BlockPos(x, GROUND, -3))) & 0xFFFFFF);
                if (line.contains("floor none") || !close(source, EXPECTED[i])) {
                    failures.append(line).append(String.format(" (expected #%06X)", EXPECTED[i])).append("; ");
                }
            }
            if (!failures.isEmpty()) {
                throw new AssertionError("test lights not colored as written: " + failures);
            }
        }
    }

    // texture averages can be off a step or two
    private static boolean close(int a, int b) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF)) > 3) {
                return false;
            }
        }
        return true;
    }
}
