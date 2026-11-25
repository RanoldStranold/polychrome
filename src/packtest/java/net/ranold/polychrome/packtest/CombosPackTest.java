package net.ranold.polychrome.packtest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.ranold.polychrome.color.LightColors;

import java.util.Map;

// features used together, expected values from how each should behave: rules with sets, tags, automatic colors,
// dimensions, replace and tint; broken entries and broken sets falling back across packs and dimensions
public class CombosPackTest implements FabricClientGameTest {

    private static final int G = PackTests.GROUND;
    private static final String LIME = "polychrome-testblocks:lime_test_light";
    private static final Map<String, String> CANDLE_ON_TOP = Map.of("above", "minecraft:red_candle");
    private static final Map<String, String> STONE_ON_TOP = Map.of("above", "minecraft:stone");
    private static final Map<String, String> NOTHING = Map.of();

    @Override
    public void runTest(ClientGameTestContext context) {
        PackTests t = new PackTests("combos");
        PackTests.write(context, "c_low", Map.of("assets/pt_c_low/polychrome/light_colors/low.json", """
                { "sets": { "pt_magic": "#A040FF", "pt_warm": "#00FFFF" },
                  "colors": {
                    "minecraft:copper_bulb": "#FF0000",
                    "minecraft:exposed_copper_bulb": "#FF0000",
                    "minecraft:waxed_copper_bulb": "#00FF00"
                  },
                  "rules": [
                    { "matchBlocks": "minecraft:sea_lantern", "above": "minecraft:red_candle", "color": "#FF2020" },
                    { "matchBlocks": "minecraft:glowstone", "touching": "#minecraft:wool", "color": "@pt_magic" },
                    { "matchBlocks": "polychrome-testblocks:lime_test_light", "above": "minecraft:stone", "color": "#0000FF" }
                  ],
                  "dimensions": {
                    "minecraft:the_nether": {
                      "colors": { "minecraft:waxed_copper_bulb": "#0000FF" },
                      "rules": [
                        { "matchBlocks": "minecraft:sea_lantern", "above": "minecraft:red_candle", "color": "#00FF00" },
                        { "matchBlocks": "minecraft:shroomlight", "above": "minecraft:stone", "color": "#00FF00" }
                      ]
                    }
                  } }
                """));
        PackTests.write(context, "c_high", Map.of("assets/pt_c_high/polychrome/light_colors/high.json", """
                { "sets": { "pt_magic": "#FFFF00", "fire": "@pt_no_such_set", "pt_warm": "@fire" },
                  "colors": {
                    "#polychrome-testblocks:test_lights": "#123456",
                    "minecraft:copper_bulb[lit=true]": { "texture": "pt_c_high:block/missing" },
                    "minecraft:exposed_copper_bulb": { "texture": "pt_c_high:block/missing" },
                    "minecraft:lantern": "@pt_warm"
                  },
                  "rules": [
                    { "matchBlocks": "minecraft:shroomlight", "above": "minecraft:stone", "color": "#FF8000" }
                  ],
                  "dimensions": {
                    "minecraft:the_nether": { "colors": { "minecraft:copper_bulb": "@pt_no_such_set" } }
                  } }
                """));
        PackTests.write(context, "c_replace", Map.of("assets/pt_c_replace/polychrome/light_colors/replace.json", """
                { "replace": true,
                  "colors": { "minecraft:glowstone": "#00FFFF" },
                  "rules": [ { "matchBlocks": "minecraft:shroomlight", "above": "minecraft:stone", "color": "#FF00FF" } ] }
                """));
        PackTests.write(context, "c_end", Map.of("assets/pt_c_end/polychrome/light_colors/end.json", """
                { "items": { "minecraft:torch": "#00FF00" },
                  "dimensions": { "minecraft:the_end": { "tint": "#FF00FF", "tint_amount": 0.5 } } }
                """));
        try (TestSingleplayerContext singleplayer = PackTests.world(context)) {
            TestServerContext server = singleplayer.getServer();
            int fire = PackTests.color(context, "minecraft:torch");
            int lime = PackTests.color(context, LIME);

            PackTests.use(context, "c_low", "c_high");
            t.equal("rule color takes the higher pack's set", PackTests.ruleColor(context, "minecraft:glowstone", Map.of("east", "minecraft:white_wool")), 0xFFFF00);
            t.equal("broken set in a higher pack: the built-in set stays", PackTests.color(context, "minecraft:torch"), fire);
            t.equal("a set pointing at a broken set follows that set's fallback", PackTests.color(context, "minecraft:lantern"), fire);
            t.equal("lower pack's rule beats a higher pack's tag", PackTests.ruleColor(context, LIME, STONE_ON_TOP), 0x0000FF);
            t.equal("no rule match: the higher pack's tag beats the automatic color", PackTests.ruleColor(context, LIME, NOTHING), 0x123456);
            t.equal("broken state key: the lower pack's block key", PackTests.color(context, "minecraft:copper_bulb[lit=true]"), 0xFF0000);
            t.equal("broken key: the same key from the pack below", PackTests.color(context, "minecraft:exposed_copper_bulb"), 0xFF0000);
            t.equal("base rule in the overworld", PackTests.ruleColor(context, "minecraft:sea_lantern", CANDLE_ON_TOP), 0xFF2020);
            t.equal("higher pack's rule in the overworld", PackTests.ruleColor(context, "minecraft:shroomlight", STONE_ON_TOP), 0xFF8000);

            // in the world too: a rule colored by a set from another pack
            server.runCommand(String.format("tp @a 0.5 %d 8.5 180 40", G + 8));
            server.runCommand(String.format("setblock 1 %d 0 minecraft:white_wool", G));
            server.runCommand(String.format("setblock 0 %d 0 minecraft:glowstone", G));
            PackTests.waitForLight(context, 0, G, 4);
            t.close("in the world: rule with the higher pack's set", PackTests.hue(context, 0, G, 4), 0xFFFF00);

            PackTests.travel(context, server, "minecraft:the_nether", 100);
            t.equal("dimension rule beats the same pack's base rule", PackTests.ruleColor(context, "minecraft:sea_lantern", CANDLE_ON_TOP), 0x00FF00);
            t.equal("no rule match in the dimension: plain color", PackTests.ruleColor(context, "minecraft:sea_lantern", NOTHING), 0xBFF0FF);
            t.equal("higher pack's base rule beats a lower pack's dimension rule", PackTests.ruleColor(context, "minecraft:shroomlight", STONE_ON_TOP), 0xFF8000);
            t.equal("broken dimension key: the lower pack's color", PackTests.color(context, "minecraft:copper_bulb[lit=false]"), 0xFF0000);
            t.equal("dimension key beats the same pack's base key", PackTests.color(context, "minecraft:waxed_copper_bulb"), 0x0000FF);
            t.equal("broken set falls back in the dimension too", PackTests.color(context, "minecraft:torch"), fire);
            PackTests.travel(context, server, "minecraft:overworld", G + 8);

            PackTests.use(context, "c_low", "c_high", "c_replace");
            t.equal("replace drops lower rules", PackTests.ruleColor(context, "minecraft:sea_lantern", CANDLE_ON_TOP), 0xFFFFFF);
            t.equal("replace keeps its own rules", PackTests.ruleColor(context, "minecraft:shroomlight", STONE_ON_TOP), 0xFF00FF);
            t.equal("replace drops lower colors", PackTests.color(context, "minecraft:copper_bulb"), 0xFFFFFF);
            t.equal("automatic color under replace, lower rule gone", PackTests.ruleColor(context, LIME, STONE_ON_TOP), lime);
            t.close("in the world: replace recolors a placed rule light", PackTests.hue(context, 0, G, 4), 0x00FFFF);

            PackTests.use(context, "c_replace", "c_low");
            t.equal("pack above a replace keeps its rules", PackTests.ruleColor(context, "minecraft:sea_lantern", CANDLE_ON_TOP), 0xFF2020);
            t.close("in the world: pack above a replace", PackTests.hue(context, 0, G, 4), 0xA040FF);

            PackTests.use(context, "c_low", "c_high", "c_end");
            PackTests.travel(context, server, "minecraft:the_end", 100);
            t.equal("tint moves rule colors", PackTests.ruleColor(context, "minecraft:sea_lantern", CANDLE_ON_TOP), LightColors.mix(0xFF2020, 0xFF00FF, 0.5));
            t.equal("tint moves item colors", PackTests.item(context, "minecraft:torch"), LightColors.mix(0x00FF00, 0xFF00FF, 0.5));
            t.equal("tint moves a tag color over an automatic one", PackTests.ruleColor(context, LIME, NOTHING), LightColors.mix(0x123456, 0xFF00FF, 0.5));
            PackTests.travel(context, server, "minecraft:overworld", G + 8);
            t.equal("no tint back home", PackTests.item(context, "minecraft:torch"), 0x00FF00);
            PackTests.use(context);
        }
        t.done();
    }
}
