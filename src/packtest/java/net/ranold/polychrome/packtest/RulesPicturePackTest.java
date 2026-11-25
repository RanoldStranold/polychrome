package net.ranold.polychrome.packtest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;

// a neighbor rule as a player sees it: sea lantern on white concrete from above, floor red with a red candle on
// top, back to the lantern's own pale blue once the candle is gone
public class RulesPicturePackTest implements FabricClientGameTest {

    private static final int G = PackTests.GROUND;

    @Override
    public void runTest(ClientGameTestContext context) {
        PackTests t = new PackTests("rules-picture");
        PackTests.write(context, "picture", Map.of("assets/pt_picture/polychrome/light_colors/rules.json", """
                { "rules": [ { "matchBlocks": "minecraft:sea_lantern", "above": "minecraft:red_candle", "color": "#FF2020" } ] }
                """));
        try (TestSingleplayerContext singleplayer = PackTests.world(context)) {
            TestServerContext server = singleplayer.getServer();
            PackTests.use(context, "picture");
            server.runCommand(String.format("fill -8 %d -8 8 %d 8 minecraft:white_concrete", G - 1, G - 1));
            server.runCommand(String.format("setblock 0 %d 0 minecraft:sea_lantern", G));
            server.runCommand(String.format("setblock 0 %d 0 minecraft:red_candle", G + 1));
            server.runCommand(String.format("tp @a 0.5 %d 0.5 0 90", G + 6));
            context.runOnClient(client -> {
                if (!client.gui.hud.isHidden()) {
                    client.gui.hud.toggle();
                }
            });
            singleplayer.getConnection().waitForChunksRender();
            PackTests.waitForLight(context, 2, G, 0);
            context.waitTicks(20);
            double[] red = floor(context.takeScreenshot("rules-candle-on-top"));

            server.runCommand(String.format("setblock 0 %d 0 minecraft:air", G + 1));
            context.waitTicks(2);
            PackTests.waitForLight(context, 2, G, 0);
            context.waitTicks(20);
            double[] plain = floor(context.takeScreenshot("rules-candle-removed"));
            context.runOnClient(client -> {
                if (client.gui.hud.isHidden()) {
                    client.gui.hud.toggle();
                }
            });
            PackTests.use(context);

            t.that("candle on top: floor is red", red[0] - red[2] > 25 && red[0] - red[1] > 25, rgb(red));
            t.that("candle gone: floor is not red", plain[2] >= plain[0] - 5, rgb(plain));
        }
        t.done();
    }

    // average floor color in a ring around the screen center
    private static double[] floor(Path screenshot) {
        BufferedImage image;
        try {
            image = ImageIO.read(screenshot.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int w = image.getWidth(), h = image.getHeight();
        double[] sum = new double[3];
        int count = 0;
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                double d = Math.hypot(x - w / 2.0, y - h / 2.0);
                if (d < 0.12 * h || d > 0.3 * h) {
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

    private static String rgb(double[] c) {
        return String.format("floor (%.0f, %.0f, %.0f)", c[0], c[1], c[2]);
    }
}
