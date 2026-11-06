package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.far.FarIngest;
import net.ranold.polychrome.field.HueField;

// voxygen builds LODs itself and hands voxy sections one by one, never through tryAutoIngestChunk.
// player at RD 2, voxygen fills around them: lit sections beyond RD need distant color. skipped without both
public class VoxygenGameTest implements FabricClientGameTest {

    private static final int BEYOND = 64;  // blocks, past RD 2

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!FabricLoader.getInstance().isModLoaded("voxy") || !FabricLoader.getInstance().isModLoaded("voxyworldgenv3")) {
            System.out.println("[polychrome-test] voxygen: Voxy or Voxygen not installed, skipped");
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
            int far = 0;
            String stats = "";
            long started = System.nanoTime();
            while (System.nanoTime() - started < 180_000_000_000L) {
                context.waitTicks(100);
                String result = context.computeOnClient(client -> count(client.player.getBlockX(), client.player.getBlockZ())
                        + ";" + Polychrome.farIngest().stats() + "; " + Polychrome.far().stats());
                far = Integer.parseInt(result.substring(0, result.indexOf(';')));
                stats = result.substring(result.indexOf(';') + 1);
                System.out.printf("[polychrome-test] voxygen: %.0f s, %d colored sections beyond %d blocks; %s%n",
                        (System.nanoTime() - started) / 1e9, far, BEYOND, stats);
                if (far >= 200) {
                    break;
                }
            }
            context.takeScreenshot("voxygen");
            if (far == 0) {
                throw new AssertionError("voxygen LODs got no distant color: " + stats);
            }
        } finally {
            context.runOnClient(client -> client.options.renderDistance().set(12));
        }
    }

    private static int count(int px, int pz) {
        FarIngest ingest = Polychrome.farIngest();
        if (ingest == null) {
            return 0;
        }
        int n = 0;
        for (long key : Polychrome.far().table().snapshotKeys()) {
            int dx = HueField.sectionX(key) * 16 + 8 - px, dz = HueField.sectionZ(key) * 16 + 8 - pz;
            if (Math.max(Math.abs(dx), Math.abs(dz)) > BEYOND) {
                n++;
            }
        }
        return n;
    }
}
