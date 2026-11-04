package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.field.Rgb9e5;
import net.ranold.polychrome.gpu.LightVolume;

// report: north wall torch on a log had no color, west one did, both = neither. builds it one at a time
// then both, prints light + GPU hue. then relogs so the light comes from the server with the chunks
// (worldgen campfire case), has to be colored the same
public class WallTorchGameTest implements FabricClientGameTest {

    private static final int GROUND = -60;  // first air above the flat grass
    private static final int TORCH_Y = GROUND + 2;

    @Override
    public void runTest(ClientGameTestContext context) {
        TestWorldSave save;
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            save = singleplayer.getWorldSave();
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set midnight");
            server.runCommand("gamemode spectator @a");
            server.runCommand(String.format("fill 0 %d 0 0 %d 0 minecraft:oak_log", GROUND, GROUND + 4));
            server.runCommand(String.format("tp @a 4.5 %d -5.5 40 25", TORCH_Y + 1));
            singleplayer.getConnection().waitForChunksRender();
            context.waitTicks(40);

            String north = String.format("0 %d -1", TORCH_Y);
            String west = String.format("-1 %d 0", TORCH_Y);

            server.runCommand("setblock " + north + " minecraft:wall_torch[facing=north]");
            settle(context, singleplayer);
            dump(context, "north only");
            pair(context, "walltorch-north");

            server.runCommand("setblock " + north + " minecraft:air");
            server.runCommand("setblock " + west + " minecraft:wall_torch[facing=west]");
            settle(context, singleplayer);
            dump(context, "west only");
            pair(context, "walltorch-west");

            server.runCommand("setblock " + north + " minecraft:wall_torch[facing=north]");
            settle(context, singleplayer);
            dump(context, "both");
            pair(context, "walltorch-both");
        }
        try (TestSingleplayerContext singleplayer = save.open()) {
            settle(context, singleplayer);
            dump(context, "both after relog");
            pair(context, "walltorch-relog");
            int beside = context.computeOnClient(client -> Polychrome.volume().packedAt(2, TORCH_Y, -2));
            if (beside == 0) {
                throw new AssertionError("no color beside the torches after relog: light from chunk packets was missed");
            }
        }
    }

    private static void settle(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        context.waitTicks(20);
        singleplayer.getConnection().waitForChunksRender();
        context.waitTicks(10);
    }

    private static void pair(ClientGameTestContext context, String name) {
        context.takeScreenshot(name + "-on");
        context.runOnClient(client -> Polychrome.setTintEnabled(false));
        context.waitTicks(2);
        context.takeScreenshot(name + "-off");
        context.runOnClient(client -> Polychrome.setTintEnabled(true));
        context.waitTicks(2);
    }

    // top down slice at torch height and ground: engine light, then GPU hue
    private static void dump(ClientGameTestContext context, String scene) {
        String result = context.computeOnClient(client -> {
            LightVolume volume = Polychrome.volume();
            StringBuilder out = new StringBuilder();
            for (int y : new int[] {TORCH_Y, GROUND}) {
                out.append("\n y=").append(y).append(" block light (rows z=-8..8, cols x=-8..8):");
                for (int z = -8; z <= 8; z++) {
                    out.append("\n  ");
                    for (int x = -8; x <= 8; x++) {
                        out.append(String.format("%3d", client.level.getBrightness(LightLayer.BLOCK, new BlockPos(x, y, z))));
                    }
                }
                out.append("\n y=").append(y).append(" GPU hue per 2x2x2 cell (rgb, '.' = none):");
                for (int z = -8; z <= 8; z += 2) {
                    out.append("\n  ");
                    for (int x = -8; x <= 8; x += 2) {
                        int packed = volume.packedAt(x, y, z);
                        out.append(' ').append(packed == 0 ? "   .   " : hex(Rgb9e5.unpack(packed)));
                    }
                }
            }
            out.append("\n ").append(volume.stats());
            return out.toString();
        });
        System.out.printf("[polychrome-test] %s:%s%n", scene, result);
    }

    private static String hex(float[] rgb) {
        float max = Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
        if (max <= 0f) {
            return "   0   ";
        }
        return String.format("#%02X%02X%02X", Math.round(rgb[0] / max * 255), Math.round(rgb[1] / max * 255), Math.round(rgb[2] / max * 255));
    }
}
