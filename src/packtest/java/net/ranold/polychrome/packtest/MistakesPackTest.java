package net.ranold.polychrome.packtest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.Map;

// pack mistakes: a bad value, an unknown block, a missing texture, a set loop and a broken file must only
// lose that one entry: the color from the pack below stays. everything else in the same file, pack and other
// files still works
public class MistakesPackTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        PackTests t = new PackTests("mistakes");
        PackTests.write(context, "mistakes", Map.of(
                "assets/pt_bad/polychrome/light_colors/a_mixed.json", """
                        { "sets": { "loop_a": "@loop_b", "loop_b": "@loop_a" },
                          "colors": {
                            "minecraft:copper_bulb": "#GGGGGG",
                            "minecraft:not_a_block": "#FF0000",
                            "minecraft:exposed_copper_bulb": { "texture": "pt_bad:block/missing" },
                            "minecraft:weathered_copper_bulb": "@loop_a",
                            "minecraft:oxidized_copper_bulb": "#00FF00",
                            "minecraft:waxed_copper_bulb": "@no_such_set",
                            "minecraft:waxed_weathered_copper_bulb": "@no_such_set"
                        } }
                        """,
                "assets/pt_bad/polychrome/light_colors/b_broken.json", "{ \"colors\": { \"minecraft:lantern\": ",
                "assets/pt_bad/polychrome/light_colors/c_fine.json", """
                        { "colors": { "minecraft:waxed_exposed_copper_bulb": "#0000FF" } }
                        """));
        // what the mistakes sit on top of
        PackTests.write(context, "mistakes_low", Map.of("assets/pt_bad_low/polychrome/light_colors/low.json", """
                { "colors": {
                    "minecraft:copper_bulb": "#110022",
                    "minecraft:exposed_copper_bulb": "#220033",
                    "minecraft:weathered_copper_bulb": "#330044",
                    "minecraft:waxed_copper_bulb": "#440055"
                } }
                """));
        try (TestSingleplayerContext ignored = PackTests.world(context)) {
            int fire = PackTests.color(context, "minecraft:lantern");
            PackTests.use(context, "mistakes_low", "mistakes");
            t.equal("valid entry next to the mistakes", PackTests.color(context, "minecraft:oxidized_copper_bulb"), 0x00FF00);
            t.equal("file after a broken file", PackTests.color(context, "minecraft:waxed_exposed_copper_bulb"), 0x0000FF);
            t.equal("bad hex keeps the lower pack's color", PackTests.color(context, "minecraft:copper_bulb"), 0x110022);
            t.equal("missing texture keeps the lower pack's color", PackTests.color(context, "minecraft:exposed_copper_bulb"), 0x220033);
            t.equal("set loop keeps the lower pack's color", PackTests.color(context, "minecraft:weathered_copper_bulb"), 0x330044);
            t.equal("unknown set keeps the lower pack's color", PackTests.color(context, "minecraft:waxed_copper_bulb"), 0x440055);
            t.equal("no lower color: white", PackTests.color(context, "minecraft:waxed_weathered_copper_bulb"), 0xFFFFFF);
            t.equal("broken file changes nothing", PackTests.color(context, "minecraft:lantern"), fire);
            t.equal("built-in colors untouched", PackTests.color(context, "minecraft:torch"), fire);
            PackTests.use(context);
        }
        t.done();
    }
}
