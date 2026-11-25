package net.ranold.polychrome.packtest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.Map;

// which blocks a key reaches: block id, block state, #tag. which wins: a higher pack always, whatever
// kind of key; inside one pack the more specific key
public class KeysPackTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        PackTests t = new PackTests("keys");
        PackTests.write(context, "keys", Map.of("assets/pt_keys/polychrome/light_colors/keys.json", """
                { "colors": {
                    "minecraft:copper_bulb": "#FF00FF",
                    "minecraft:redstone_lamp[lit=true]": "#00FF00",
                    "#polychrome-testblocks:test_lights": "#123456",
                    "#minecraft:campfires": "#0000FF",
                    "minecraft:soul_campfire": "#FFFF00",
                    "minecraft:exposed_copper_bulb[lit=true]": "#00FFFF",
                    "minecraft:exposed_copper_bulb": "#FF8000",
                    "minecraft:waxed_copper_bulb": "#0000FF"
                } }
                """));
        PackTests.write(context, "keys_low", Map.of("assets/pt_keys_low/polychrome/light_colors/keys.json", """
                { "colors": { "minecraft:waxed_copper_bulb[lit=true]": "#00FF00" } }
                """));
        try (TestSingleplayerContext ignored = PackTests.world(context)) {
            int lampBefore = PackTests.color(context, "minecraft:redstone_lamp[lit=false]");
            PackTests.use(context, "keys_low", "keys");

            t.equal("block id", PackTests.color(context, "minecraft:copper_bulb[lit=true]"), 0xFF00FF);
            t.equal("block id covers every state", PackTests.color(context, "minecraft:copper_bulb[lit=false]"), 0xFF00FF);
            t.equal("block state key", PackTests.color(context, "minecraft:redstone_lamp[lit=true]"), 0x00FF00);
            t.equal("other states keep the lower color", PackTests.color(context, "minecraft:redstone_lamp[lit=false]"), lampBefore);
            t.equal("state beats block in the same file", PackTests.color(context, "minecraft:exposed_copper_bulb[lit=true]"), 0x00FFFF);
            t.equal("block covers the states the state key misses", PackTests.color(context, "minecraft:exposed_copper_bulb[lit=false]"), 0xFF8000);
            t.equal("tag reaches a block with no color written", PackTests.color(context, "polychrome-testblocks:lime_test_light"), 0x123456);
            t.equal("block beats tag in the same file", PackTests.color(context, "minecraft:soul_campfire"), 0xFFFF00);
            t.equal("higher pack's tag beats a built-in block key", PackTests.color(context, "minecraft:campfire"), 0x0000FF);
            t.equal("higher pack's tag beats a lower mod's block key", PackTests.color(context, "polychrome-testblocks:red_test_light"), 0x123456);
            t.equal("higher pack's block key beats a lower pack's state key", PackTests.color(context, "minecraft:waxed_copper_bulb[lit=true]"), 0x0000FF);
            PackTests.use(context, "keys", "keys_low");
            t.equal("swapped: the state key's pack is higher now", PackTests.color(context, "minecraft:waxed_copper_bulb[lit=true]"), 0x00FF00);
            t.equal("swapped: other states keep the block key", PackTests.color(context, "minecraft:waxed_copper_bulb[lit=false]"), 0x0000FF);

            PackTests.use(context);
            t.equal("pack off restores", PackTests.color(context, "minecraft:copper_bulb[lit=true]"), 0xFFFFFF);
        }
        t.done();
    }
}
