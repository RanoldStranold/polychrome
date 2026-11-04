package net.ranold.polychrome;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.api.ClientModInitializer;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import net.ranold.polychrome.color.LightColors;
import net.ranold.polychrome.compat.DynamicLightsCompat;
import net.ranold.polychrome.far.FarColors;
import net.ranold.polychrome.far.FarIngest;
import net.ranold.polychrome.field.DynamicLight;
import net.ranold.polychrome.field.HueWorker;
import net.ranold.polychrome.gpu.LightVolume;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.server.packs.resources.PreparableReloadListener;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import java.nio.file.Path;

// client entrypoint, glue between the game, the hue worker and the light volume. all render thread
public final class Polychrome implements ClientModInitializer {

    public static final String MOD_ID = "polychrome";
    public static final Logger LOGGER = LoggerFactory.getLogger("Polychrome");

    private static final LightVolume VOLUME = new LightVolume();
    // null unless a LOD mod that reads it is installed
    private static final @Nullable FarColors FAR = FabricLoader.getInstance().isModLoaded("voxy") ? new FarColors() : null;
    // colors chunks that get generated but never loaded (chunky etc)
    private static volatile @Nullable FarIngest farIngest;
    private static volatile @Nullable HueWorker worker;
    private static @Nullable ClientLevel workerLevel;
    // kept so everything can be rescanned after a resource reload
    private static final LongSet loadedChunks = new LongOpenHashSet();
    private static volatile boolean tintEnabled = Settings.loadEnabled();
    // block light changed since last frame
    private static final LongSet lightDirty = new LongOpenHashSet();
    private static long focus = Long.MIN_VALUE;
    public static final boolean DYNAMIC_LIGHTS = FabricLoader.getInstance().isModLoaded("lambdynlights");
    private static DynamicLight[] dynamicLights = new DynamicLight[0];
    private static long dynamicNanos;

    @Override
    public void onInitializeClient() {
        // colors load off thread and install on main. in a world the reload screen waits until every chunk is recolored
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                Identifier.fromNamespaceAndPath(MOD_ID, "light_colors"),
                (PreparableReloadListener) (state, prepare, barrier, apply) ->
                        CompletableFuture.supplyAsync(() -> LightColors.read(state.resourceManager()), prepare)
                                .thenCompose(barrier::wait)
                                .thenComposeAsync(Polychrome::colorsReloaded, apply));

        // dev only: K flips the setting for quick A/B looks, J is the frame cost benchmark
        boolean dev = FabricLoader.getInstance().isDevelopmentEnvironment();
        KeyMapping toggle = dev ? KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.polychrome.toggle", InputConstants.Type.KEYBOARD, InputConstants.KEY_K, KeyMapping.Category.MISC)) : null;
        KeyMapping benchmark = dev ? KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.polychrome.benchmark", InputConstants.Type.KEYBOARD, InputConstants.KEY_J, KeyMapping.Category.MISC)) : null;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // left the world. disconnect event isnt always in time, stop() also saves distant color
            if (client.level == null && worker != null) {
                stop();
            }
            if (DYNAMIC_LIGHTS && worker != null && client.level != null) {
                long start = System.nanoTime();
                DynamicLight[] lights = DynamicLightsCompat.gather();
                if (!Arrays.equals(lights, dynamicLights)) {
                    dynamicLights = lights;
                    worker.dynamicLights(lights);
                }
                dynamicNanos += System.nanoTime() - start;
            }
            while (toggle != null && toggle.consumeClick()) {
                Settings.setEnabled(!tintEnabled);
                if (client.player != null) {
                    client.player.sendOverlayMessage(Component.literal("Colored lighting: " + (tintEnabled ? "on" : "off")));
                }
            }
            while (benchmark != null && benchmark.consumeClick()) {
                if (!FrameBenchmark.running() && client.level != null) {
                    FrameBenchmark.start();
                }
            }
        });

        ClientChunkEvents.CHUNK_LOAD.register(Polychrome::chunkLoaded);
        ClientChunkEvents.CHUNK_UNLOAD.register(Polychrome::chunkUnloaded);
        // run now if already on render thread, queued tasks dont always run before the next world and far color has to save
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (client.isSameThread()) {
                stop();
            } else {
                client.execute(Polychrome::stop);
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stop());
    }

    // not saved, see Settings.setEnabled for that
    public static void setTintEnabled(boolean enabled) {
        tintEnabled = enabled;
    }

    public static boolean tintEnabled() {
        return tintEnabled;
    }

    public static LightVolume volume() {
        return VOLUME;
    }

    public static @Nullable FarColors far() {
        return FAR;
    }

    public static @Nullable FarIngest farIngest() {
        return farIngest;
    }

    // diagnostics
    public static boolean colorPending() {
        return !lightDirty.isEmpty() || (worker != null && worker.isBusy()) || VOLUME.pendingUploads() > 0 || (FAR != null && FAR.pending());
    }

    public static long workerPasses() {
        return worker == null ? 0 : worker.passes();
    }

    public static String workerTimings() {
        return worker == null ? "no worker" : worker.timings();
    }

    public static DynamicLight[] dynamicLights() {
        return dynamicLights;
    }

    public static double dynamicMillis() {
        return dynamicNanos / 1_000_000.0;
    }

    public static long workerMillis() {
        return worker == null ? 0 : worker.workNanos() / 1_000_000;
    }


    private static void chunkLoaded(ClientLevel level, LevelChunk chunk) {
        HueWorker hue = workerFor(level);
        loadedChunks.add(chunk.getPos().pack());
        hue.chunkLoaded(level, chunk);
    }

    // from the light engine callback, copied once per frame
    public static void lightChanged(long sectionKey) {
        if (worker != null) {
            lightDirty.add(sectionKey);
        }
    }

    private static void chunkUnloaded(ClientLevel level, LevelChunk chunk) {
        if (worker == null || level != workerLevel) {
            return;
        }
        loadedChunks.remove(chunk.getPos().pack());
        worker.chunkUnloaded(chunk.getPos().x(), chunk.getPos().z(), chunk.getMinSectionY(), chunk.getSectionsCount());
    }

    public static void blockChanged(BlockPos pos, BlockState state) {
        if (worker != null) {
            worker.blockChanged(pos.getX(), pos.getY(), pos.getZ(), state);
        }
    }

    private static HueWorker workerFor(ClientLevel level) {
        if (worker == null || workerLevel != level) {
            stop();
            // each dimension can have its own colors, so the table is redone on a dimension change
            if (!LightColors.isResolvedFor(dimension(level))) {
                long start = System.nanoTime();
                LightColors.resolve(dimension(level));
                LOGGER.info("Light colors for {} in {} ms", dimension(level), (System.nanoTime() - start) / 1_000_000);
            }
            worker = new HueWorker(VOLUME::publish, FAR == null ? null : FAR::publish);
            workerLevel = level;
            if (FAR != null) {
                FAR.open(farFile(level));
                farIngest = new FarIngest(FAR);
            }
            LOGGER.info("[lifecycle] new hue worker for level {}", System.identityHashCode(level));
        }
        return worker;
    }

    private static void stop() {
        LOGGER.info("[lifecycle] stop (worker {}, level {}, client level {})", worker != null,
                workerLevel == null ? 0 : System.identityHashCode(workerLevel),
                Minecraft.getInstance().level == null ? 0 : System.identityHashCode(Minecraft.getInstance().level));
        if (worker != null) {
            worker.shutdown();
        }
        worker = null;
        workerLevel = null;
        loadedChunks.clear();
        lightDirty.clear();
        focus = Long.MIN_VALUE;
        dynamicLights = new DynamicLight[0];
        VOLUME.reset();
        FarIngest ingest = farIngest;
        farIngest = null;
        if (ingest != null) {
            ingest.shutdown();
        }
        if (FAR != null) {
            FAR.close();
        }
    }

    private static String dimension(ClientLevel level) {
        return level.dimension().identifier().toString();
    }

    // singleplayer: inside the world folder, multiplayer: per server address, null if neither
    private static @Nullable Path farFile(ClientLevel level) {
        Minecraft minecraft = Minecraft.getInstance();
        String name = "far_" + level.dimension().identifier().toString().replaceAll("[^a-z0-9_.-]", "_") + ".bin";
        IntegratedServer singleplayer = minecraft.getSingleplayerServer();
        if (singleplayer != null) {
            return singleplayer.getWorldPath(LevelResource.ROOT).normalize().resolve(MOD_ID).resolve(name);
        }
        ServerData server = minecraft.getCurrentServer();
        if (server != null && server.ip != null) {
            return minecraft.gameDirectory.toPath().resolve(MOD_ID).resolve("servers")
                    .resolve(server.ip.toLowerCase().replaceAll("[^a-z0-9_.-]", "_")).resolve(name);
        }
        return null;
    }

    // so a reload can never hang
    private static final long RECOLOR_TIMEOUT_NANOS = 60_000_000_000L;
    private static final ScheduledExecutorService RECOLOR_WATCH = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Polychrome reload watch");
        thread.setDaemon(true);
        return thread;
    });
    // diagnostics, -1 if nothing needed recoloring
    private static volatile long lastRecolorMillis = -1;

    public static long lastRecolorMillis() {
        return lastRecolorMillis;
    }

    // main thread. outside a world just store them. in a world, if any color changed, recolor every
    // loaded chunk and complete when done (1 min cap), otherwise complete now
    private static CompletableFuture<Void> colorsReloaded(LightColors.Loaded loaded) {
        int[] before = LightColors.table();
        LightColors.install(loaded);
        lastRecolorMillis = -1;
        if (Minecraft.getInstance().level == null || worker == null) {
            return CompletableFuture.completedFuture(null);
        }
        LightColors.resolve(dimension(Minecraft.getInstance().level));
        if (before != null && Arrays.equals(before, LightColors.table())) {
            LOGGER.info("Light colors unchanged; nothing to recolor");
            return CompletableFuture.completedFuture(null);
        }
        long start = System.nanoTime();
        rebuild();
        CompletableFuture<Void> done = new CompletableFuture<>();
        int[] idlePolls = {0};
        ScheduledFuture<?>[] watch = new ScheduledFuture<?>[1];
        watch[0] = RECOLOR_WATCH.scheduleAtFixedRate(() -> {
            HueWorker current = worker;
            long elapsed = System.nanoTime() - start;
            // two quiet checks 20ms apart = worker really done
            idlePolls[0] = current == null || !current.isBusy() ? idlePolls[0] + 1 : 0;
            if (idlePolls[0] >= 2 || elapsed > RECOLOR_TIMEOUT_NANOS) {
                lastRecolorMillis = elapsed / 1_000_000;
                if (elapsed > RECOLOR_TIMEOUT_NANOS) {
                    LOGGER.warn("Recoloring after the resource reload took over a minute; continuing without waiting");
                } else {
                    LOGGER.info("Recolored every loaded chunk for the new light colors in {} ms", lastRecolorMillis);
                }
                VOLUME.uploadEverythingNextFrame();
                done.complete(null);
                watch[0].cancel(false);
            }
        }, 20, 20, TimeUnit.MILLISECONDS);
        return done;
    }

    // colors changed, resolve again and rebuild from every loaded chunk
    private static void rebuild() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || worker == null) {
            return;
        }
        long[] chunks = loadedChunks.toLongArray();
        stop();
        LightColors.resolve(dimension(level));
        HueWorker hue = workerFor(level);
        for (long packed : chunks) {
            LevelChunk chunk = level.getChunkSource().getChunk(ChunkPos.getX(packed), ChunkPos.getZ(packed), false);
            if (chunk != null) {
                loadedChunks.add(packed);
                hue.chunkLoaded(level, chunk);
            }
        }
    }


    // ns between frames, null when not recording
    private static long @Nullable [] frameTimes;
    private static int frameCount;
    private static long lastFrame;

    public static void recordFrames(int max) {
        frameTimes = new long[max];
        frameCount = 0;
        lastFrame = 0;
    }

    public static long[] recordedFrames() {
        long[] times = frameTimes == null ? new long[0] : Arrays.copyOf(frameTimes, frameCount);
        frameTimes = null;
        return times;
    }

    public static void onFrame(Vec3 camera) {
        FrameBenchmark.onFrame();
        if (frameTimes != null) {
            long now = System.nanoTime();
            if (lastFrame != 0 && frameCount < frameTimes.length) {
                frameTimes[frameCount++] = now - lastFrame;
            }
            lastFrame = now;
        }
        if (worker != null) {
            long section = SectionPos.asLong(SectionPos.posToSectionCoord(camera.x), SectionPos.posToSectionCoord(camera.y),
                    SectionPos.posToSectionCoord(camera.z));
            if (section != focus) {
                focus = section;
                worker.focus(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
            }
        }
        if (worker != null && workerLevel != null && !lightDirty.isEmpty()) {
            var it = lightDirty.iterator();
            while (it.hasNext()) {
                long key = it.nextLong();
                worker.lightChanged(workerLevel, SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
            }
            lightDirty.clear();
        }
        boolean enabled = tintEnabled && Minecraft.getInstance().level != null && worker != null;
        int benchmarkMode = FrameBenchmark.running() ? FrameBenchmark.mode() : 0;
        VOLUME.onFrame(camera, enabled && benchmarkMode != 1 && benchmarkMode != 2);
        if (FAR != null) {
            FAR.onFrame(camera, enabled && benchmarkMode != 1 && benchmarkMode != 3);
        }
    }
}
