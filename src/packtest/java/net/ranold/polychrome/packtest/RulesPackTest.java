package net.ranold.polychrome.packtest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;

import java.util.Map;

// neighbor rules, checked on floor next to each light
public class RulesPackTest implements FabricClientGameTest {

    private static final int G = PackTests.GROUND;
    private static final int SEA_LANTERN = 0xBFF0FF, GLOWSTONE = 0xFFD68A;

    @Override
    public void runTest(ClientGameTestContext context) {
        PackTests t = new PackTests("rules");
        PackTests.write(context, "rules", Map.of("assets/pt_rules/polychrome/light_colors/rules.json", """
                { "sets": { "pt_blue": "#2040FF" },
                  "rules": [
                    { "matchBlocks": "minecraft:sea_lantern", "above": "minecraft:red_candle", "color": "#FF2020" },
                    { "matchBlocks": ["minecraft:sea_lantern"], "touching": "#minecraft:wool", "color": "@pt_blue" },
                    { "matchBlocks": "minecraft:glowstone", "below": "minecraft:stone", "north": "minecraft:stone", "color": "#00FF00" },
                    { "above": "minecraft:stone", "color": "#FF00FF" },
                    { "matchBlocks": "minecraft:glowstone", "sideways": "minecraft:stone", "color": "#FF00FF" },
                    { "matchBlocks": "minecraft:not_a_block", "above": "minecraft:stone", "color": "#FF00FF" },
                    { "matchBlocks": "minecraft:shroomlight", "above": "minecraft:stone", "color": "#00FFFF" }
                  ] }
                """));
        PackTests.write(context, "rules_high", Map.of("assets/pt_rules_high/polychrome/light_colors/rules.json", """
                { "rules": [ { "matchBlocks": "minecraft:sea_lantern", "above": "minecraft:red_candle", "color": "#FFFF00" } ] }
                """));
        PackTests.write(context, "plain_high", Map.of("assets/pt_plain_high/polychrome/light_colors/plain.json", """
                { "colors": { "minecraft:sea_lantern": "#0000FF" } }
                """));
        TestWorldSave save;
        try (TestSingleplayerContext singleplayer = PackTests.world(context)) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand(String.format("tp @a 8.5 %d 12.5 180 40", G + 8));
            PackTests.use(context, "rules");
            place(context, server, 0, G, 0, "minecraft:sea_lantern");
            t.close("no neighbor: plain color", PackTests.hue(context, 0, G, 4), SEA_LANTERN);

            place(context, server, 0, G + 1, 0, "minecraft:red_candle");
            t.close("red candle on top", PackTests.hue(context, 0, G, 4), 0xFF2020);
            place(context, server, 0, G + 1, 0, "minecraft:air");
            t.close("candle removed", PackTests.hue(context, 0, G, 4), SEA_LANTERN);
            place(context, server, 0, G + 1, 0, "minecraft:red_candle");
            place(context, server, 1, G, 0, "minecraft:white_wool");
            t.close("both rules match: the first wins", PackTests.hue(context, 0, G, 4), 0xFF2020);
            place(context, server, 0, G + 1, 0, "minecraft:air");
            t.close("candle removed: the wool rule", PackTests.hue(context, 0, G, 4), 0x2040FF);
            place(context, server, 1, G, 0, "minecraft:air");
            t.close("wool removed: plain again", PackTests.hue(context, 0, G, 4), SEA_LANTERN);

            // light placed next to what it needs
            place(context, server, 9, G, 0, "minecraft:red_wool");
            place(context, server, 8, G, 0, "minecraft:sea_lantern");
            t.close("light placed beside its condition", PackTests.hue(context, 8, G, 4), 0x2040FF);

            // every condition of a rule
            place(context, server, 32, G - 1, 0, "minecraft:stone");
            place(context, server, 32, G, 0, "minecraft:glowstone");
            t.close("one of two conditions: plain", PackTests.hue(context, 32, G, 4), GLOWSTONE);
            place(context, server, 32, G, -1, "minecraft:stone");
            t.close("both conditions", PackTests.hue(context, 32, G, 4), 0x00FF00);
            place(context, server, 48, G, 0, "minecraft:shroomlight");
            place(context, server, 48, G + 1, 0, "minecraft:stone");
            t.close("good rule after bad ones in the same file", PackTests.hue(context, 48, G, 4), 0x00FFFF);

            // higher pack's rule is checked first
            place(context, server, 0, G + 1, 0, "minecraft:red_candle");
            PackTests.use(context, "rules", "rules_high");
            t.close("higher pack's rule wins", PackTests.hue(context, 0, G, 4), 0xFFFF00);
            PackTests.use(context, "rules");
            t.close("back to the lower pack's rule", PackTests.hue(context, 0, G, 4), 0xFF2020);

            // a higher pack's plain color says nothing about neighbors, so a lower pack's matching rule still wins
            PackTests.use(context, "rules", "plain_high");
            t.close("lower pack's rule beats a higher plain color", PackTests.hue(context, 0, G, 4), 0xFF2020);
            place(context, server, 0, G + 1, 0, "minecraft:air");
            // light is already there, but right after a reload the game's light update for the change is ~30 ticks late
            PackTests.waitForHue(context, 0, G, 4, 0x0000FF);
            t.close("no rule match: the higher plain color", PackTests.hue(context, 0, G, 4), 0x0000FF);
            place(context, server, 0, G + 1, 0, "minecraft:red_candle");
            PackTests.use(context, "rules");

            // across a chunk border: lantern at x 15, wool at x 16 in the next chunk
            place(context, server, 15, G, 16, "minecraft:sea_lantern");
            place(context, server, 16, G, 16, "minecraft:blue_wool");
            t.close("neighbor in the next chunk", PackTests.hue(context, 15, G, 20), 0x2040FF);
            save = singleplayer.getWorldSave();
        }
        try (TestSingleplayerContext singleplayer = save.open()) {
            singleplayer.getConnection().waitForChunksRender();
            PackTests.waitForLight(context, 15, G, 20);
            t.close("after a relog", PackTests.hue(context, 0, G, 4), 0xFF2020);
            t.close("next chunk after a relog", PackTests.hue(context, 15, G, 20), 0x2040FF);
            PackTests.use(context);
            t.close("pack off: plain", PackTests.hue(context, 0, G, 4), SEA_LANTERN);
        }
        t.done();
    }

    private static void place(ClientGameTestContext context, TestServerContext server, int x, int y, int z, String block) {
        server.runCommand(String.format("setblock %d %d %d %s", x, y, z, block));
        context.waitTicks(2);
        PackTests.waitForLight(context, x, G, z + 4);
    }
}
