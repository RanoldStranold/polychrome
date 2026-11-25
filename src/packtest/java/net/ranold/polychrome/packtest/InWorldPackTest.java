package net.ranold.polychrome.packtest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;

import java.util.Map;

// what a player sees, not just the table: lights already placed recolor on reload, lights placed after,
// chunks loaded after (far away), after a relog, and turning the pack off
public class InWorldPackTest implements FabricClientGameTest {

    private static final int G = PackTests.GROUND;

    @Override
    public void runTest(ClientGameTestContext context) {
        PackTests t = new PackTests("in-world");
        PackTests.write(context, "world_a", Map.of("assets/pt_world/polychrome/light_colors/w.json", """
                { "colors": { "minecraft:copper_bulb": "#FF0000" } }
                """));
        PackTests.write(context, "world_b", Map.of("assets/pt_world/polychrome/light_colors/w.json", """
                { "colors": { "minecraft:copper_bulb": "#0000FF" } }
                """));
        TestWorldSave save;
        try (TestSingleplayerContext singleplayer = PackTests.world(context)) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand(String.format("setblock 0 %d 0 minecraft:copper_bulb[lit=true]", G));
            server.runCommand(String.format("tp @a 0.5 %d 6.5 180 40", G + 6));
            PackTests.settle(context);
            t.close("no pack: white", PackTests.hue(context, 2, G, 0), 0xFFFFFF);

            PackTests.use(context, "world_a");
            t.close("placed light recolors on reload", PackTests.hue(context, 2, G, 0), 0xFF0000);

            server.runCommand(String.format("setblock 20 %d 0 minecraft:copper_bulb[lit=true]", G));
            PackTests.waitForLight(context, 22, G, 0);
            t.close("light placed after the reload", PackTests.hue(context, 22, G, 0), 0xFF0000);

            PackTests.use(context, "world_b");
            t.close("switching packs recolors", PackTests.hue(context, 2, G, 0), 0x0000FF);

            // a chunk that was never loaded before the reload (forceload: setblock needs it loaded on the server)
            server.runCommand(String.format("tp @a 400.5 %d 6.5 180 40", G + 6));
            server.runCommand("forceload add 400 0");
            singleplayer.getConnection().waitForChunksRender();
            server.runCommand(String.format("setblock 400 %d 0 minecraft:copper_bulb[lit=true]", G));
            PackTests.waitForLight(context, 402, G, 0);
            t.close("chunk loaded after the reload", PackTests.hue(context, 402, G, 0), 0x0000FF);
            server.runCommand(String.format("tp @a 0.5 %d 6.5 180 40", G + 6));
            singleplayer.getConnection().waitForChunksRender();
            PackTests.settle(context);
            save = singleplayer.getWorldSave();
        }
        try (TestSingleplayerContext singleplayer = save.open()) {
            singleplayer.getConnection().waitForChunksRender();
            PackTests.settle(context);
            t.close("after a relog", PackTests.hue(context, 2, G, 0), 0x0000FF);
            PackTests.use(context);
            t.close("pack off: white again", PackTests.hue(context, 2, G, 0), 0xFFFFFF);
        }
        t.done();
    }
}
