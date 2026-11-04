package net.ranold.polychrome.far;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.field.HueField;
import net.ranold.polychrome.field.HueWorker;

import java.util.concurrent.LinkedBlockingQueue;

// distant color for chunks the player never loads (chunky pregen ingested by voxy). own hue field on
// its own thread, publishes sections once their 4 side neighbors are known. keeps the last MAX_CHUNKS
// chunks only, pregen goes outward so neighbors come soon after
public final class FarIngest {

    private static final int MAX_CHUNKS = 1024;

    private final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private final HueField field = new HueField();
    private final FarColors far;
    private final Thread thread;
    private volatile boolean running = true;

    // oldest first: x, z, min section y, section count
    private final LongArrayFIFOQueue chunks = new LongArrayFIFOQueue();
    private volatile long chunksSeen, sectionsPublished;
    private volatile boolean busy;

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
            this.chunks.enqueue(((long) cx << 32) | (cz & 0xFFFFFFFFL));
            this.chunks.enqueue(((long) minSectionY << 32) | light.length);
            this.chunksSeen++;
        });
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
        return "generated chunks seen " + this.chunksSeen + ", sections published " + this.sectionsPublished
                + ", kept " + this.field.loadedSections() + " sections";
    }
}
