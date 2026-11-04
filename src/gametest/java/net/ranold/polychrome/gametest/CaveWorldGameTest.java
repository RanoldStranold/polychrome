package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.color.LightColors;
import net.ranold.polychrome.gpu.LightVolume;

// diagnostic. normal world w/ caves, lava, lichen (where color went missing in play). checks every
// nearby light has color data and is on the GPU, then relogs and checks again
public class CaveWorldGameTest implements FabricClientGameTest {

    private static final int RADIUS = 96;

    @Override
    public void runTest(ClientGameTestContext context) {
        TestWorldSave save;
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
            server.runCommand("tp @a 0 -40 0");
            singleplayer.getConnection().waitForChunksRender();
            context.waitTicks(200);
            audit(context, "first join");
            lavaShots(context, server, "join");
        }
        try (TestSingleplayerContext singleplayer = save.open()) {
            singleplayer.getConnection().waitForChunksRender();
            context.waitTicks(200);
            audit(context, "after relog");
            lavaShots(context, singleplayer.getServer(), "relog");
        }
    }

    // up to 3 exposed lava blocks underground, from 5 blocks away, screenshot each
    private static void lavaShots(ClientGameTestContext context, TestServerContext server, String tag) {
        java.util.List<BlockPos> lava = context.computeOnClient(client -> {
            java.util.List<BlockPos> found = new java.util.ArrayList<>();
            BlockPos center = client.player.blockPosition();
            for (int dx = -80; dx <= 80 && found.size() < 3; dx += 1) {
                for (int dz = -80; dz <= 80 && found.size() < 3; dz += 1) {
                    for (int y = -60; y < 0; y++) {
                        BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                        if (client.level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.LAVA)
                                && client.level.getBlockState(pos.above()).isAir()
                                && client.level.getBlockState(pos.above(2)).isAir()
                                && found.stream().noneMatch(p -> p.distManhattan(pos) < 24)) {
                            found.add(pos);
                            break;
                        }
                    }
                }
            }
            return found;
        });
        for (int i = 0; i < lava.size(); i++) {
            BlockPos pos = lava.get(i);
            server.runCommand(String.format("tp @a %d %d %d -90 45", pos.getX() - 3, pos.getY() + 4, pos.getZ()));
            context.waitTicks(40);
            System.out.printf("[polychrome-test] %s lava %d at %s%n", tag, i, pos.toShortString());
            context.takeScreenshot("cave-" + tag + "-lava-" + i);
        }
    }

    private static void audit(ClientGameTestContext context, String scene) {
        String result = context.computeOnClient(client -> {
            LightVolume volume = Polychrome.volume();
            BlockPos center = client.player.blockPosition();
            int emitters = 0, noData = 0, notResident = 0;
            StringBuilder examples = new StringBuilder();
            for (int cx = (center.getX() - RADIUS) >> 4; cx <= (center.getX() + RADIUS) >> 4; cx++) {
                for (int cz = (center.getZ() - RADIUS) >> 4; cz <= (center.getZ() + RADIUS) >> 4; cz++) {
                    LevelChunk chunk = client.level.getChunkSource().getChunk(cx, cz, false);
                    if (chunk == null) {
                        continue;
                    }
                    for (int y = Math.max(chunk.getMinY(), center.getY() - RADIUS); y < Math.min(chunk.getMaxY(), center.getY() + RADIUS); y++) {
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
                                    if (examples.length() < 600) {
                                        examples.append(' ').append(state.getBlock().getDescriptionId()).append('@').append(pos.toShortString()).append(';');
                                    }
                                } else if (!volume.isResident(pos.getX(), pos.getY(), pos.getZ())) {
                                    notResident++;
                                }
                            }
                        }
                    }
                }
            }
            return "emitters " + emitters + ", without color data " + noData + ", data but not on GPU " + notResident
                    + " | " + volume.stats() + " | missing:" + examples;
        });
        System.out.printf("[polychrome-test] %s: %s%n", scene, result);
    }

    @SuppressWarnings("unused")
    private static Minecraft unused() {
        return Minecraft.getInstance();
    }
}
