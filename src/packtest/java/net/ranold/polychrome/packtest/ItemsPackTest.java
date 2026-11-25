package net.ranold.polychrome.packtest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.api.PolychromeApi;
import net.ranold.polychrome.color.LightColors;

import java.util.Map;

// items and entities keys: written item color, block item falling back to its block, unwritten item white,
// entity color, the public api agreeing, and a held item lighting the floor in its color (LambDynamicLights)
public class ItemsPackTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        PackTests t = new PackTests("items");
        PackTests.write(context, "items", Map.of("assets/pt_items/polychrome/light_colors/items.json", """
                { "colors": { "minecraft:copper_bulb": "#FF00FF" },
                  "items": { "minecraft:torch": "#00FF00", "minecraft:stick": "#0000FF",
                             "#minecraft:candles": "#FF8000", "minecraft:red_candle": "#8000FF" },
                  "entities": { "minecraft:blaze": "#00FFFF" } }
                """));
        try (TestSingleplayerContext singleplayer = PackTests.world(context)) {
            int fire = PackTests.color(context, "minecraft:torch");
            PackTests.use(context, "items");
            t.equal("items key", item(context, "minecraft:torch"), 0x00FF00);
            t.equal("items key does not change the placed block", PackTests.color(context, "minecraft:torch"), fire);
            t.equal("block item falls back to its block", item(context, "minecraft:copper_bulb"), 0xFF00FF);
            t.equal("items key on a non-block item", item(context, "minecraft:stick"), 0x0000FF);
            t.equal("items #tag", item(context, "minecraft:white_candle"), 0xFF8000);
            t.equal("item id beats item tag in the same file", item(context, "minecraft:red_candle"), 0x8000FF);
            t.equal("unwritten item is white", item(context, "minecraft:dirt"), 0xFFFFFF);
            t.equal("built-in item color stays", item(context, "minecraft:blaze_rod"), fire);
            t.equal("entities key", entity(context, "minecraft:blaze") & 0xFFFFFF, 0x00FFFF);
            t.equal("unwritten entity has none", entity(context, "minecraft:zombie"), -1);
            t.equal("api itemColor agrees", context.computeOnClient(client -> PolychromeApi.itemColor(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("minecraft:torch"))))), 0x00FF00);

            if (Polychrome.DYNAMIC_LIGHTS) {
                TestServerContext server = singleplayer.getServer();
                server.runCommand("gamemode creative @a");
                server.runCommand(String.format("tp @a 0.5 %d 0.5 0 60", PackTests.GROUND));
                singleplayer.getConnection().waitForChunksRender();
                context.waitTicks(20);
                server.runCommand("item replace entity @a weapon.mainhand with minecraft:torch");
                for (int i = 0; i < 100 && context.computeOnClient(client -> Polychrome.dynamicLights().length) == 0; i++) {
                    context.waitTick();
                }
                PackTests.settle(context);
                int lights = context.computeOnClient(client -> Polychrome.dynamicLights().length);
                t.that("LambDynamicLights reports the held torch", lights > 0, lights + " dynamic lights");
                t.close("held torch lights the floor in the items color", PackTests.hue(context, 2, PackTests.GROUND, 0), 0x00FF00);
                server.runCommand("item replace entity @a weapon.mainhand with minecraft:air");
            } else {
                t.that("held item in the world", true, "skipped, LambDynamicLights not installed");
            }
            PackTests.use(context);
        }
        t.done();
    }

    private static int item(ClientGameTestContext context, String id) {
        return context.computeOnClient(client -> LightColors.colorOf(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)))) & 0xFFFFFF);
    }

    private static int entity(ClientGameTestContext context, String id) {
        return context.computeOnClient(client -> LightColors.colorOf(BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(id))));
    }
}
