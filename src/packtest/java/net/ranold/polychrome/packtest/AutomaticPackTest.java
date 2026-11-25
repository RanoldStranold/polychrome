package net.ranold.polychrome.packtest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.Map;

// modded lights with no color written: color from their texture, a texture pack changing that texture
// changes the light, any written key beats it, vanilla lights are never guessed
public class AutomaticPackTest implements FabricClientGameTest {

    private static final String LIME = "polychrome-testblocks:lime_test_light";

    @Override
    public void runTest(ClientGameTestContext context) {
        PackTests t = new PackTests("automatic");
        PackTests.write(context, "auto_texture", Map.of());
        PackTests.writeTexture(context, "auto_texture", "assets/polychrome-testblocks/textures/block/lime_test_light.png", 0x2060FF);
        PackTests.write(context, "auto_written", Map.of("assets/pt_auto/polychrome/light_colors/auto.json", """
                { "colors": { "polychrome-testblocks:lime_test_light": "#FF0000" } }
                """));
        try (TestSingleplayerContext ignored = PackTests.world(context)) {
            t.equal("from the texture", PackTests.color(context, LIME), 0x82FF23);
            t.equal("vanilla light with no color stays white", PackTests.color(context, "minecraft:copper_bulb"), 0xFFFFFF);
            PackTests.use(context, "auto_texture");
            t.equal("texture pack changes it", PackTests.color(context, LIME), 0x2060FF);
            PackTests.use(context, "auto_texture", "auto_written");
            t.equal("written color beats it", PackTests.color(context, LIME), 0xFF0000);
            PackTests.use(context);
            t.equal("back to the original texture", PackTests.color(context, LIME), 0x82FF23);
        }
        t.done();
    }
}
