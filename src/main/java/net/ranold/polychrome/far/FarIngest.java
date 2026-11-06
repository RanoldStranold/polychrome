package net.ranold.polychrome.far;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.field.HueField;
import net.ranold.polychrome.field.HueWorker;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.LongAdder;

// distant color for chunks the player never loads (chunky pregen ingested by voxy, or sections other
// mods hand voxy directly, like voxygen). own hue field on its own thread, publishes sections once
// their 4 side neighbors are known. keeps the last MAX_CHUNKS chunks only, pregen goes outward so
// neighbors come soon after
public final class FarIngest {

    private static final int MAX_CHUNKS = 1024;

    private final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private final HueField field = new HueField();
    private final FarColors far;
    private final Thread thread;
    private volatile boolean running = true;

    // oldest first: x, z, min section y, section count
    private final LongArrayFIFOQueue chunks = new LongArrayFIFOQueue();
    private final LongOpenHashSet columns = new LongOpenHashSet();
    private volatile long chunksSeen, sectionsPublished, sectionsHanded;
    private volatile boolean busy;
    // time spent on the caller's thread (Voxygen workers)
    private final LongAdder callerNanos = new LongAdder(), callerCalls = new LongAdder();

    public FarIngest(FarColors far) {
        this.far = far;
        this.field.trackFar();
        this.thread = new Thread(this::run, "Polychrome distant color ingest");
        this.thread.setDaemon(true);
        this.thread.setPriority(Thread.MIN_PRIORITY);
        this.thread.start();
    }

    // any thread, copies block light + sources
    public void chunkGenerated(LevelChunk chunk) {
        Level level = chunk.getLevel();
        int cx = chunk.getPos().x(), cz = chunk.getPos().z();
        int minSectionY = chunk.getMinSectionY();
        LevelChunkSection[] sections = chunk.getSections();
        byte[][] light = new byte[sections.length][];
        @SuppressWarnings("unchecked")
        PalettedContainer<BlockState>[] palettes = new PalettedContainer[sections.length];
        for (int i = 0; i < sections.length; i++) {
            light[i] = HueWorker.readLight(level, cx, minSectionY + i, cz);
            LevelChunkSection section = sections[i];
            if (light[i] != null && section != null && section.maybeHas(state -> state.getLightEmission() > 0)) {
                palettes[i] = section.getStates().copy();
            }
        }
        this.tasks.add(() -> {
            for (int i = 0; i < light.length; i++) {
                this.field.loadSection(cx, minSectionY + i, cz, light[i], HueWorker.sources(palettes[i]));
            }
            this.track(cx, cz, minSectionY, light.length);
        });
    }

    // any thread. one section at a time, air sections never come, so the first section of a column
    // marks the whole column known (dark) and the real ones overwrite it
    public void sectionGenerated(int cx, int cy, int cz, LevelChunkSection section, @Nullable DataLayer blockLight,
                                 int minSectionY, int sectionCount) {
        long start = System.nanoTime();
        byte[] light = HueWorker.unpackLight(blockLight);
        PalettedContainer<BlockState> palette = light != null && section.maybeHas(state -> state.getLightEmission() > 0)
                ? section.getStates().copy() : null;
        this.tasks.add(() -> {
            this.sectionsHanded++;
            if (!this.columns.contains(column(cx, cz))) {
                for (int i = 0; i < sectionCount; i++) {
                    this.field.loadSection(cx, minSectionY + i, cz, null, HueWorker.sources(null));
                }
                this.track(cx, cz, minSectionY, sectionCount);
            }
            this.field.loadSection(cx, cy, cz, light, HueWorker.sources(palette));
        });
        this.callerNanos.add(System.nanoTime() - start);
        this.callerCalls.increment();
    }

    private void track(int cx, int cz, int minSectionY, int count) {
        if (this.columns.add(column(cx, cz))) {
            this.chunks.enqueue(column(cx, cz));
            this.chunks.enqueue(((long) minSectionY << 32) | count);
            this.chunksSeen++;
        }
    }

    private static long column(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public void shutdown() {
        this.running = false;
        this.thread.interrupt();
    }

    private void run() {
        int[] cells = new int[FarTable.CELLS];
        while (this.running) {
            try {
                Runnable task = this.tasks.take();
                this.busy = true;
                while (task != null) {
                    task.run();
                    task = this.tasks.poll();
                }
                while (this.field.process(4096)) {
                }
                this.field.drainDirty(key -> this.publish(key, cells));
                this.field.drainFarDirty(key -> this.publish(key, cells));
                while (this.chunks.size() / 2 > MAX_CHUNKS) {
                    long pos = this.chunks.dequeueLong(), range = this.chunks.dequeueLong();
                    int cx = (int) (pos >> 32), cz = (int) pos, minY = (int) (range >> 32), count = (int) range;
                    this.columns.remove(pos);
                    for (int i = 0; i < count; i++) {
                        this.field.unloadSection(cx, minY + i, cz);
                    }
                }
                // dropping old chunks leaves their neighbors incomplete, those dont get published
                while (this.field.process(4096)) {
                }
                this.field.drainDirty(key -> { });
                this.field.drainFarDirty(key -> { });
                this.busy = !this.tasks.isEmpty();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Polychrome.LOGGER.error("Distant color ingest failed", t);
            }
        }
    }

    private void publish(long key, int[] cells) {
        if (this.field.isSurrounded(key) && this.field.packFar(key, cells)) {
            this.far.publish(key, cells.clone());
            this.sectionsPublished++;
        }
    }

    // diagnostics
    public boolean isBusy() {
        return this.busy || !this.tasks.isEmpty();
    }

    public String stats() {
        return "generated chunks seen " + this.chunksSeen + ", sections handed one by one " + this.sectionsHanded + ", sections published " + this.sectionsPublished
                + ", kept " + this.field.loadedSections() + " sections, caller thread " + this.callerNanos.sum() / 1_000_000 + " ms for "
                + this.callerCalls.sum() + " sections";
    }
}
