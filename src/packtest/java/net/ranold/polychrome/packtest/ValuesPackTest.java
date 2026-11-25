package net.ranold.polychrome.packtest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.Map;

// every way of writing a color, from a real pack: hex, set, set chain, built-in set, object forms, textures,
// texture region, and changing a built-in set recolors everything that uses it
public class ValuesPackTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        PackTests t = new PackTests("values");
        PackTests.write(context, "values", Map.of("assets/pt_values/polychrome/light_colors/values.json", """
                { "sets": {
                    "pt_teal": "#20C0C0",
                    "pt_chain": "@pt_teal",
                    "fire": "#FF2000"
                  },
                  "colors": {
                    "minecraft:copper_bulb": "#11AA33",
                    "minecraft:exposed_copper_bulb": "@pt_teal",
                    "minecraft:weathered_copper_bulb": { "color": "#C02090" },
                    "minecraft:oxidized_copper_bulb": { "set": "pt_chain" },
                    "minecraft:waxed_copper_bulb": "@soul_fire",
                    "minecraft:waxed_exposed_copper_bulb": { "texture": "pt_values:block/blue" },
                    "minecraft:waxed_weathered_copper_bulb": { "texture": "pt_values:textures/block/half.png", "region": [8, 0, 8, 16] },
                    "minecraft:waxed_oxidized_copper_bulb": { "texture": "pt_values:block/half" }
                } }
                """));
        PackTests.writeTexture(context, "values", "assets/pt_values/textures/block/blue.png", 0x2040FF);
        // left half red, right half green: region picks the green half, the whole averages to yellow
        writeHalves(context);
        try (TestSingleplayerContext ignored = PackTests.world(context)) {
            PackTests.use(context, "values");
            t.equal("hex", PackTests.color(context, "minecraft:copper_bulb"), 0x11AA33);
            t.equal("@set", PackTests.color(context, "minecraft:exposed_copper_bulb"), 0x20C0C0);
            t.equal("{color}", PackTests.color(context, "minecraft:weathered_copper_bulb"), 0xC02090);
            t.equal("{set} through a set chain", PackTests.color(context, "minecraft:oxidized_copper_bulb"), 0x20C0C0);
            t.equal("built-in set from a lower pack", PackTests.color(context, "minecraft:waxed_copper_bulb"), 0x58D6FF);
            t.equal("texture by id", PackTests.color(context, "minecraft:waxed_exposed_copper_bulb"), 0x2040FF);
            t.equal("texture region, full path", PackTests.color(context, "minecraft:waxed_weathered_copper_bulb"), 0x00FF00);
            t.equal("whole texture averages", PackTests.color(context, "minecraft:waxed_oxidized_copper_bulb"), 0xFFFF00);
            t.equal("changing a built-in set: torch", PackTests.color(context, "minecraft:torch"), 0xFF2000);
            t.equal("changing a built-in set: lantern", PackTests.color(context, "minecraft:lantern"), 0xFF2000);
            t.equal("changing a built-in set: candles by tag", PackTests.color(context, "minecraft:red_candle"), 0xFF2000);
            PackTests.use(context);
        }
        t.done();
    }

    private static void writeHalves(ClientGameTestContext context) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, x < 8 ? 0xFFFF0000 : 0xFF00FF00);
            }
        }
        java.nio.file.Path file = context.computeOnClient(client -> client.getResourcePackDirectory())
                .resolve("polychrome_pt_values/assets/pt_values/textures/block/half.png");
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            javax.imageio.ImageIO.write(image, "png", file.toFile());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
