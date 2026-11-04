package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.color.LightColors;
import net.ranold.polychrome.gpu.LightVolume;

// two old reports: nether lava sea sections with no color (GPU pool overflowed), and color
// stopping short of the light on a flat world. screenshots tint on/off + checks the GPU has every light
public class NetherAndReachGameTest implements FabricClientGameTest {

    private static final int FLOOR = -61;

    @Override
    public void runTest(ClientGameTestContext context) {
        // flat world night, 3x3 lava pool from above. color should reach as far as the glow
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set midnight");
            server.runCommand("gamemode spectator @a");
            server.runCommand(String.format("fill -1 %d -1 1 %d 1 lava", FLOOR, FLOOR));
            server.runCommand(String.format("tp @a 0.5 %d -14.5 0 40", FLOOR + 14));
            singleplayer.getConnection().waitForChunksRender();
            context.waitTicks(100);
            pair(context, "reach-flat");
        }

        // nether: above the lava sea
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(settings -> {
                    settings.setWorldType(settings.getNormalPresetList().getFirst());
                    settings.setSeed("4321");
                })
                .create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("execute in minecraft:the_nether run tp @a 0 45 0 0 60");
            context.waitTicks(40);
            singleplayer.getConnection().waitForChunksRender();
            context.waitTicks(200);
            audit(context, "nether");
            pair(context, "nether-down");
            server.runCommand("execute in minecraft:the_nether run tp @a 0 45 0 90 20");
            context.waitTicks(40);
            pair(context, "nether-east");
        }
    }

    private static void pair(ClientGameTestContext context, String name) {
        context.takeScreenshot(name + "-on");
        context.runOnClient(client -> Polychrome.setTintEnabled(false));
        context.waitTicks(2);
        context.takeScreenshot(name + "-off");
        context.runOnClient(client -> Polychrome.setTintEnabled(true));
        context.waitTicks(2);
    }

    private static void audit(ClientGameTestContext context, String scene) {
        String result = context.computeOnClient(client -> {
            LightVolume volume = Polychrome.volume();
            BlockPos center = client.player.blockPosition();
            int radius = 48;
            int emitters = 0, noData = 0, notResident = 0;
            for (int cx = (center.getX() - radius) >> 4; cx <= (center.getX() + radius) >> 4; cx++) {
                for (int cz = (center.getZ() - radius) >> 4; cz <= (center.getZ() + radius) >> 4; cz++) {
                    LevelChunk chunk = client.level.getChunkSource().getChunk(cx, cz, false);
                    if (chunk == null) {
                        continue;
                    }
                    for (int y = Math.max(chunk.getMinY(), center.getY() - radius); y < Math.min(chunk.getMaxY(), center.getY() + radius); y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockPos pos = new BlockPos((cx << 4) + x, y, (cz << 4) + z);
                                BlockState state = chunk.getBlockState(pos);
                                if (LightColors.sourceOf(state) == 0) {
                                    continue;
                                }
                                emitters++;
                                if (volume.packedAt(pos.getX(), pos.getY(), pos.getZ()) == 0) {
                                    noData++;
                                } else if (!volume.isResident(pos.getX(), pos.getY(), pos.getZ())) {
                                    notResident++;
                                }
                            }
                        }
                    }
                }
            }
            return "emitters " + emitters + ", without color data " + noData + ", data but not on GPU " + notResident + " | " + volume.stats();
        });
        System.out.printf("[polychrome-test] %s: %s%n", scene, result);
    }
}
