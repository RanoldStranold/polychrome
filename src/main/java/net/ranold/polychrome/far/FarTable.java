package net.ranold.polychrome.far;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.field.HueField;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.BitSet;

// coarse color for LODs: 8 Rgb9e5 per section (8x8x8 blocks each, see HueField.packFar).
// store = every section ever colored, kept after unload and saved.
// gpu table = sections within RADIUS of the camera, laid out exactly like far_colors.glsl reads it:
//   [0] index mask, [1] saturation bits, [2] enabled, [3] data start,
//   then the index (linear probing, 3 ints: key lo, key hi, slot, -1 = empty, 2x the data slots),
//   then 8 ints per data slot.
// fixed size (MAX_DATA_SLOTS, 16 MiB data + 12 MiB index) because doubling it caused multi ms frames.
// past that sections stay white. render thread owns it, not thread safe
public final class FarTable {

    public static final int CELLS = 8;
    public static final int HEADER_INTS = 4;
    // sections, = 4096 blocks
    public static final int RADIUS = 256;
    // camera move in sections before the GPU set gets rechecked
    private static final int RECENTER_STEP = 16;
    public static final int MAX_DATA_SLOTS = 1 << 19;
    private static final int EMPTY = -1;

    // store
    private final Long2IntOpenHashMap storeSlot = new Long2IntOpenHashMap();
    private int[] storeData = new int[1024 * CELLS];
    private final IntArrayList storeFree = new IntArrayList();
    private int storeTop;

    // gpu table
    private int dataSlots;
    private int mask;
    private int[] index;
    // gpu slot -> store slot, -1 = free
    private int[] gpuToStore;
    private final IntArrayList gpuFree = new IntArrayList();
    private int gpuTop;
    private int residentCount;
    private boolean warnedFull;

    // uploads
    private final BitSet dirtyIndex = new BitSet();
    private final BitSet dirtyData = new BitSet();
    private boolean cleared;

    // camera
    private int centerX = Integer.MIN_VALUE / 2, centerZ = Integer.MIN_VALUE / 2;
    private int checkedX = Integer.MIN_VALUE / 2, checkedZ = Integer.MIN_VALUE / 2;
    private long[] recenterKeys = new long[0];
    private int recenterPos;

    public FarTable() {
        this.storeSlot.defaultReturnValue(-1);
        this.allocateGpu(MAX_DATA_SLOTS);
    }


    public void put(long key, int[] cells) {
        int slot = this.storeSlot.get(key);
        if (slot < 0) {
            slot = this.allocateStore();
            this.storeSlot.put(key, slot);
        }
        System.arraycopy(cells, 0, this.storeData, slot * CELLS, CELLS);
        if (this.inWindow(key)) {
            this.makeResident(key, slot, true);
        }
    }

    // like put() but leaves known sections alone (loading from disk)
    public void putIfAbsent(long key, int[] cells) {
        if (!this.storeSlot.containsKey(key)) {
            this.put(key, cells);
        }
    }

    public void remove(long key) {
        int slot = this.storeSlot.remove(key);
        if (slot < 0) {
            return;
        }
        this.removeResident(key);
        this.storeFree.add(slot);
    }

    public int size() {
        return this.storeSlot.size();
    }

    public int residentCount() {
        return this.residentCount;
    }

    // for saving
    public long[] snapshotKeys() {
        return this.storeSlot.keySet().toLongArray();
    }

    public int[] snapshotCells(long[] keys) {
        int[] out = new int[keys.length * CELLS];
        for (int i = 0; i < keys.length; i++) {
            int slot = this.storeSlot.get(keys[i]);
            if (slot >= 0) {
                System.arraycopy(this.storeData, slot * CELLS, out, i * CELLS, CELLS);
            }
        }
        return out;
    }

    private int allocateStore() {
        if (!this.storeFree.isEmpty()) {
            return this.storeFree.popInt();
        }
        if ((this.storeTop + 1) * CELLS > this.storeData.length) {
            this.storeData = Arrays.copyOf(this.storeData, this.storeData.length * 2);
        }
        return this.storeTop++;
    }


    // after RECENTER_STEP sections the whole store gets rechecked, a slice per recenterStep()
    public void setCenter(int sx, int sz) {
        this.centerX = sx;
        this.centerZ = sz;
        if (Math.max(Math.abs(sx - this.checkedX), Math.abs(sz - this.checkedZ)) >= RECENTER_STEP) {
            this.checkedX = sx;
            this.checkedZ = sz;
            this.recenterKeys = this.storeSlot.keySet().toLongArray();
            this.recenterPos = 0;
        }
    }

    // up to budget sections, true when done
    public boolean recenterStep(int budget) {
        int end = Math.min(this.recenterKeys.length, this.recenterPos + budget);
        for (; this.recenterPos < end; this.recenterPos++) {
            long key = this.recenterKeys[this.recenterPos];
            int slot = this.storeSlot.get(key);
            if (slot < 0) {
                continue;
            }
            if (this.inWindow(key)) {
                this.makeResident(key, slot, false);
            } else {
                this.removeResident(key);
            }
        }
        if (this.recenterPos >= this.recenterKeys.length) {
            this.recenterKeys = new long[0];
            this.recenterPos = 0;
            return true;
        }
        return false;
    }

    private boolean inWindow(long key) {
        return Math.abs(HueField.sectionX(key) - this.centerX) <= RADIUS && Math.abs(HueField.sectionZ(key) - this.centerZ) <= RADIUS;
    }


    private void makeResident(long key, int storeSlot, boolean changed) {
        int at = this.findIndex(key);
        if (at >= 0) {
            int gpu = this.index[at * 3 + 2];
            this.gpuToStore[gpu] = storeSlot;
            if (changed) {
                this.dirtyData.set(gpu);
            }
            return;
        }
        if (this.residentCount >= this.dataSlots) {
            if (!this.warnedFull) {
                this.warnedFull = true;
                Polychrome.LOGGER.warn("Distant light color table full ({} sections); more distant light stays white", MAX_DATA_SLOTS);
            }
            return;
        }
        int gpu = this.gpuFree.isEmpty() ? this.gpuTop++ : this.gpuFree.popInt();
        this.gpuToStore[gpu] = storeSlot;
        this.residentCount++;
        this.insertIndex(key, gpu);
        this.dirtyData.set(gpu);
    }

    private void removeResident(long key) {
        int at = this.findIndex(key);
        if (at < 0) {
            return;
        }
        int gpu = this.index[at * 3 + 2];
        this.gpuToStore[gpu] = -1;
        this.gpuFree.add(gpu);
        this.residentCount--;
        this.removeIndex(key);
    }

    // -1 if not on the GPU
    private int findIndex(long key) {
        int lo = (int) key, hi = (int) (key >>> 32);
        int i = this.home(lo, hi);
        while (this.index[i * 3 + 2] != EMPTY) {
            if (this.index[i * 3] == lo && this.index[i * 3 + 1] == hi) {
                return i;
            }
            i = (i + 1) & this.mask;
        }
        return -1;
    }

    private void allocateGpu(int slots) {
        this.dataSlots = slots;
        this.mask = slots * 2 - 1;
        // all -1 like the GPU copy gets cleared to (slot -1 = empty entry)
        this.index = new int[slots * 2 * 3];
        Arrays.fill(this.index, EMPTY);
        this.gpuToStore = new int[slots];
        Arrays.fill(this.gpuToStore, -1);
        this.cleared = true;
    }

    // must match cl_farHash in the shader
    public static int hash(int lo, int hi) {
        int h = lo * 0x9E3779B1 ^ hi * 0x85EBCA77;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return h;
    }

    private int home(int lo, int hi) {
        return hash(lo, hi) & this.mask;
    }

    private void insertIndex(long key, int gpu) {
        int lo = (int) key, hi = (int) (key >>> 32);
        int i = this.home(lo, hi);
        while (this.index[i * 3 + 2] != EMPTY) {
            i = (i + 1) & this.mask;
        }
        this.index[i * 3] = lo;
        this.index[i * 3 + 1] = hi;
        this.index[i * 3 + 2] = gpu;
        this.dirtyIndex.set(i);
    }

    // shifts later entries of the probe run back so lookups dont stop early
    private void removeIndex(long key) {
        int lo = (int) key, hi = (int) (key >>> 32);
        int i = this.home(lo, hi);
        while (this.index[i * 3 + 2] != EMPTY && (this.index[i * 3] != lo || this.index[i * 3 + 1] != hi)) {
            i = (i + 1) & this.mask;
        }
        if (this.index[i * 3 + 2] == EMPTY) {
            return;
        }
        int j = i;
        while (true) {
            j = (j + 1) & this.mask;
            if (this.index[j * 3 + 2] == EMPTY) {
                break;
            }
            int k = this.home(this.index[j * 3], this.index[j * 3 + 1]);
            boolean stays = i <= j ? (i < k && k <= j) : (i < k || k <= j);
            if (stays) {
                continue;
            }
            System.arraycopy(this.index, j * 3, this.index, i * 3, 3);
            this.dirtyIndex.set(i);
            i = j;
        }
        this.index[i * 3 + 2] = EMPTY;
        this.dirtyIndex.set(i);
    }

    // what the shader does, for tests / diagnostics

    // 0 if none, same lookup as the shader
    public int lookup(int x, int y, int z) {
        long key = HueField.sectionKey(x >> 4, y >> 4, z >> 4);
        int lo = (int) key, hi = (int) (key >>> 32);
        int i = this.home(lo, hi);
        for (int probe = 0; probe <= this.mask; probe++) {
            int gpu = this.index[i * 3 + 2];
            if (gpu == EMPTY) {
                return 0;
            }
            if (this.index[i * 3] == lo && this.index[i * 3 + 1] == hi) {
                return this.storeData[this.gpuToStore[gpu] * CELLS + HueField.farIndex(x, y, z)];
            }
            i = (i + 1) & this.mask;
        }
        return 0;
    }

    // 0xRRGGBB or -1 for white, same trilinear blend as cl_farHue
    public int smoothHue(int x, int y, int z) {
        double fx = (x + 0.5) / 8 - 0.5, fy = (y + 0.5) / 8 - 0.5, fz = (z + 0.5) / 8 - 0.5;
        int bx = (int) Math.floor(fx), by = (int) Math.floor(fy), bz = (int) Math.floor(fz);
        double tx = fx - bx, ty = fy - by, tz = fz - bz;
        double r = 0, g = 0, b = 0;
        for (int k = 0; k < 8; k++) {
            int ox = k & 1, oy = (k >> 1) & 1, oz = (k >> 2) & 1;
            int cell = this.lookup((bx + ox) * 8, (by + oy) * 8, (bz + oz) * 8);
            if (cell == 0) {
                continue;
            }
            double w = (ox == 1 ? tx : 1 - tx) * (oy == 1 ? ty : 1 - ty) * (oz == 1 ? tz : 1 - tz);
            float[] c = net.ranold.polychrome.field.Rgb9e5.unpack(cell);
            r += c[0] * w;
            g += c[1] * w;
            b += c[2] * w;
        }
        double peak = Math.max(r, Math.max(g, b));
        if (peak <= 0) {
            return -1;
        }
        return (int) Math.round(r / peak * 255) << 16 | (int) Math.round(g / peak * 255) << 8 | (int) Math.round(b / peak * 255);
    }

    // 0 if unknown
    public int stored(int x, int y, int z) {
        int slot = this.storeSlot.get(HueField.sectionKey(x >> 4, y >> 4, z >> 4));
        return slot < 0 ? 0 : this.storeData[slot * CELLS + HueField.farIndex(x, y, z)];
    }


    public int indexEntries() {
        return this.mask + 1;
    }

    public int dataSlots() {
        return this.dataSlots;
    }

    // after header + index
    public int dataStart() {
        return HEADER_INTS + this.indexEntries() * 3;
    }

    public int totalInts() {
        return this.dataStart() + this.dataSlots * CELLS;
    }

    // true once per new table: clear the GPU copy to -1 before uploading dirty parts
    public boolean takeCleared() {
        boolean cleared = this.cleared;
        this.cleared = false;
        return cleared;
    }

    // diagnostics
    public boolean isSlotUsed(int gpu) {
        return this.gpuToStore[gpu] >= 0;
    }

    // so the GPU copy can be emptied entry by entry instead of cleared whole
    public BitSet occupiedIndex() {
        BitSet used = new BitSet();
        for (int i = 0; i <= this.mask; i++) {
            if (this.index[i * 3 + 2] != EMPTY) {
                used.set(i);
            }
        }
        return used;
    }

    public BitSet dirtyIndex() {
        return this.dirtyIndex;
    }

    public BitSet dirtyData() {
        return this.dirtyData;
    }

    // [from, to), 3 ints each
    public void writeIndex(int from, int to, IntBuffer out) {
        out.put(this.index, from * 3, (to - from) * 3);
    }

    // [from, to), CELLS ints each, free slots -1
    public void writeData(int from, int to, IntBuffer out) {
        for (int gpu = from; gpu < to; gpu++) {
            int store = this.gpuToStore[gpu];
            if (store < 0) {
                for (int c = 0; c < CELLS; c++) {
                    out.put(EMPTY);
                }
            } else {
                out.put(this.storeData, store * CELLS, CELLS);
            }
        }
    }
}
