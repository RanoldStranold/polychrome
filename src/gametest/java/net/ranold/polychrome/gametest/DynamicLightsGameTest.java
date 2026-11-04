package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.level.block.Blocks;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.color.LightColors;
import net.ranold.polychrome.field.Rgb9e5;

// LambDynamicLights (skipped without it), flat world at night: held soul torch / torch color the
// floor, torch next to a placed soul lantern mixes, nothing left after putting it away, cost while walking
public class DynamicLightsGameTest implements FabricClientGameTest {

    private static final int GROUND = -60;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Polychrome.DYNAMIC_LIGHTS) {
            System.out.println("[polychrome-test] dynamic lights: LambDynamicLights not installed, skipped");
            return;
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set midnight");
            server.runCommand("gamemode creative @a");
            server.runCommand(String.format("tp @a 0.5 %d 0.5 0 60", GROUND));
            singleplayer.getConnection().waitForChunksRender();
            context.waitTicks(20);

            int soul = context.computeOnClient(client -> LightColors.colorOf(Blocks.SOUL_TORCH.defaultBlockState()));
            int fire = context.computeOnClient(client -> LightColors.colorOf(Blocks.TORCH.defaultBlockState()));

            hold(context, server, "minecraft:soul_torch");
            String soulFloor = floor(context, 2, 0);
            context.takeScreenshot("dynamic-soul-torch");
            hold(context, server, "minecraft:torch");
            String fireFloor = floor(context, 2, 0);
            context.takeScreenshot("dynamic-torch");
            System.out.printf("[polychrome-test] dynamic: soul torch %s (expect #%06X), torch %s (expect #%06X); lights %d%n",
                    soulFloor, soul, fireFloor, fire, context.computeOnClient(client -> Polychrome.dynamicLights().length));
            if (!close(soulFloor, soul) || !close(fireFloor, fire)) {
                throw new AssertionError("held lights did not color the floor: soul " + soulFloor + ", torch " + fireFloor);
            }

            // soul lantern 10 east, player with a torch at origin
            server.runCommand(String.format("setblock 10 %d 0 minecraft:soul_lantern", GROUND));
            context.waitTicks(10);
            settle(context);
            String nearPlayer = floor(context, 1, 0), nearLantern = floor(context, 9, 0);
            context.takeScreenshot("dynamic-torch-beside-soul-lantern");
            System.out.printf("[polychrome-test] dynamic: beside a soul lantern, near the player %s, near the lantern %s%n", nearPlayer, nearLantern);
            if (!reddish(nearPlayer) || reddish(nearLantern)) {
                throw new AssertionError("torch and lantern colors in the wrong places: " + nearPlayer + " / " + nearLantern);
            }
            server.runCommand(String.format("setblock 10 %d 0 minecraft:air", GROUND));

            hold(context, server, "minecraft:air");
            String dark = floor(context, 2, 0);
            System.out.printf("[polychrome-test] dynamic: torch put away, floor %s%n", dark);
            if (!dark.equals("none")) {
                throw new AssertionError("color left behind after the dynamic light went: " + dark);
            }

            // cost: walk a circle with a torch for 200 ticks
            hold(context, server, "minecraft:torch");
            double readBefore = context.computeOnClient(client -> Polychrome.dynamicMillis());
            long workBefore = context.computeOnClient(client -> Polychrome.workerMillis());
            int ticks = 200;
            for (int t = 0; t < ticks; t++) {
                double angle = t * 0.08;
                server.runCommand(String.format(java.util.Locale.ROOT, "tp @a %.2f %d %.2f 0 60", 0.5 + 6 * Math.cos(angle), GROUND, 0.5 + 6 * Math.sin(angle)));
                context.waitTicks(1);
            }
            double read = context.computeOnClient(client -> Polychrome.dynamicMillis()) - readBefore;
            long work = context.computeOnClient(client -> Polychrome.workerMillis()) - workBefore;
            System.out.printf("[polychrome-perf] dynamic lights: walking with a torch for %d ticks: reading lights %.3f ms per tick on the game thread, worker %.2f ms per tick%n",
                    ticks, read / ticks, (double) work / ticks);
        }
    }

    private static void hold(ClientGameTestContext context, TestServerContext server, String item) {
        server.runCommand("item replace entity @a weapon.mainhand with " + item);
        context.waitTicks(5);
        settle(context);
    }

    private static void settle(ClientGameTestContext context) {
        for (int i = 0; i < 100 && context.computeOnClient(client -> Polychrome.colorPending()); i++) {
            context.waitTicks(1);
        }
        context.waitTicks(2);
    }

    // within 4 per channel, GPU format rounds a bit
    private static boolean close(String color, int rgb) {
        if (!color.startsWith("#")) {
            return false;
        }
        int got = Integer.parseInt(color.substring(1), 16);
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs(((got >> shift) & 0xFF) - ((rgb >> shift) & 0xFF)) > 4) {
                return false;
            }
        }
        return true;
    }

    private static boolean reddish(String color) {
        if (!color.startsWith("#")) {
            return false;
        }
        int rgb = Integer.parseInt(color.substring(1), 16);
        return ((rgb >> 16) & 0xFF) > (rgb & 0xFF);
    }

    // GPU color at a floor block, brightest = 255, or "none"
    private static String floor(ClientGameTestContext context, int x, int z) {
        return context.computeOnClient(client -> {
            int packed = Polychrome.volume().packedAt(x, GROUND, z);
            if (packed == 0) {
                return "none";
            }
            float[] c = Rgb9e5.unpack(packed);
            float max = Math.max(c[0], Math.max(c[1], c[2]));
            return String.format("#%06X", Math.round(c[0] / max * 255) << 16 | Math.round(c[1] / max * 255) << 8 | Math.round(c[2] / max * 255));
        });
    }
}
