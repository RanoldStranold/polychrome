package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.ranold.polychrome.Polychrome;

// report: lava placed right after opening a new world had no color for ages, also after relog.
// ticks from light appearing to color existing, fresh world and after relog
public class SurfaceLavaGameTest implements FabricClientGameTest {

    private static final int MAX_TICKS = 1200;

    @Override
    public void runTest(ClientGameTestContext context) {
        TestWorldSave save;
        BlockPos lava;
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(settings -> {
                    settings.setWorldType(settings.getNormalPresetList().getFirst());
                    settings.setSeed("777");
                })
                .create()) {
            save = singleplayer.getWorldSave();
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set midnight");
            server.runCommand("gamemode spectator @a");
            context.waitTicks(5);

            // as soon as the player is in: lava 8 blocks away
            lava = context.computeOnClient(client -> client.level.getHeightmapPos(
                    Heightmap.Types.MOTION_BLOCKING, client.player.blockPosition().offset(0, 0, 8)));
            BlockPos at = lava;
            server.runCommand(String.format("setblock %d %d %d lava", at.getX(), at.getY(), at.getZ()));
            measure(context, "fresh world, lava placed 5 ticks after join", at);
            singleplayer.getConnection().waitForChunksRender();
            server.runCommand(String.format("tp @a %d %d %d 0 45", at.getX(), at.getY() + 4, at.getZ() - 5));
            context.waitTicks(20);
            context.takeScreenshot("lava-fresh");
        }
        try (TestSingleplayerContext singleplayer = save.open()) {
            measure(context, "after relog, existing lava", lava);
            singleplayer.getConnection().waitForChunksRender();
            context.waitTicks(20);
            context.takeScreenshot("lava-relog");
        }
    }

    // ticks until block light next to the lava, and until color data
    private static void measure(ClientGameTestContext context, String scene, BlockPos lava) {
        BlockPos beside = lava.offset(1, 1, 0);
        int lightAt = -1, colorAt = -1;
        for (int tick = 0; tick <= MAX_TICKS && (lightAt < 0 || colorAt < 0); tick++) {
            boolean light = context.computeOnClient(client -> client.level != null && client.level.getBrightness(LightLayer.BLOCK, beside) > 0);
            boolean color = context.computeOnClient(client -> Polychrome.volume().packedAt(beside.getX(), beside.getY(), beside.getZ()) != 0);
            if (light && lightAt < 0) {
                lightAt = tick;
            }
            if (color && colorAt < 0) {
                colorAt = tick;
            }
            context.waitTick();
        }
        System.out.printf("[polychrome-test] %s: block light after %d ticks, color after %d ticks%n", scene, lightAt, colorAt);
    }
}
