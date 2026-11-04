package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.compat.VoxyCompat;
import net.ranold.polychrome.far.FarColors;
import net.ranold.polychrome.far.FarFile;
import net.ranold.polychrome.field.Rgb9e5;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

// voxy LODs: rainbow lights ~90 blocks out colored while loaded, then RD 4 so voxy draws them.
// tint off/on has to change the distant terrain (nothing near is lit). skipped without voxy
public class VoxyFarGameTest implements FabricClientGameTest {

    private static final int GROUND = -60;
    private static final int LIGHTS_X = 150;
    private static final int BLUE_Z = -84 + 5 * 24;
    private static final String[] COLORS = {"red", "orange", "yellow", "green", "cyan", "blue", "purple", "magenta"};

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!FabricLoader.getInstance().isModLoaded("voxy")) {
            System.out.println("[polychrome-test] voxy: not installed, skipped");
            return;
        }
        context.runOnClient(client -> client.options.renderDistance().set(12));
        Path save;
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set midnight");
            server.runCommand("gamemode spectator @a");
            // lights' chunks have to be loaded for the fill
            server.runCommand(String.format("tp @a %d %d 0 -90 12", LIGHTS_X, GROUND + 25));
            context.waitTicks(100);
            for (int i = 0; i < COLORS.length; i++) {
                int z = -84 + i * 24;
                server.runCommand(String.format("fill %d %d %d %d %d %d polychrome-testblocks:%s_test_light",
                        LIGHTS_X - 1, GROUND, z - 1, LIGHTS_X + 1, GROUND, z + 1, COLORS[i]));
            }
            server.runCommand(String.format("tp @a 60 %d 0 -90 12", GROUND + 25));
            // wait for the lights' surroundings only, RD 12 gen can outlast the framework's wait
            for (int tick = 0; tick < 1200; tick += 20) {
                boolean ready = context.computeOnClient(client -> Polychrome.far() != null
                        && Polychrome.far().table().stored(LIGHTS_X + 3, GROUND, -84 + 3 * 24) != 0
                        && Polychrome.far().table().stored(LIGHTS_X + 3, GROUND, -84 + 7 * 24) != 0);
                if (ready) {
                    break;
                }
                context.waitTicks(20);
            }
            context.waitTicks(60);
            String near = context.computeOnClient(client -> {
                FarColors far = Polychrome.far();
                int packed = far == null ? 0 : far.table().stored(LIGHTS_X + 3, GROUND, -84 + 3 * 24);
                return (far == null ? "no far colors" : far.stats()) + "; green cell " + (packed == 0 ? "none" : Integer.toHexString(packed))
                        + "; shader patched " + VoxyCompat.isShaderPatched();
            });
            System.out.println("[polychrome-test] voxy, in render distance: " + near);

            context.runOnClient(client -> client.options.renderDistance().set(4));
            context.waitTicks(200);
            Path on = context.takeScreenshot("voxy-far-on");
            context.runOnClient(client -> Polychrome.setTintEnabled(false));
            context.waitTicks(5);
            Path off = context.takeScreenshot("voxy-far-off");
            context.runOnClient(client -> Polychrome.setTintEnabled(true));

            int differing = context.computeOnClient(client -> Polychrome.far().verifyGpu());
            // camera side of the blue light: chunk still loaded, light's chunk isnt. has to stay blue, not go white
            float[] cameraSide = context.computeOnClient(client -> Rgb9e5.unpack(Polychrome.far().table().lookup(LIGHTS_X - 10, GROUND, BLUE_Z)));
            String after = context.computeOnClient(client -> Polychrome.far().stats()
                    + "; chunks " + client.level.getChunkSource().getLoadedChunksCount());
            int changed = changedPixels(on, off);
            System.out.printf("[polychrome-test] voxy, beyond render distance: %s; GPU ints differing from the table %d; camera side of blue light rgb %.3f %.3f %.3f; pixels changed by the tint %d%n",
                    after, differing, cameraSide[0], cameraSide[1], cameraSide[2], changed);
            if (!VoxyCompat.isShaderPatched()) {
                throw new AssertionError("Voxy's terrain shader was not patched");
            }
            if (changed < 200) {
                throw new AssertionError("distant terrain did not change color with the tint (" + changed + " pixels)");
            }
            if (differing != 0) {
                throw new AssertionError(differing + " ints of the GPU table differ from the CPU table");
            }
            if (!(cameraSide[2] > cameraSide[0] * 2)) {
                throw new AssertionError("the camera side of the blue light lost its color");
            }
            save = singleplayer.getWorldSave().getSaveDirectory();
        } finally {
            context.runOnClient(client -> client.options.renderDistance().set(12));
        }
        // left: distant color saved within a tick
        context.waitTicks(5);
        Path file = save.resolve("polychrome").resolve("far_minecraft_overworld.bin");
        int[] count = new int[1];
        try {
            FarFile.load(file, (key, cells) -> count[0]++);
        } catch (IOException e) {
            throw new AssertionError("distant color file unreadable: " + file, e);
        }
        System.out.println("[polychrome-test] voxy, saved: " + count[0] + " sections in " + file);
        if (count[0] == 0) {
            throw new AssertionError("no distant color saved at " + file);
        }
    }

    private static int changedPixels(Path a, Path b) {
        try {
            BufferedImage x = ImageIO.read(a.toFile()), y = ImageIO.read(b.toFile());
            int changed = 0;
            for (int py = 0; py < x.getHeight(); py++) {
                for (int px = 0; px < x.getWidth(); px++) {
                    int p = x.getRGB(px, py), q = y.getRGB(px, py);
                    int d = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)) + Math.abs((p & 255) - (q & 255));
                    if (d > 12) {
                        changed++;
                    }
                }
            }
            return changed;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
