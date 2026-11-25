package net.ranold.polychrome.packtest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.Map;

// several packs: higher wins key by key, lower keys it does not touch stay, order swaps flip it,
// replace drops everything below (built-in colors too) but not what is above
public class OrderPackTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        PackTests t = new PackTests("order");
        PackTests.write(context, "order_a", Map.of("assets/pt_a/polychrome/light_colors/a.json", """
                { "colors": { "minecraft:copper_bulb": "#FF0000", "minecraft:exposed_copper_bulb": "#00FF00" } }
                """));
        PackTests.write(context, "order_b", Map.of("assets/pt_b/polychrome/light_colors/b.json", """
                { "colors": { "minecraft:copper_bulb": "#0000FF" } }
                """));
        PackTests.write(context, "order_replace", Map.of("assets/pt_r/polychrome/light_colors/r.json", """
                { "replace": true, "colors": { "minecraft:weathered_copper_bulb": "#FFFF00" } }
                """));
        try (TestSingleplayerContext ignored = PackTests.world(context)) {
            int fire = PackTests.color(context, "minecraft:torch");
            int lime = PackTests.color(context, "polychrome-testblocks:lime_test_light");

            PackTests.use(context, "order_a", "order_b");
            t.equal("higher pack wins", PackTests.color(context, "minecraft:copper_bulb"), 0x0000FF);
            t.equal("lower pack's other keys stay", PackTests.color(context, "minecraft:exposed_copper_bulb"), 0x00FF00);
            t.equal("built-in colors stay", PackTests.color(context, "minecraft:torch"), fire);

            PackTests.use(context, "order_b", "order_a");
            t.equal("swapped order flips it", PackTests.color(context, "minecraft:copper_bulb"), 0xFF0000);

            PackTests.use(context, "order_a", "order_replace");
            t.equal("replace drops the built-in colors", PackTests.color(context, "minecraft:torch"), 0xFFFFFF);
            t.equal("replace drops lower packs", PackTests.color(context, "minecraft:copper_bulb"), 0xFFFFFF);
            t.equal("replace keeps its own colors", PackTests.color(context, "minecraft:weathered_copper_bulb"), 0xFFFF00);
            t.equal("automatic colors still apply after replace", PackTests.color(context, "polychrome-testblocks:lime_test_light"), lime);

            PackTests.use(context, "order_replace", "order_a");
            t.equal("pack above a replace keeps its colors", PackTests.color(context, "minecraft:copper_bulb"), 0xFF0000);
            t.equal("built-in colors are still gone under it", PackTests.color(context, "minecraft:torch"), 0xFFFFFF);

            PackTests.use(context);
            t.equal("all off restores the built-in colors", PackTests.color(context, "minecraft:torch"), fire);
        }
        t.done();
    }
}
