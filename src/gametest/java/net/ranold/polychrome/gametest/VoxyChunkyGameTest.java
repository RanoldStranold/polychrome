package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.far.FarIngest;
import net.ranold.polychrome.field.HueField;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// chunky pregen with voxy: chunks never loaded still need distant color (lava at seed 1234).
// generates around x 2000, player at origin RD 2. skipped without voxy
public class VoxyChunkyGameTest implements FabricClientGameTest {

    private static final int CENTER_X = 2000;
    private static final int RADIUS = 160;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!FabricLoader.getInstance().isModLoaded("voxy") || !FabricLoader.getInstance().isModLoaded("chunky")) {
            System.out.println("[polychrome-test] voxy chunky: Voxy or Chunky not installed, skipped");
            return;
        }
        context.runOnClient(client -> client.options.renderDistance().set(2));
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(settings -> {
                    settings.setWorldType(settings.getNormalPresetList().getFirst());
                    settings.setSeed("1234");
                })
                .create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamemode spectator @a");
            long logStart = logLength();
            server.runCommand(String.format("chunky center %d 0", CENTER_X));
            server.runCommand("chunky radius " + RADIUS);
            server.runCommand("chunky start");
            long started = System.nanoTime();
            while (!logSince(logStart).contains("Task finished for minecraft:overworld")) {
                if (System.nanoTime() - started > 300_000_000_000L) {
                    throw new AssertionError("Chunky did not finish within 5 minutes");
                }
                context.waitTicks(20);
            }
            for (int i = 0; i < 600; i++) {
                boolean settled = context.computeOnClient(client -> {
                    FarIngest ingest = Polychrome.farIngest();
                    return ingest != null && !ingest.isBusy() && !Polychrome.far().pending();
                });
                if (settled) {
                    break;
                }
                context.waitTicks(1);
            }
            context.waitTicks(5);
            String result = context.computeOnClient(client -> {
                long[] keys = Polychrome.far().table().snapshotKeys();
                int near = 0;
                for (long key : keys) {
                    if (Math.abs(HueField.sectionX(key) * 16 - CENTER_X) <= RADIUS + 16 && Math.abs(HueField.sectionZ(key) * 16) <= RADIUS + 16) {
                        near++;
                    }
                }
                return near + ";" + Polychrome.farIngest().stats() + "; " + Polychrome.far().stats()
                        + "; client chunks " + client.level.getChunkSource().getLoadedChunksCount();
            });
            int sections = Integer.parseInt(result.substring(0, result.indexOf(';')));
            System.out.println("[polychrome-test] voxy chunky: distant color for " + sections + " sections around x=" + CENTER_X
                    + " (never loaded by the client); " + result.substring(result.indexOf(';') + 1));
            if (sections == 0) {
                throw new AssertionError("pre-generated chunks got no distant color");
            }
        } finally {
            context.runOnClient(client -> client.options.renderDistance().set(12));
        }
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
