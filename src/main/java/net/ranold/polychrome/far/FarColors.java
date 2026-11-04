package net.ranold.polychrome.far;

import net.minecraft.world.phys.Vec3;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.gpu.LightVolume;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// distant color for LOD mods (voxy): owns the FarTable, keeps it in one GL SSBO, saves it with the world.
// plain GL because voxy is. render thread only, except publish() (hue worker)
public final class FarColors {

    // voxy uses 0-6
    public static final int BINDING = 9;
    // per frame budget for applying arrived colors, rest waits
    private static final long APPLY_NANOS_PER_FRAME = 1_000_000L;
    private static final int RECENTER_PER_FRAME = 16_384;
    private static final long SAVE_INTERVAL_NANOS = 300_000_000_000L;
    // dirty entries closer than this get uploaded as one range
    private static final int RUN_GAP = 16;

    private record Update(int generation, long key, int @Nullable [] cells, boolean fromDisk) {
    }

    private final ConcurrentLinkedQueue<Update> incoming = new ConcurrentLinkedQueue<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Polychrome distant color IO");
        thread.setDaemon(true);
        return thread;
    });
    private final Object saveLock = new Object();

    private FarTable table = new FarTable();
    private volatile int generation;
    private @Nullable Path file;
    private boolean changedSinceSave;
    private long lastSave = System.nanoTime();

    // diagnostics
    private long frameNanos, maxFrameNanos, frames;
    private String worstFrame = "none";
    private int uploadCalls, uploadInts;

    private int buffer;
    // entries the last dimension used, emptied on the GPU for the next one
    private final BitSet stale = new BitSet();
    private final int[] header = new int[FarTable.HEADER_INTS];
    private boolean headerWritten;

    // from the hue worker, null = light went out
    public void publish(long key, int @Nullable [] cells) {
        this.incoming.add(new Update(this.generation, key, cells, false));
    }

    // starts empty, loads the save in the background
    public void open(@Nullable Path file) {
        this.close();
        this.file = file;
        if (file == null) {
            return;
        }
        int generation = this.generation;
        this.io.execute(() -> {
            try {
                long start = System.nanoTime();
                int count = FarFile.load(file, (key, cells) -> this.incoming.add(new Update(generation, key, cells, true)));
                if (count > 0) {
                    Polychrome.LOGGER.info("Loaded distant light color for {} sections in {} ms from {}", count,
                            (System.nanoTime() - start) / 1_000_000, file);
                }
            } catch (Exception e) {
                Polychrome.LOGGER.warn("Could not load distant light color from {}", file, e);
            }
        });
    }

    // saves on this thread so it isnt lost on exit, then forgets everything
    public void close() {
        this.applyIncoming(Long.MAX_VALUE);
        if (this.file != null && this.changedSinceSave) {
            this.save(this.file, this.table.snapshotKeys());
        }
        this.generation++;
        this.incoming.clear();
        this.stale.or(this.table.occupiedIndex());
        this.table = new FarTable();
        this.file = null;
        this.changedSinceSave = false;
        this.headerWritten = false;
    }

    private void save(Path file, long[] keys) {
        int[] cells = this.table.snapshotCells(keys);
        synchronized (this.saveLock) {
            try {
                FarFile.save(file, keys, cells);
            } catch (Exception e) {
                Polychrome.LOGGER.warn("Could not save distant light color to {}", file, e);
            }
        }
        this.changedSinceSave = false;
        this.lastSave = System.nanoTime();
    }

    public void onFrame(Vec3 camera, boolean enabled) {
        long start = System.nanoTime();
        int applied = this.applyIncoming(start + APPLY_NANOS_PER_FRAME);
        long applyEnd = System.nanoTime();
        this.table.setCenter((int) Math.floor(camera.x) >> 4, (int) Math.floor(camera.z) >> 4);
        this.table.recenterStep(RECENTER_PER_FRAME);
        long recenterEnd = System.nanoTime();
        this.uploadCalls = 0;
        this.uploadInts = 0;
        this.upload(enabled);
        long uploadEnd = System.nanoTime();

        if (this.file != null && this.changedSinceSave && System.nanoTime() - this.lastSave > SAVE_INTERVAL_NANOS) {
            Path file = this.file;
            long[] keys = this.table.snapshotKeys();
            int[] cells = this.table.snapshotCells(keys);
            this.changedSinceSave = false;
            this.lastSave = System.nanoTime();
            this.io.execute(() -> {
                synchronized (this.saveLock) {
                    try {
                        FarFile.save(file, keys, cells);
                    } catch (Exception e) {
                        Polychrome.LOGGER.warn("Could not save distant light color to {}", file, e);
                    }
                }
            });
        }
        long end = System.nanoTime();
        long spent = end - start;
        this.frameNanos += spent;
        if (spent > this.maxFrameNanos) {
            this.maxFrameNanos = spent;
            this.worstFrame = String.format("frame %d: applying %d updates %.2f ms, camera window %.2f ms, %d uploads of %d KiB %.2f ms, saving %.2f ms",
                    this.frames, applied, (applyEnd - start) / 1e6, (recenterEnd - applyEnd) / 1e6, this.uploadCalls, this.uploadInts / 256,
                    (uploadEnd - recenterEnd) / 1e6, (end - uploadEnd) / 1e6);
        }
        this.frames++;
    }

    // until deadline (checked every 64), returns how many
    private int applyIncoming(long deadline) {
        Update update;
        int generation = this.generation;
        int applied = 0;
        while (((applied & 63) != 0 || System.nanoTime() < deadline) && (update = this.incoming.poll()) != null) {
            applied++;
            if (update.generation() != generation) {
                continue;
            }
            if (update.cells() == null) {
                this.table.remove(update.key());
                this.changedSinceSave = true;
            } else if (update.fromDisk()) {
                this.table.putIfAbsent(update.key(), update.cells());
            } else {
                this.table.put(update.key(), update.cells());
                this.changedSinceSave = true;
            }
        }
        return applied;
    }


    // render thread, inside voxy's frame
    public void bind() {
        if (this.buffer == 0) {
            this.upload(false);
        }
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, BINDING, this.buffer);
    }

    private void upload(boolean enabled) {
        FarTable table = this.table;
        if (this.buffer == 0) {
            this.buffer = GL45C.glCreateBuffers();
            GL45C.glNamedBufferStorage(this.buffer, (long) table.totalInts() * 4, GL45C.GL_DYNAMIC_STORAGE_BIT);
            // new table is all -1, cleared on the GPU instead of uploading 28 MiB. only once,
            // clearing it on every world change cost several ms
            try (MemoryStack stack = MemoryStack.stackPush()) {
                GL45C.glClearNamedBufferData(this.buffer, GL30C.GL_R32UI, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT, stack.ints(-1));
            }
            Polychrome.LOGGER.info("Distant light color table created: {} sections, {} MiB", table.dataSlots(), (long) table.totalInts() * 4 >> 20);
            this.stale.clear();
            this.headerWritten = false;
        }
        if (table.takeCleared()) {
            // new dimension: only empty the entries the last one used
            this.uploadRuns(this.stale, FarTable.HEADER_INTS, 3, (from, to, out) -> {
                for (int i = (to - from) * 3; i > 0; i--) {
                    out.put(-1);
                }
            });
            this.headerWritten = false;
        }
        this.uploadRuns(table.dirtyIndex(), FarTable.HEADER_INTS, 3, table::writeIndex);
        this.uploadRuns(table.dirtyData(), table.dataStart(), FarTable.CELLS, table::writeData);

        int[] wanted = {table.indexEntries() - 1, Float.floatToRawIntBits(LightVolume.SATURATION), enabled ? 1 : 0, table.dataStart()};
        if (!this.headerWritten || !java.util.Arrays.equals(wanted, this.header)) {
            System.arraycopy(wanted, 0, this.header, 0, wanted.length);
            IntBuffer data = MemoryUtil.memAllocInt(FarTable.HEADER_INTS);
            try {
                data.put(wanted).flip();
                GL45C.glNamedBufferSubData(this.buffer, 0, data);
            } finally {
                MemoryUtil.memFree(data);
            }
            this.headerWritten = true;
        }
    }

    private interface RangeWriter {
        void write(int from, int to, IntBuffer out);
    }

    // set bits of dirty as ranges of stride-int entries from int base
    private void uploadRuns(BitSet dirty, int base, int stride, RangeWriter writer) {
        int from = dirty.nextSetBit(0);
        while (from >= 0) {
            int to = from + 1;
            int next = dirty.nextSetBit(to);
            while (next >= 0 && next - to < RUN_GAP) {
                to = next + 1;
                next = dirty.nextSetBit(to);
            }
            IntBuffer data = MemoryUtil.memAllocInt((to - from) * stride);
            try {
                writer.write(from, to, data);
                data.flip();
                GL45C.glNamedBufferSubData(this.buffer, (long) (base + from * stride) * 4, data);
                this.uploadCalls++;
                this.uploadInts += (to - from) * stride;
            } finally {
                MemoryUtil.memFree(data);
            }
            from = next;
        }
        dirty.clear();
    }


    public FarTable table() {
        return this.table;
    }

    public boolean pending() {
        return !this.incoming.isEmpty();
    }

    // reads the GPU buffer back and compares with the table, returns ints that differ (-1 = no buffer). slow, diagnostics
    public int verifyGpu() {
        if (this.buffer == 0) {
            return -1;
        }
        int total = this.table.totalInts();
        IntBuffer gpu = MemoryUtil.memAllocInt(total);
        IntBuffer cpu = MemoryUtil.memAllocInt(total);
        try {
            GL45C.glGetNamedBufferSubData(this.buffer, 0, gpu);
            cpu.put(this.header);
            this.table.writeIndex(0, this.table.indexEntries(), cpu);
            this.table.writeData(0, this.table.dataSlots(), cpu);
            int differ = 0;
            int dataStart = this.table.dataStart();
            for (int i = 0; i < total; i++) {
                // free slot can still hold an old dimension's color, nothing reads it
                if (i >= dataStart && !this.table.isSlotUsed((i - dataStart) / FarTable.CELLS)) {
                    continue;
                }
                if (gpu.get(i) != cpu.get(i)) {
                    if (differ < 5) {
                        Polychrome.LOGGER.info("GPU distant color differs at int {} (data starts at {}): gpu {} cpu {}", i,
                                this.table.dataStart(), Integer.toHexString(gpu.get(i)), Integer.toHexString(cpu.get(i)));
                    }
                    differ++;
                }
            }
            return differ;
        } finally {
            MemoryUtil.memFree(gpu);
            MemoryUtil.memFree(cpu);
        }
    }

    // avg and worst ms per frame, diagnostics
    public String frameCost() {
        return String.format("%.3f ms average, %.2f ms worst over %d frames (worst: %s)", this.frames == 0 ? 0.0 : this.frameNanos / 1e6 / this.frames,
                this.maxFrameNanos / 1e6, this.frames, this.worstFrame);
    }

    public String stats() {
        return "distant: known " + this.table.size() + ", on GPU " + this.table.residentCount() + " of " + this.table.dataSlots()
                + " (" + ((long) this.table.totalInts() * 4 >> 10) + " KiB)";
    }
}
