package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.color.LightColors;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

// PolychromeApi looked up the way Torchlit does (by name, publicLookup), so a renamed or changed method fails here.
// values must match the world's. the dimension tint is checked in ResourceReloadGameTest
public class ApiGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        MethodHandle enabled, itemColor, colorAt;
        try {
            Class<?> api = Class.forName("net.ranold.polychrome.api.PolychromeApi");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            enabled = lookup.findStatic(api, "enabled", MethodType.methodType(boolean.class));
            itemColor = lookup.findStatic(api, "itemColor", MethodType.methodType(int.class, ItemStack.class));
            colorAt = lookup.findStatic(api, "colorAt", MethodType.methodType(int.class, BlockPos.class));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("PolychromeApi is not what Torchlit looks up: " + e);
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            context.waitTicks(20);
            String result = context.computeOnClient(client -> {
                try {
                    int soul = (int) itemColor.invokeExact(new ItemStack(Items.SOUL_TORCH)) & 0xFFFFFF;
                    int torch = (int) itemColor.invokeExact(new ItemStack(Items.TORCH)) & 0xFFFFFF;
                    int stone = (int) itemColor.invokeExact(new ItemStack(Items.STONE)) & 0xFFFFFF;
                    int empty = (int) itemColor.invokeExact(ItemStack.EMPTY) & 0xFFFFFF;
                    boolean on = (boolean) enabled.invokeExact();
                    Polychrome.setTintEnabled(false);
                    boolean off = (boolean) enabled.invokeExact();
                    Polychrome.setTintEnabled(true);
                    int worldTorch = LightColors.colorOf(Blocks.TORCH.defaultBlockState()) & 0xFFFFFF;
                    String seen = String.format("soul torch %06X, torch %06X (world %06X), stone %06X, empty %06X, enabled %b, after turning off %b",
                            soul, torch, worldTorch, stone, empty, on, off);
                    boolean ok = soul == 0x58D6FF && torch == worldTorch && stone == 0xFFFFFF && empty == 0xFFFFFF && on && !off;
                    return (ok ? "ok: " : "WRONG: ") + seen;
                } catch (Throwable t) {
                    return "WRONG: threw " + t;
                }
            });
            System.out.println("[polychrome-test] api: " + result);
            if (!result.startsWith("ok")) {
                throw new AssertionError("api: " + result);
            }
            roomColor(context, singleplayer, colorAt);
        }
    }

    // colorAt: soul lantern and torch 24 apart at night, hue beside each must be that light's color, unlit spot
    // and colors off white. then the cost per call on the render thread
    private static void roomColor(ClientGameTestContext context, TestSingleplayerContext singleplayer, MethodHandle colorAt) {
        TestServerContext server = singleplayer.getServer();
        server.runCommand("gamerule advance_time false");
        server.runCommand("time set midnight");
        server.runCommand("gamemode spectator @a");
        server.runCommand("setblock 0 -60 0 minecraft:soul_lantern");
        server.runCommand("setblock 24 -60 0 minecraft:torch");
        server.runCommand("tp @a 12.5 -55 8.5 180 30");
        singleplayer.getConnection().waitForChunksRender();
        context.waitTicks(20);
        for (int i = 0; i < 400 && context.computeOnClient(client -> Polychrome.colorPending()); i++) {
            context.waitTicks(1);
        }
        context.waitTicks(5);
        String result = context.computeOnClient(client -> {
            try {
                int soul = LightColors.colorOf(Blocks.SOUL_LANTERN.defaultBlockState()) & 0xFFFFFF;
                int fire = LightColors.colorOf(Blocks.TORCH.defaultBlockState()) & 0xFFFFFF;
                int nearSoul = (int) colorAt.invokeExact(new BlockPos(2, -60, 1));
                int nearTorch = (int) colorAt.invokeExact(new BlockPos(25, -60, 2));
                int unlit = (int) colorAt.invokeExact(new BlockPos(12, -60, 40));
                int lantern = (int) colorAt.invokeExact(new BlockPos(0, -60, 0));
                Polychrome.setTintEnabled(false);
                int off = (int) colorAt.invokeExact(new BlockPos(2, -60, 1));
                Polychrome.setTintEnabled(true);
                BlockPos at = new BlockPos(2, -60, 1);
                int calls = 100_000, sink = 0;
                long start = System.nanoTime();
                for (int i = 0; i < calls; i++) {
                    sink += (int) colorAt.invokeExact(at);
                }
                double nanos = (System.nanoTime() - start) / (double) calls;
                String seen = String.format("beside soul lantern %06X (expect %06X), beside torch %06X (expect %06X), unlit %06X, lantern's own block %06X, off %06X; %.0f ns per call%s",
                        nearSoul, soul, nearTorch, fire, unlit, lantern, off, nanos, sink == 42 ? "" : "");
                boolean ok = close(nearSoul, soul) && close(nearTorch, fire) && unlit == 0xFFFFFF && off == 0xFFFFFF;
                return (ok ? "ok: " : "WRONG: ") + seen;
            } catch (Throwable t) {
                return "WRONG: threw " + t;
            }
        });
        System.out.println("[polychrome-test] api colorAt: " + result);
        if (!result.startsWith("ok")) {
            throw new AssertionError("api colorAt: " + result);
        }
    }

    // Rgb9e5 rounding
    private static boolean close(int a, int b) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF)) > 4) {
                return false;
            }
        }
        return true;
    }
}
