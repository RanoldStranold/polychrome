package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

// not a test. makes a creative world, pregens it with chunky (overworld + nether) with all dev mods,
// copies it to run/saves as "DnT Test". run it alone
public class PregenerateWorldGameTest implements FabricClientGameTest {

    private static final String WORLD_NAME = "DnT Test";
    private static final int OVERWORLD_RADIUS = 1024;
    private static final int NETHER_RADIUS = 512;
    // give up on a dimension after this
    private static final int MAX_MINUTES = 60;

    @Override
    public void runTest(ClientGameTestContext context) {
        Path save;
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(settings -> {
                    settings.setWorldType(settings.getNormalPresetList().getFirst());
                    settings.setName(WORLD_NAME);
                    settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                    settings.setAllowCommands(true);
                })
                .create()) {
            save = singleplayer.getWorldSave().getSaveDirectory();
            TestServerContext server = singleplayer.getServer();
            singleplayer.getConnection().waitForChunksRender();
            generate(context, server, "minecraft:overworld", OVERWORLD_RADIUS);
            generate(context, server, "minecraft:the_nether", NETHER_RADIUS);
            server.runCommand("save-all flush");
            context.waitTicks(40);
        }
        copyToDevSaves(save);
    }

    private static void generate(ClientGameTestContext context, TestServerContext server, String dimension, int radius) {
        long logStart = logLength();
        server.runCommand("chunky world " + dimension);
        server.runCommand("chunky center 0 0");
        server.runCommand("chunky radius " + radius);
        server.runCommand("chunky start");
        System.out.printf("[pregen] %s radius %d started%n", dimension, radius);
        long started = System.currentTimeMillis();
        while (!logSince(logStart).contains("Task finished for " + dimension)) {
            if (System.currentTimeMillis() - started > MAX_MINUTES * 60_000L) {
                throw new AssertionError("Chunky did not finish " + dimension + " within " + MAX_MINUTES + " minutes");
            }
            context.waitTicks(100);
        }
        System.out.printf("[pregen] %s done in %d s%n", dimension, (System.currentTimeMillis() - started) / 1000);
    }

    private static Path log() {
        return Path.of("logs", "latest.log");
    }

    private static long logLength() {
        try {
            return Files.size(log());
        } catch (IOException e) {
            return 0;
        }
    }

    private static String logSince(long offset) {
        try {
            byte[] all = Files.readAllBytes(log());
            return offset >= all.length ? "" : new String(all, (int) offset, all.length - (int) offset);
        } catch (IOException e) {
            return "";
        }
    }

    // gametest dir is build/run/clientGameTest, dev client's is run/
    private static void copyToDevSaves(Path save) {
        Path project = Path.of("").toAbsolutePath().getParent().getParent().getParent();
        Path target = project.resolve("run").resolve("saves").resolve(WORLD_NAME);
        try {
            if (Files.exists(target)) {
                try (Stream<Path> old = Files.walk(target)) {
                    old.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
            try (Stream<Path> files = Files.walk(save)) {
                files.forEach(source -> {
                    if (source.getFileName().toString().equals("session.lock")) {
                        return;
                    }
                    Path dest = target.resolve(save.relativize(source).toString());
                    try {
                        if (Files.isDirectory(source)) {
                            Files.createDirectories(dest);
                        } else {
                            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.printf("[pregen] copied to %s%n", target);
    }
}
