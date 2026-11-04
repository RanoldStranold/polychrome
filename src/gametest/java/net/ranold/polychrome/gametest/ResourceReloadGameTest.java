package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.color.LightColors;
import net.ranold.polychrome.field.Rgb9e5;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

// pack that turns the fire set green, reloaded in a world full of torches: all go green, reload only
// finishes once everything's recolored, unchanged reload does nothing, pack off brings fire back
public class ResourceReloadGameTest implements FabricClientGameTest {

    private static final int GROUND = -60;
    private static final String PACK = "polychrome_test_green_fire";
    private static final int GREEN = 0x30FF30;
    // the pack's fire in the nether
    private static final int NETHER_BLUE = 0x3060FF;

    @Override
    public void runTest(ClientGameTestContext context) {
        writePack(context);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set midnight");
            server.runCommand("gamemode spectator @a");
            for (int x = -96; x <= 96; x += 24) {
                for (int z = -96; z <= 96; z += 24) {
                    server.runCommand(String.format("setblock %d %d %d minecraft:torch", x, GROUND, z));
                }
            }
            server.runCommand(String.format("tp @a 0.5 %d 0.5", GROUND + 10));
            singleplayer.getConnection().waitForChunksRender();
            settle(context);
            int fire = context.computeOnClient(client -> LightColors.sourceOf(Blocks.TORCH.defaultBlockState()) & 0xFFFFFF);
            System.out.printf("[polychrome-test] reload: torch color before %06X, floor %s%n", fire, floor(context));

            String id = context.computeOnClient(client -> {
                client.getResourcePackRepository().reload();
                return client.getResourcePackRepository().getAvailableIds().stream().filter(p -> p.contains(PACK)).findFirst().orElse("");
            });
            if (id.isEmpty()) {
                throw new AssertionError("test resource pack not found");
            }
            long reloadMs = reloadWith(context, id, true);
            // has to be complete the moment the reload's done
            int pending = context.computeOnClient(client -> Polychrome.volume().pendingUploads());
            String floorAfter = floor(context);
            int torch = context.computeOnClient(client -> LightColors.sourceOf(Blocks.TORCH.defaultBlockState()) & 0xFFFFFF);
            long recolor = context.computeOnClient(client -> Polychrome.lastRecolorMillis());
            context.waitTicks(1);
            int pendingNextFrame = context.computeOnClient(client -> Polychrome.volume().pendingUploads());
            System.out.printf("[polychrome-test] reload: pack on in %d ms (recoloring %d ms); torch color %06X; floor %s; waiting uploads %d, after one frame %d; %s%n",
                    reloadMs, recolor, torch, floorAfter, pending, pendingNextFrame, Polychrome.volume().stats());
            if (torch != GREEN) {
                throw new AssertionError(String.format("torch color %06X after the pack, expected %06X", torch, GREEN));
            }
            if (recolor < 0) {
                throw new AssertionError("the color change did not recolor the world");
            }
            if (!floorAfter.startsWith("green") || pendingNextFrame != 0) {
                throw new AssertionError("the world was not fully recolored when the reload finished: floor " + floorAfter + ", uploads waiting " + pendingNextFrame);
            }

            // per dimension: nether fire is blue, back in the overworld it's green again
            server.runCommand("execute in minecraft:the_nether run tp @a 0.5 100 0.5");
            context.waitTicks(40);
            int nether = context.computeOnClient(client -> LightColors.sourceOf(Blocks.TORCH.defaultBlockState()) & 0xFFFFFF);
            // the end's tint: green fire moved toward purple
            server.runCommand("execute in minecraft:the_end run tp @a 0.5 100 0.5");
            context.waitTicks(40);
            int end = context.computeOnClient(client -> LightColors.sourceOf(Blocks.TORCH.defaultBlockState()) & 0xFFFFFF);
            int endWhite = context.computeOnClient(client -> LightColors.white());
            System.out.printf("[polychrome-test] reload: torch color in the end %06X, untinted-white lights %06X%n", end, endWhite);
            if (end != LightColors.mix(GREEN, 0xA050FF, 0.4) || endWhite != LightColors.mix(0xFFFFFF, 0xA050FF, 0.4)) {
                throw new AssertionError(String.format("end tint: torch %06X, white %06X", end, endWhite));
            }
            server.runCommand(String.format("execute in minecraft:overworld run tp @a 0.5 %d 0.5", GROUND + 10));
            context.waitTicks(40);
            settle(context);
            int overworld = context.computeOnClient(client -> LightColors.sourceOf(Blocks.TORCH.defaultBlockState()) & 0xFFFFFF);
            System.out.printf("[polychrome-test] reload: torch color in the nether %06X, back in the overworld %06X, floor %s%n",
                    nether, overworld, floor(context));
            if (nether != NETHER_BLUE || overworld != GREEN || LightColors.white() != 0xFFFFFF) {
                throw new AssertionError(String.format("per dimension color: nether %06X (expected %06X), overworld %06X (expected %06X)",
                        nether, NETHER_BLUE, overworld, GREEN));
            }

            reloadWith(context, id, true);
            long again = context.computeOnClient(client -> Polychrome.lastRecolorMillis());
            System.out.printf("[polychrome-test] reload: again with nothing changed, recoloring %d ms%n", again);
            if (again != -1) {
                throw new AssertionError("an unchanged reload recolored anyway");
            }

            reloadWith(context, id, false);
            int back = context.computeOnClient(client -> LightColors.sourceOf(Blocks.TORCH.defaultBlockState()) & 0xFFFFFF);
            context.waitTicks(1);
            System.out.printf("[polychrome-test] reload: pack off, torch color %06X, floor %s%n", back, floor(context));
            if (back != fire) {
                throw new AssertionError(String.format("torch color %06X after removing the pack, expected %06X", back, fire));
            }
        }
    }

    private static void settle(ClientGameTestContext context) {
        context.waitTicks(20);
        for (int i = 0; i < 400 && context.computeOnClient(client -> Polychrome.colorPending()); i++) {
            context.waitTicks(1);
        }
        context.waitTicks(5);
    }

    // returns reload ms
    private static long reloadWith(ClientGameTestContext context, String id, boolean on) {
        long start = System.nanoTime();
        CompletableFuture<Void> reload = context.computeOnClient(client -> {
            List<String> selected = new ArrayList<>(client.getResourcePackRepository().getSelectedIds());
            selected.remove(id);
            if (on) {
                selected.add(id);
            }
            client.getResourcePackRepository().setSelected(selected);
            return client.reloadResourcePacks();
        });
        for (int i = 0; i < 3000 && !reload.isDone(); i++) {
            context.waitTicks(1);
        }
        if (!reload.isDone()) {
            throw new AssertionError("resource reload did not finish");
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    // floor next to the torch at origin
    private static String floor(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            int packed = Polychrome.volume().packedAt(2, GROUND, 0);
            if (packed == 0) {
                return "none";
            }
            float[] c = Rgb9e5.unpack(packed);
            float max = Math.max(c[0], Math.max(c[1], c[2]));
            int rgb = Math.round(c[0] / max * 255) << 16 | Math.round(c[1] / max * 255) << 8 | Math.round(c[2] / max * 255);
            boolean green = c[1] >= max * 0.99f && c[0] < max * 0.5f;
            return (green ? "green " : "") + String.format("#%06X", rgb);
        });
    }

    private static void writePack(ClientGameTestContext context) {
        Path dir = context.computeOnClient(client -> client.getResourcePackDirectory()).resolve(PACK);
        try {
            Files.createDirectories(dir.resolve("assets/polychrome_test/polychrome/light_colors"));
            Files.writeString(dir.resolve("pack.mcmeta"), """
                    { "pack": { "description": "Polychrome test: green fire", "min_format": 97, "max_format": 97 } }
                    """);
            Files.writeString(dir.resolve("assets/polychrome_test/polychrome/light_colors/fire.json"), """
                    { "sets": { "fire": "#30FF30" },
                      "dimensions": { "minecraft:the_nether": { "sets": { "fire": "#3060FF" } },
                                      "minecraft:the_end": { "tint": "#A050FF", "tint_amount": 0.4 } } }
                    """);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
