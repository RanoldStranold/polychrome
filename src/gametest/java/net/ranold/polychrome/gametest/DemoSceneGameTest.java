package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.ranold.polychrome.Polychrome;

// diagnostic, not listed: a row of vanilla lights on stone at night for showcase screenshots, color on and off
public class DemoSceneGameTest implements FabricClientGameTest {

    private static final int GROUND = -60;
    private static final String[] LIGHTS = {
            "redstone_torch", "torch", "campfire", "lava", "glowstone", "copper_torch",
            "soul_torch", "sea_lantern", "amethyst_cluster", "crying_obsidian", "end_rod"};

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule advance_weather false");
            server.runCommand("time set midnight");
            server.runCommand("gamemode spectator @a");
            int width = LIGHTS.length * 5;
            server.runCommand(String.format("fill -4 %d -8 %d %d 6 minecraft:smooth_stone", GROUND - 1, width, GROUND - 1));
            server.runCommand(String.format("fill -4 %d -8 %d %d -8 minecraft:stone_bricks", GROUND, width, GROUND + 6));
            for (int i = 0; i < LIGHTS.length; i++) {
                int x = i * 5;
                String light = LIGHTS[i];
                if (light.equals("lava")) {
                    server.runCommand(String.format("setblock %d %d -4 minecraft:lava", x, GROUND - 1));
                } else {
                    server.runCommand(String.format("setblock %d %d -4 minecraft:%s", x, GROUND, light));
                }
            }
            // water in front of the lights, for translucent terrain
            server.runCommand(String.format("fill -4 %d 0 %d %d 2 minecraft:water", GROUND - 1, width, GROUND - 1));
            server.runCommand(String.format(java.util.Locale.ROOT, "tp @a %.1f %d 19.5 180 24", width / 2.0 - 2, GROUND + 9));
            context.runOnClient(client -> {
                if (!client.gui.hud.isHidden()) {
                    client.gui.hud.toggle();
                }
            });
            singleplayer.getConnection().waitForChunksRender();
            context.waitTicks(40);
            for (int i = 0; i < 400 && context.computeOnClient(client -> Polychrome.colorPending()); i++) {
                context.waitTicks(1);
            }
            context.waitTicks(20);
            context.takeScreenshot("demo-colored");
            context.runOnClient(client -> Polychrome.setTintEnabled(false));
            context.waitTicks(10);
            context.takeScreenshot("demo-vanilla");
            context.runOnClient(client -> {
                Polychrome.setTintEnabled(true);
                if (client.gui.hud.isHidden()) {
                    client.gui.hud.toggle();
                }
            });
        }
    }
}
