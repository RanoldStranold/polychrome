package net.ranold.polychrome.field;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.color.LightColors;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.LinkedBlockingQueue;

// runs a HueField on its own thread. game thread only copies light/palettes and posts tasks,
// results go out as packed Rgb9e5 (null = section has no light now)
public final class HueWorker {

    // passes per loop before publishing + checking for tasks
    private static final int SECTION_BUDGET = 512;

    public interface Output {
        void section(long key, int @Nullable [] packed);
    }

    // packFar output, null when the light's gone
    public interface FarOutput {
        void section(long key, int @Nullable [] cells);
    }

    private final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private final HueField field = new HueField();
    private final Output output;
    private final @Nullable FarOutput far;
    private final Thread thread;
    private volatile boolean running = true;

    public HueWorker(Output output, @Nullable FarOutput far) {
        this.output = output;
        this.far = far;
        if (far != null) {
            this.field.trackFar();
        }
        this.thread = new Thread(this::run, "Polychrome hue worker");
        this.thread.setDaemon(true);
        this.thread.setPriority(Thread.NORM_PRIORITY - 1);
        this.thread.start();
    }


    public void chunkLoaded(Level level, LevelChunk chunk) {
        long start = System.nanoTime();
        int cx = chunk.getPos().x();
        int cz = chunk.getPos().z();
        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = chunk.getMinSectionY();
        byte[][] light = new byte[sections.length][];
        @SuppressWarnings("unchecked")
        PalettedContainer<BlockState>[] palettes = new PalettedContainer[sections.length];
        for (int i = 0; i < sections.length; i++) {
            light[i] = readLight(level, cx, minSectionY + i, cz);
            LevelChunkSection section = sections[i];
            if (section != null && section.maybeHas(state -> state.getLightEmission() > 0)) {
                palettes[i] = section.getStates().copy();
            }
        }
        this.post(() -> {
            for (int i = 0; i < light.length; i++) {
                this.field.loadSection(cx, minSectionY + i, cz, light[i], sources(palettes[i]));
            }
        });
        this.gameThreadNanos += System.nanoTime() - start;
    }

    public void chunkUnloaded(int cx, int cz, int minSectionY, int sectionCount) {
        this.post(() -> {
            for (int i = 0; i < sectionCount; i++) {
                this.field.unloadSection(cx, minSectionY + i, cz);
            }
        });
    }

    public void lightChanged(Level level, int sx, int sy, int sz) {
        long start = System.nanoTime();
        byte[] light = readLight(level, sx, sy, sz);
        this.post(() -> this.field.setLight(sx, sy, sz, light));
        this.gameThreadNanos += System.nanoTime() - start;
    }

    // player changed section, nearby work goes first
    public void focus(int sx, int sy, int sz) {
        this.post(() -> this.field.setFocus(sx, sy, sz));
    }

    public void blockChanged(int x, int y, int z, BlockState state) {
        int source = LightColors.sourceOf(state);
        this.post(() -> this.field.setSource(x, y, z, source));
    }

    // only call when they actually changed
    public void dynamicLights(DynamicLight[] lights) {
        this.post(() -> this.field.setDynamicLights(lights));
    }

    public void shutdown() {
        this.running = false;
        this.thread.interrupt();
    }

    private void post(Runnable task) {
        this.tasks.add(task);
    }

    // one byte per cell 0..15, null if dark. vanilla nibbles unpacked directly, anything else
    // (JLR stores more precision) goes through DataLayer.get like the rest of the game
    public static byte @Nullable [] readLight(Level level, int sx, int sy, int sz) {
        DataLayer layer = level.getLightEngine().getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(sx, sy, sz));
        if (layer == null || layer.isEmpty()) {
            return null;
        }
        byte[] out = new byte[4096];
        boolean any = false;
        if (layer.getClass() == DataLayer.class) {
            byte[] raw = layer.getData();
            for (int i = 0; i < 4096; i++) {
                int value = (raw[i >> 1] >> ((i & 1) << 2)) & 15;
                out[i] = (byte) value;
                any |= value != 0;
            }
        } else {
            for (int i = 0; i < 4096; i++) {
                int value = Math.clamp(layer.get(i & 15, i >> 8, (i >> 4) & 15), 0, 15);
                out[i] = (byte) value;
                any |= value != 0;
            }
        }
        return any ? out : null;
    }

    public static Int2IntMap sources(@Nullable PalettedContainer<BlockState> palette) {
        Int2IntMap sources = new Int2IntOpenHashMap();
        if (palette == null) {
            return sources;
        }
        for (int i = 0; i < 4096; i++) {
            int source = LightColors.sourceOf(palette.get(i & 15, i >> 8, (i >> 4) & 15));
            if (source != 0) {
                sources.put(i, source);
            }
        }
        return sources;
    }


    // diagnostics
    private volatile int busyTasks;
    private long busySince;
    private long recomputesAtStart;

    private volatile long passes;
    private volatile long workNanos;
    private volatile long taskNanos, processNanos, packNanos;
    private long gameThreadNanos;

    // tasks queued or sections still recomputing
    public boolean isBusy() {
        return !this.tasks.isEmpty() || this.busyTasks > 0;
    }

    public long passes() {
        return this.passes;
    }

    public long workNanos() {
        return this.workNanos;
    }

    // ms, diagnostics
    public String timings() {
        return String.format("tasks %d ms, section passes %d ms, packing %d ms; game thread copying %d ms; %s",
                this.taskNanos / 1_000_000, this.processNanos / 1_000_000, this.packNanos / 1_000_000, this.gameThreadNanos / 1_000_000,
                this.field.queueReasons());
    }

    private void run() {
        int[] packed = new int[512];
        int[] farCells = new int[8];
        while (this.running) {
            try {
                Runnable task = this.tasks.poll();
                if (task == null && !this.field.hasWork()) {
                    if (this.busyTasks > 0) {
                        long ms = (System.nanoTime() - this.busySince) / 1_000_000;
                        if (ms > 100) {
                            Polychrome.LOGGER.info("Hue worker caught up: {} tasks, {} section passes in {} ms, {} sections loaded",
                                    this.busyTasks, this.field.recomputes() - this.recomputesAtStart, ms, this.field.loadedSections());
                        }
                        this.busyTasks = 0;
                    }
                    task = this.tasks.take();
                }
                long workStart = System.nanoTime();
                if (this.busyTasks == 0) {
                    this.busySince = workStart;
                    this.recomputesAtStart = this.field.recomputes();
                }
                while (task != null) {
                    task.run();
                    this.busyTasks++;
                    task = this.tasks.poll();
                }
                long processStart = System.nanoTime();
                this.field.process(SECTION_BUDGET);
                this.passes = this.field.recomputes();
                long packStart = System.nanoTime();
                this.field.drainDirty(key -> {
                    this.output.section(key, this.field.pack(key, packed) ? packed.clone() : null);
                    // unloaded chunks keep their distant color, only light going out removes it.
                    // at the loaded edge color is incomplete so keep the last good one
                    if (this.far != null && this.field.isSurrounded(key)) {
                        this.far.section(key, this.field.packFar(key, farCells) ? farCells.clone() : null);
                    }
                });
                // dynamic light never touches distant color
                this.field.drainRepack(key -> this.output.section(key, this.field.pack(key, packed) ? packed.clone() : null));
                if (this.far != null) {
                    this.field.drainFarDirty(key -> {
                        if (this.field.isSurrounded(key) && this.field.packFar(key, farCells)) {
                            this.far.section(key, farCells.clone());
                        }
                    });
                }
                long end = System.nanoTime();
                this.taskNanos += processStart - workStart;
                this.processNanos += packStart - processStart;
                this.packNanos += end - packStart;
                this.workNanos += end - workStart;
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Polychrome.LOGGER.error("Hue worker task failed", t);
            }
        }
    }
}
