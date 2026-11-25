package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.shader.NvidiumPatcher;
import net.ranold.polychrome.shader.ShaderPatcher;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

// the picture, not the data: soul lantern on white concrete seen from above, color on and off. the floor beside
// it has to come out blue with color on, not without, and never brighter. catches a renderer update the shader
// patch no longer matches (the data tests all passed on 26.2 with uncolored terrain)
public class TerrainShaderGameTest implements FabricClientGameTest {

    private static final int GROUND = -60;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule advance_weather false");
            server.runCommand("time set midnight");
            server.runCommand("gamemode spectator @a");
            server.runCommand(String.format("fill -8 %d -8 8 %d 8 minecraft:white_concrete", GROUND - 1, GROUND - 1));
            server.runCommand(String.format("setblock 0 %d 0 minecraft:soul_lantern", GROUND));
            server.runCommand(String.format("tp @a 0.5 %d 0.5 0 90", GROUND + 5));
            context.runOnClient(client -> {
                if (!client.gui.hud.isHidden()) {
                    client.gui.hud.toggle();
                }
            });
            singleplayer.getConnection().waitForChunksRender();
            context.waitTicks(20);
            for (int i = 0; i < 400 && context.computeOnClient(client -> Polychrome.colorPending()); i++) {
                context.waitTick();
            }
            context.waitTicks(20);
            double[] on = floor(context.takeScreenshot("terrain-shader-on"));
            context.runOnClient(client -> Polychrome.setTintEnabled(false));
            context.waitTicks(10);
            double[] off = floor(context.takeScreenshot("terrain-shader-off"));
            context.runOnClient(client -> {
                Polychrome.setTintEnabled(true);
                if (client.gui.hud.isHidden()) {
                    client.gui.hud.toggle();
                }
            });

            boolean nvidium = FabricLoader.getInstance().isModLoaded("nvidium");
            String patch = nvidium
                    ? "Nvidium patch: solid " + NvidiumPatcher.solidColored + ", translucent " + NvidiumPatcher.translucentColored
                    : FabricLoader.getInstance().isModLoaded("sodium") ? "Sodium patch applied: " + ShaderPatcher.sodiumTerrainColored
                    : "vanilla renderer";
            String seen = String.format("floor with color %s, without %s; %s", rgb(on), rgb(off), patch);
            System.out.println("[polychrome-test] terrain shader: " + seen);
            // soul fire is blue, vanilla block light is warm
            boolean blue = on[2] - on[0] > 20;
            boolean changed = (on[2] - on[0]) - (off[2] - off[0]) > 20;
            boolean notBrighter = max(on) <= max(off) + 6;
            if (!blue || !changed || !notBrighter) {
                throw new AssertionError("terrain shader: " + (!blue || !changed ? "floor beside a soul lantern is not blue with color on"
                        : "color made the floor brighter") + " (" + seen + ")");
            }
        }
    }

    // average floor color in a ring around the lantern at the screen center
    private static double[] floor(Path screenshot) {
        BufferedImage image;
        try {
            image = ImageIO.read(screenshot.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int w = image.getWidth(), h = image.getHeight();
        double inner = 0.12 * h, outer = 0.3 * h;
        double[] sum = new double[3];
        int count = 0;
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                double d = Math.hypot(x - w / 2.0, y - h / 2.0);
                if (d < inner || d > outer) {
                    continue;
                }
                int c = image.getRGB(x, y);
                sum[0] += (c >> 16) & 255;
                sum[1] += (c >> 8) & 255;
                sum[2] += c & 255;
                count++;
            }
        }
        return new double[]{sum[0] / count, sum[1] / count, sum[2] / count};
    }

    private static double max(double[] c) {
        return Math.max(c[0], Math.max(c[1], c[2]));
    }

    private static String rgb(double[] c) {
        return String.format("(%.0f, %.0f, %.0f)", c[0], c[1], c[2]);
    }
}
