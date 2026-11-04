package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.ranold.polychrome.Polychrome;

// dark stone hall with colored lights, screenshots to build/run/clientGameTest/screenshots
public class PolychromeGameTest implements FabricClientGameTest {

    private static final int FLOOR = -61;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            singleplayer.getConnection().waitForChunksRender();
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule advance_weather false");
            server.runCommand("gamemode spectator @a");

            // outdoors at noon, no tint expected (sky light wins)
            server.runCommand(String.format("setblock 3 %d 0 redstone_torch", FLOOR + 1));
            server.runCommand(String.format("setblock -3 %d 0 soul_torch", FLOOR + 1));
            server.runCommand("time set noon");
            server.runCommand(String.format("tp @a 0.5 %d -6.5 0 25", FLOOR + 3));
            context.waitTicks(60);
            context.takeScreenshot("cl-01-outdoor-noon");

            // closed hall at night, 4 colors along a wall + a mix in the middle
            server.runCommand("time set midnight");
            server.runCommand(String.format("fill -14 %d -14 14 %d 14 stone hollow", FLOOR - 1, FLOOR + 9));
            server.runCommand(String.format("setblock -9 %d 8 redstone_torch", FLOOR));
            server.runCommand(String.format("setblock -3 %d 8 soul_torch", FLOOR));
            server.runCommand(String.format("setblock 3 %d 8 torch", FLOOR));
            server.runCommand(String.format("setblock 9 %d 8 copper_torch", FLOOR));
            server.runCommand(String.format("setblock -2 %d 0 redstone_lamp[lit=true]", FLOOR));
            server.runCommand(String.format("setblock 2 %d 0 sea_lantern", FLOOR));
            server.runCommand(String.format("summon armor_stand 0 %d 2", FLOOR));
            server.runCommand(String.format("tp @a 0.5 %d -10.5 0 15", FLOOR + 5));
            context.waitTicks(80);
            log(context, "hall");
            context.takeScreenshot("cl-02-hall-night");

            // lights removed, color has to go away completely
            server.runCommand(String.format("fill -13 %d -13 13 %d 13 air replace", FLOOR, FLOOR + 8));
            server.runCommand(String.format("setblock 0 %d 0 torch", FLOOR));
            server.runCommand("kill @e[type=armor_stand]");
            context.waitTicks(80);
            log(context, "after removal");
            context.takeScreenshot("cl-03-hall-plain-torch");
        }
    }

    private static void log(ClientGameTestContext context, String scene) {
        String stats = context.computeOnClient(client -> "known sections " + Polychrome.volume().knownSections()
                + ", resident " + Polychrome.volume().residentCount());
        System.out.printf("[polychrome-test] %s: %s%n", scene, stats);
    }
}
