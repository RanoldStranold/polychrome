package net.ranold.polychrome.packtest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.ItemStack;
import net.ranold.polychrome.color.LightColors;

import java.util.Map;

// dimensions section: colors, sets and items per dimension on top of the same pack's base, a higher pack's
// base beating a lower pack's dimension key, tint + tint_amount moving every light (and plain white) toward
// one color, and the overworld untouched
public class DimensionsPackTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        PackTests t = new PackTests("dimensions");
        PackTests.write(context, "dimensions", Map.of("assets/pt_dim/polychrome/light_colors/dim.json", """
                { "colors": { "minecraft:copper_bulb": "#FF0000", "minecraft:exposed_copper_bulb": "#FF8000" },
                  "dimensions": {
                    "minecraft:the_nether": {
                      "colors": { "minecraft:copper_bulb": "#00FF00" },
                      "sets": { "fire": "#0000FF" },
                      "items": { "minecraft:stick": "#FFFF00" }
                    },
                    "minecraft:the_end": { "tint": "#FF00FF", "tint_amount": 0.5 }
                  } }
                """));
        PackTests.write(context, "dimensions_low", Map.of("assets/pt_dim_low/polychrome/light_colors/dim.json", """
                { "dimensions": { "minecraft:the_nether": { "colors": { "minecraft:exposed_copper_bulb": "#00FFFF" } } } }
                """));
        try (TestSingleplayerContext singleplayer = PackTests.world(context)) {
            TestServerContext server = singleplayer.getServer();
            int fire = PackTests.color(context, "minecraft:torch");
            PackTests.use(context, "dimensions_low", "dimensions");
            t.equal("overworld uses the base", PackTests.color(context, "minecraft:copper_bulb"), 0xFF0000);

            server.runCommand("execute in minecraft:the_nether run tp @a 0.5 100 0.5");
            context.waitTicks(40);
            t.equal("dimension color", PackTests.color(context, "minecraft:copper_bulb"), 0x00FF00);
            t.equal("dimension set", PackTests.color(context, "minecraft:torch"), 0x0000FF);
            t.equal("dimension items", context.computeOnClient(client -> LightColors.colorOf(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse("minecraft:stick")))) & 0xFFFFFF), 0xFFFF00);
            t.equal("higher pack's base beats a lower pack's dimension key", PackTests.color(context, "minecraft:exposed_copper_bulb"), 0xFF8000);
            t.equal("base keys the dimension does not name stay", PackTests.color(context, "minecraft:soul_torch"), 0x58D6FF);

            server.runCommand("execute in minecraft:the_end run tp @a 0.5 100 0.5");
            context.waitTicks(40);
            t.equal("tint moves colors", PackTests.color(context, "minecraft:copper_bulb"), LightColors.mix(0xFF0000, 0xFF00FF, 0.5));
            t.equal("tint moves white", context.computeOnClient(client -> LightColors.white()), LightColors.mix(0xFFFFFF, 0xFF00FF, 0.5));

            server.runCommand(String.format("execute in minecraft:overworld run tp @a 0.5 %d 0.5", PackTests.GROUND + 10));
            context.waitTicks(40);
            t.equal("back in the overworld", PackTests.color(context, "minecraft:torch"), fire);
            t.equal("no tint in the overworld", context.computeOnClient(client -> LightColors.white()), 0xFFFFFF);
            PackTests.use(context);
        }
        t.done();
    }
}
