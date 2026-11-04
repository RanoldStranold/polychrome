package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.field.Rgb9e5;

// diagnostic for "placing/breaking a light looks odd". soul torch on a section corner, screenshot +
// print engine light / field / GPU every frame after place and break, to see what arrives when
public class PlaceBreakGameTest implements FabricClientGameTest {

    private static final int GROUND = -60;
    private static final int FRAMES = 10;
    private static final int[][] PROBES = {{2, GROUND, 2}, {-2, GROUND, 2}, {2, GROUND, -2}, {-2, GROUND, -2}, {5, GROUND, 0}};

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set midnight");
            server.runCommand("gamemode creative @a");
            server.runCommand("item replace entity @a hotbar.0 with minecraft:soul_torch");
            // 2 blocks south of the corner looking at that grass block
            server.runCommand(String.format("tp @a 0.5 %d 2.5 180 39", GROUND));
            singleplayer.getConnection().waitForChunksRender();
            context.waitTicks(40);

            // real clicks like the user: client predicts the change and sodium rebuilds meshes right away
            context.getInput().pressKey(options -> options.keyUse);
            record(context, "place");
            context.waitTicks(40);
            server.runCommand(String.format("tp @a 0.5 %d 2.5 180 33", GROUND));
            context.waitTicks(5);
            context.getInput().pressKey(options -> options.keyAttack);
            record(context, "break");
        }
    }

    private static void record(ClientGameTestContext context, String name) {
        for (int frame = 0; frame < FRAMES; frame++) {
            int f = frame;
            String line = context.computeOnClient(client -> {
                StringBuilder out = new StringBuilder(" torch " + client.level.getBlockState(new BlockPos(0, GROUND, 0)).getBlock().getDescriptionId() + ";");
                for (int[] p : PROBES) {
                    BlockPos pos = new BlockPos(p[0], p[1], p[2]);
                    out.append(String.format(" (%d,%d) light %2d gpu %s;", p[0], p[2],
                            client.level.getBrightness(LightLayer.BLOCK, pos), color(Polychrome.volume().packedAt(p[0], p[1], p[2]))));
                }
                return out.toString();
            });
            System.out.printf("[polychrome-test] %s frame %d:%s%n", name, f, line);
            context.takeScreenshot(String.format("placebreak-%s-%02d", name, f));
        }
    }

    // #RRGGBB brightest 255, "WHITE" untraced, or "none"
    private static String color(int packed) {
        if (packed == 0) {
            return "none   ";
        }
        float[] c = Rgb9e5.unpack(packed);
        float max = Math.max(c[0], Math.max(c[1], c[2]));
        int rgb = Math.round(c[0] / max * 255) << 16 | Math.round(c[1] / max * 255) << 8 | Math.round(c[2] / max * 255);
        return rgb == 0xFFFFFF ? "WHITE  " : String.format("#%06X", rgb);
    }
}
