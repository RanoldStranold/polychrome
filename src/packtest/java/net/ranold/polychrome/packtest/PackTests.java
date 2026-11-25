package net.ranold.polychrome.packtest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.color.LightColors;
import net.ranold.polychrome.field.Rgb9e5;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

// shared steps for the resource pack suite: real packs written to the pack folder, switched on in a set order
// (last = highest), reloaded like a player would. every check is collected so one run shows every problem
public final class PackTests {

    public static final int GROUND = -60;
    // all test packs start with this, so turning packs on and off never touches anything else
    private static final String PREFIX = "polychrome_pt_";

    private final String test;
    private final List<String> failures = new ArrayList<>();

    public PackTests(String test) {
        this.test = test;
    }

    // folder pack. files: path inside the pack -> text
    public static void write(ClientGameTestContext context, String name, Map<String, String> files) {
        Path dir = packDir(context, name);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("pack.mcmeta"),
                    "{ \"pack\": { \"description\": \"Polychrome pack test " + name + "\", \"min_format\": 97, \"max_format\": 97 } }\n");
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path path = dir.resolve(file.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, file.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // single color 16x16 png inside a pack
    public static void writeTexture(ClientGameTestContext context, String name, String path, int rgb) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, 0xFF000000 | rgb);
            }
        }
        Path file = packDir(context, name).resolve(path);
        try {
            Files.createDirectories(file.getParent());
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path packDir(ClientGameTestContext context, String name) {
        return context.computeOnClient(client -> client.getResourcePackDirectory()).resolve(PREFIX + name);
    }

    // exactly these test packs on, lowest first, then a full reload. waits until recoloring is done
    public static void use(ClientGameTestContext context, String... names) {
        CompletableFuture<Void> reload = context.computeOnClient(client -> {
            client.getResourcePackRepository().reload();
            List<String> selected = new ArrayList<>(client.getResourcePackRepository().getSelectedIds());
            selected.removeIf(id -> id.contains(PREFIX));
            for (String name : names) {
                String id = client.getResourcePackRepository().getAvailableIds().stream()
                        .filter(available -> available.endsWith(PREFIX + name)).findFirst()
                        .orElseThrow(() -> new AssertionError("test pack " + name + " not found"));
                selected.add(id);
            }
            client.getResourcePackRepository().setSelected(selected);
            return client.reloadResourcePacks();
        });
        for (int i = 0; i < 3000 && !reload.isDone(); i++) {
            context.waitTick();
        }
        if (!reload.isDone()) {
            throw new AssertionError("resource reload did not finish");
        }
        settle(context);
    }

    // flat world at midnight, spectator, nothing else
    public static TestSingleplayerContext world(ClientGameTestContext context) {
        TestSingleplayerContext singleplayer = context.worldBuilder().create();
        TestServerContext server = singleplayer.getServer();
        server.runCommand("gamerule advance_time false");
        server.runCommand("gamerule advance_weather false");
        server.runCommand("time set midnight");
        server.runCommand("gamemode spectator @a");
        singleplayer.getConnection().waitForChunksRender();
        settle(context);
        return singleplayer;
    }

    public static void settle(ClientGameTestContext context) {
        context.waitTicks(5);
        for (int i = 0; i < 400 && context.computeOnClient(client -> Polychrome.colorPending()); i++) {
            context.waitTick();
        }
        context.waitTicks(3);
    }

    // block light reaches client late, ~30 ticks after a reload. wait for it, then color
    public static void waitForLight(ClientGameTestContext context, int x, int y, int z) {
        for (int i = 0; i < 200 && context.computeOnClient(client -> client.level.getBrightness(
                net.minecraft.world.level.LightLayer.BLOCK, new net.minecraft.core.BlockPos(x, y, z))) == 0; i++) {
            context.waitTick();
        }
        settle(context);
    }

    // for a change to an already lit spot: waits up to 100 ticks for the expected hue, the check after still decides
    public static void waitForHue(ClientGameTestContext context, int x, int y, int z, int expected) {
        for (int i = 0; i < 100 && !near(hue(context, x, y, z), expected); i++) {
            context.waitTick();
        }
        settle(context);
    }

    public static BlockState state(String id) {
        int bracket = id.indexOf('[');
        BlockState state = BuiltInRegistries.BLOCK.getValue(Identifier.parse(bracket < 0 ? id : id.substring(0, bracket))).defaultBlockState();
        if (bracket >= 0) {
            for (String pair : id.substring(bracket + 1, id.length() - 1).split(",")) {
                String[] kv = pair.split("=");
                state = with(state, kv[0], kv[1]);
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, String name, String value) {
        @SuppressWarnings("unchecked")
        net.minecraft.world.level.block.state.properties.Property<T> property =
                (net.minecraft.world.level.block.state.properties.Property<T>) state.getBlock().getStateDefinition().getProperty(name);
        return state.setValue(property, property.getValue(value).orElseThrow());
    }

    // color table value for a block state
    public static int color(ClientGameTestContext context, String id) {
        return context.computeOnClient(client -> LightColors.colorOf(state(id)) & 0xFFFFFF);
    }

    // color a light gets from the resolved rules with these neighbors (above, below, north, south, east, west ->
    // block id, unnamed sides air), table only
    public static int ruleColor(ClientGameTestContext context, String light, Map<String, String> around) {
        return context.computeOnClient(client -> LightColors.sourceAt(state(light), side -> state(around.getOrDefault(
                side == net.minecraft.core.Direction.UP ? "above" : side == net.minecraft.core.Direction.DOWN ? "below" : side.getSerializedName(),
                "minecraft:air"))) & 0xFFFFFF);
    }

    // item color as dynamic lights and the api see it
    public static int item(ClientGameTestContext context, String id) {
        return context.computeOnClient(client -> LightColors.colorOf(new net.minecraft.world.item.ItemStack(
                BuiltInRegistries.ITEM.getValue(Identifier.parse(id)))) & 0xFFFFFF);
    }

    // moves the player to another dimension and waits for that dimension's colors
    public static void travel(ClientGameTestContext context, TestServerContext server, String dimension, int y) {
        server.runCommand(String.format("execute in %s run tp @a 0.5 %d 0.5", dimension, y));
        context.waitTicks(40);
        settle(context);
    }

    // hue the world holds at a block (what the GPU gets), brightest = 255, -1 if unlit
    public static int hue(ClientGameTestContext context, int x, int y, int z) {
        return context.computeOnClient(client -> {
            int packed = Polychrome.volume().packedAt(x, y, z);
            if (packed == 0) {
                return -1;
            }
            float[] c = Rgb9e5.unpack(packed);
            float max = Math.max(c[0], Math.max(c[1], c[2]));
            return Math.round(c[0] / max * 255) << 16 | Math.round(c[1] / max * 255) << 8 | Math.round(c[2] / max * 255);
        });
    }

    public void equal(String what, int got, int expected) {
        this.check(what, got == expected, got, expected);
    }

    // GPU format rounds a little
    public void close(String what, int got, int expected) {
        this.check(what, near(got, expected), got, expected);
    }

    private static boolean near(int got, int expected) {
        boolean ok = got >= 0;
        for (int shift = 0; ok && shift <= 16; shift += 8) {
            ok = Math.abs(((got >> shift) & 0xFF) - ((expected >> shift) & 0xFF)) <= 4;
        }
        return ok;
    }

    public void that(String what, boolean ok, String seen) {
        System.out.printf("[polychrome-packtest] %s %s: %s (%s)%n", this.test, ok ? "ok" : "FAIL", what, seen);
        if (!ok) {
            this.failures.add(what + " (" + seen + ")");
        }
    }

    private void check(String what, boolean ok, int got, int expected) {
        this.that(what, ok, String.format("got %s, expected #%06X", got < 0 ? "nothing" : String.format("#%06X", got), expected));
    }

    // throws with every failed check
    public void done() {
        if (!this.failures.isEmpty()) {
            throw new AssertionError(this.test + ": " + this.failures.size() + " failed: " + String.join("; ", this.failures));
        }
    }
}
