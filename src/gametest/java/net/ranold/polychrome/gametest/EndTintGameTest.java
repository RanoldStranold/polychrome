package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.field.Rgb9e5;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// diagnostic, not listed. sea lantern / torches on an end stone floor in the end with a purple tint pack,
// screenshots tint on/off + the same scene in the overworld, prints GPU color going out from the lantern
public class EndTintGameTest implements FabricClientGameTest {

    private static final String PACK = "polychrome_end_tint_test";
    private static final int Y = 80;

    @Override
    public void runTest(ClientGameTestContext context) {
        Path dir = context.computeOnClient(client -> client.getResourcePackDirectory()).resolve(PACK);
        try {
            Files.createDirectories(dir.resolve("assets/endtint/polychrome/light_colors"));
            Files.writeString(dir.resolve("pack.mcmeta"), """
                    { "pack": { "description": "end tint test", "min_format": 97, "max_format": 97 } }
                    """);
            Files.writeString(dir.resolve("assets/endtint/polychrome/light_colors/end.json"), """
                    { "dimensions": { "minecraft:the_end": { "tint": "#A050FF", "tint_amount": 0.65 } } }
                    """);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            String id = context.computeOnClient(client -> {
                client.getResourcePackRepository().reload();
                return client.getResourcePackRepository().getAvailableIds().stream().filter(p -> p.contains(PACK)).findFirst().orElse("");
            });
            context.computeOnClient(client -> {
                List<String> selected = new ArrayList<>(client.getResourcePackRepository().getSelectedIds());
                selected.add(id);
                client.getResourcePackRepository().setSelected(selected);
                return client.reloadResourcePacks();
            });
            context.waitTicks(40);
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set midnight");
            server.runCommand("gamemode spectator @a");
            scene(context, server, "the_end");
            scene(context, server, "overworld");
        }
    }

    private static void scene(ClientGameTestContext context, TestServerContext server, String dim) {
        String in = "execute in minecraft:" + dim + " run ";
        server.runCommand(in + String.format("fill 90 %d -10 120 %d 10 minecraft:air", Y - 1, Y + 12));
        server.runCommand(in + String.format("fill 90 %d -10 120 %d 10 minecraft:end_stone", Y - 1, Y - 1));
        server.runCommand(in + String.format("setblock 100 %d 0 minecraft:sea_lantern", Y));
        server.runCommand(in + String.format("setblock 110 %d 0 minecraft:copper_torch", Y));
        server.runCommand(in + String.format("setblock 105 %d 6 minecraft:torch", Y));
        server.runCommand(in + String.format("tp @a 105.5 %d -6.5 0 45", Y + 7));
        context.waitTicks(100);
        for (int i = 0; i < 400 && context.computeOnClient(client -> Polychrome.colorPending()); i++) {
            context.waitTick();
        }
        context.waitTicks(20);
        StringBuilder line = new StringBuilder();
        for (int d = 1; d <= 8; d++) {
            int x = 100 + d;
            int packed = context.computeOnClient(client -> Polychrome.volume().packedAt(x, Y, 0));
            int light = context.computeOnClient(client -> client.level.getBrightness(LightLayer.BLOCK, new BlockPos(x, Y, 0)));
            line.append(String.format(" +%d L%d %s;", d, light, color(packed)));
        }
        System.out.printf("[polychrome-test] end tint, %s, from the sea lantern east:%s%n", dim, line);
        context.takeScreenshot("endtint-" + dim + "-on");
        context.runOnClient(client -> Polychrome.setTintEnabled(false));
        context.waitTicks(5);
        context.takeScreenshot("endtint-" + dim + "-off");
        context.runOnClient(client -> Polychrome.setTintEnabled(true));
    }

    private static String color(int packed) {
        if (packed == 0) {
            return "none";
        }
        float[] c = Rgb9e5.unpack(packed);
        float max = Math.max(c[0], Math.max(c[1], c[2]));
        return String.format("#%06X", Math.round(c[0] / max * 255) << 16 | Math.round(c[1] / max * 255) << 8 | Math.round(c[2] / max * 255));
    }
}
