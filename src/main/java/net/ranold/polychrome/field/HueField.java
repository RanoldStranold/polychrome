package net.ranold.polychrome.field;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.function.LongConsumer;

// color of block light at every lit block. brightness comes from whatever engine filled the
// client's block light (vanilla, JLR, scalablelux), this only picks the hue.
// not thread safe, HueWorker owns it
public final class HueField {

    // lit but color unknown. 0 = no light
    public static final int WHITE = 0xFFFFFF;

    // same order as Direction: down up north south west east
    private static final int[] DX = {0, 0, 0, 0, -1, 1};
    private static final int[] DY = {-1, 1, 0, 0, 0, 0};
    private static final int[] DZ = {0, 0, -1, 1, 0, 0};

    // weight per light level when mixing, brighter wins more
    private static final float[] WEIGHT = new float[16];

    static {
        for (int level = 0; level < 16; level++) {
            WEIGHT[level] = level * level;
        }
    }

    private static final class Section {
        // engine block light 0..15, null when dark
        byte @Nullable [] levels;
        // levels as of last recompute. arrays get replaced never written, tells which border blocks a neighbor read before an edit
        byte @Nullable [] computedLevels;
        // cell index -> emission << 24 | rgb
        final Int2IntMap sources = new Int2IntOpenHashMap();
        // 0xRRGGBB (brightest channel 255), 0 = unlit. null until computed or while dark
        int @Nullable [] hue;
        boolean queued;
        // a source here or across a border got removed and the engine's new light hasnt come yet (block
        // change arrives first, light a frame later). untraceable light keeps old color till then, no white flash
        boolean awaitingLight;

        int level(int index) {
            int level = this.levels == null ? 0 : this.levels[index];
            return Math.max(level, this.sourceEmission(index));
        }

        int sourceEmission(int index) {
            return this.sources.isEmpty() ? 0 : this.sources.get(index) >>> 24;
        }
    }

    // buckets by distance from player in sections, last one = everything farther
    private static final int QUEUE_BUCKETS = 33;

    private final Long2ObjectMap<Section> sections = new Long2ObjectOpenHashMap<>();
    private final LongArrayFIFOQueue[] queue = new LongArrayFIFOQueue[QUEUE_BUCKETS];
    private int queued;
    // nothing below this bucket
    private int nearestBucket;
    private int focusX, focusY, focusZ;
    private final LongSet dirty = new LongOpenHashSet();
    // distant color may be complete now that a neighbor loaded
    private final LongSet farDirty = new LongOpenHashSet();
    private boolean trackFar;
    // LambDynamicLights lights, only mixed in when packing
    private DynamicLight[] dynamic = new DynamicLight[0];
    private final LongSet repack = new LongOpenHashSet();

    // scratch for one pass
    private final int[] bucketStart = new int[17];
    private final int[] order = new int[4096];
    private final int[] plateau = new int[4096];
    // section being computed: level per cell incl. its own sources. zeroed between passes
    private final byte[] passLevel = new byte[4096];
    private final int[] passSource = new int[4096];
    private long recomputes;
    // diagnostics: chunk load, new light, source changed, neighbor border changed
    private final long[] queuedBy = new long[4];

    public HueField() {
        for (int i = 0; i < QUEUE_BUCKETS; i++) {
            this.queue[i] = new LongArrayFIFOQueue();
        }
    }


    // same as SectionPos.asLong
    public static long sectionKey(int sx, int sy, int sz) {
        return ((long) (sx & 0x3FFFFF) << 42) | (sy & 0xFFFFFL) | ((long) (sz & 0x3FFFFF) << 20);
    }

    public static int sectionX(long key) {
        return (int) (key >> 42);
    }

    public static int sectionY(long key) {
        return (int) (key << 44 >> 44);
    }

    public static int sectionZ(long key) {
        return (int) (key << 22 >> 42);
    }

    // y << 8 | z << 4 | x, same as vanilla DataLayer
    public static int cellIndex(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    // half res 2x2x2 cell, layout pack() writes
    public static int coarseIndex(int x, int y, int z) {
        return (((y & 15) >> 1) << 6) | (((z & 15) >> 1) << 3) | ((x & 15) >> 1);
    }

    public static int source(int emission, int rgb) {
        return emission <= 0 ? 0 : (Math.min(emission, 15) << 24) | (rgb & 0xFFFFFF);
    }


    // work is sorted by distance from here. moving more than 1 section (teleport, join) re-sorts the queue
    public void setFocus(int sx, int sy, int sz) {
        int moved = Math.max(Math.abs(sx - this.focusX), Math.max(Math.abs(sy - this.focusY), Math.abs(sz - this.focusZ)));
        this.focusX = sx;
        this.focusY = sy;
        this.focusZ = sz;
        if (moved > 1 && this.queued > 0) {
            LongArrayFIFOQueue waiting = new LongArrayFIFOQueue(this.queued);
            for (LongArrayFIFOQueue bucket : this.queue) {
                while (!bucket.isEmpty()) {
                    waiting.enqueue(bucket.dequeueLong());
                }
            }
            this.queued = 0;
            this.nearestBucket = 0;
            while (!waiting.isEmpty()) {
                long key = waiting.dequeueLong();
                Section section = this.sections.get(key);
                if (section != null) {
                    section.queued = false;
                    this.queuedBy[3]--;  // counted again below
                    this.enqueue(key, section, 3);
                }
            }
        }
    }

    public boolean isLoaded(int sx, int sy, int sz) {
        return this.sections.containsKey(sectionKey(sx, sy, sz));
    }

    // block light null if dark
    public void loadSection(int sx, int sy, int sz, byte @Nullable [] levels, Int2IntMap sources) {
        long key = sectionKey(sx, sy, sz);
        Section section = this.sections.get(key);
        if (section == null) {
            section = new Section();
            this.sections.put(key, section);
        }
        section.levels = levels;
        section.sources.clear();
        section.sources.putAll(sources);
        section.awaitingLight = false;
        if (this.trackFar) {
            for (int d = 2; d < 6; d++) {
                long beside = sectionKey(sx + DX[d], sy, sz + DZ[d]);
                Section neighbor = this.sections.get(beside);
                if (neighbor != null && neighbor.hue != null) {
                    this.farDirty.add(beside);
                }
            }
        }
        if (levels == null && section.hue == null) {
            // dark, nothing to color. a source with no light yet gets queued by setLight once the light comes
            return;
        }
        // neighbors get queued by notifyNeighbors after the compute, not here
        this.enqueue(key, section, 0);
    }

    public void setLight(int sx, int sy, int sz, byte @Nullable [] levels) {
        long key = sectionKey(sx, sy, sz);
        Section section = this.sections.get(key);
        if (section == null || Arrays.equals(section.levels, levels)) {
            return;  // same light again (chunk load then server copy)
        }
        section.levels = levels;
        section.awaitingLight = false;
        this.enqueue(key, section, 1);
    }

    // source = source() packing
    public void setSource(int x, int y, int z, int source) {
        long key = sectionKey(x >> 4, y >> 4, z >> 4);
        Section section = this.sections.get(key);
        if (section == null) {
            return;
        }
        int index = cellIndex(x, y, z);
        boolean removed = false;
        if (source == 0) {
            removed = section.sources.remove(index) != 0;
        } else {
            section.sources.put(index, source);
        }
        section.awaitingLight |= removed;
        this.enqueue(key, section, 2);
        // border lights hand their color straight across even to brighter blocks, and once gone nothing
        // in this section may change, so tell the neighbors directly
        int lx = x & 15, ly = y & 15, lz = z & 15;
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        if (ly == 0) this.enqueueAcross(sx, sy - 1, sz, removed);
        if (ly == 15) this.enqueueAcross(sx, sy + 1, sz, removed);
        if (lz == 0) this.enqueueAcross(sx, sy, sz - 1, removed);
        if (lz == 15) this.enqueueAcross(sx, sy, sz + 1, removed);
        if (lx == 0) this.enqueueAcross(sx - 1, sy, sz, removed);
        if (lx == 15) this.enqueueAcross(sx + 1, sy, sz, removed);
    }

    private void enqueueAcross(int sx, int sy, int sz, boolean removed) {
        Section section = this.sections.get(sectionKey(sx, sy, sz));
        if (section != null && removed) {
            section.awaitingLight = true;
        }
        this.enqueueIfLit(sx, sy, sz);
    }

    // repacks every section a changed dynamic light reaches, before or now. nothing recomputed
    public void setDynamicLights(DynamicLight[] lights) {
        java.util.Set<DynamicLight> before = new java.util.HashSet<>(Arrays.asList(this.dynamic));
        java.util.Set<DynamicLight> now = new java.util.HashSet<>(Arrays.asList(lights));
        for (DynamicLight light : this.dynamic) {
            if (!now.contains(light)) {
                this.repackReach(light);
            }
        }
        for (DynamicLight light : lights) {
            if (!before.contains(light)) {
                this.repackReach(light);
            }
        }
        this.dynamic = lights;
    }

    private void repackReach(DynamicLight light) {
        int minX = (int) Math.ceil(light.x() - DynamicLight.REACH - 0.5) >> 4, maxX = (int) Math.floor(light.x() + DynamicLight.REACH - 0.5) >> 4;
        int minY = (int) Math.ceil(light.y() - DynamicLight.REACH - 0.5) >> 4, maxY = (int) Math.floor(light.y() + DynamicLight.REACH - 0.5) >> 4;
        int minZ = (int) Math.ceil(light.z() - DynamicLight.REACH - 0.5) >> 4, maxZ = (int) Math.floor(light.z() + DynamicLight.REACH - 0.5) >> 4;
        for (int sx = minX; sx <= maxX; sx++) {
            for (int sy = minY; sy <= maxY; sy++) {
                for (int sz = minZ; sz <= maxZ; sz++) {
                    long key = sectionKey(sx, sy, sz);
                    if (this.sections.containsKey(key)) {
                        this.repack.add(key);
                    }
                }
            }
        }
    }

    public void unloadSection(int sx, int sy, int sz) {
        long key = sectionKey(sx, sy, sz);
        Section removed = this.sections.remove(key);
        if (removed == null) {
            return;
        }
        if (removed.hue != null) {
            this.dirty.add(key);
        }
        for (int d = 0; d < 6; d++) {
            this.enqueueIfLit(sx + DX[d], sy + DY[d], sz + DZ[d]);
        }
    }

    private void enqueue(long key, Section section, int reason) {
        if (!section.queued) {
            this.queuedBy[reason]++;
            section.queued = true;
            int distance = Math.max(Math.abs(sectionX(key) - this.focusX),
                    Math.max(Math.abs(sectionY(key) - this.focusY), Math.abs(sectionZ(key) - this.focusZ)));
            int bucket = Math.min(distance, QUEUE_BUCKETS - 1);
            this.queue[bucket].enqueue(key);
            this.queued++;
            this.nearestBucket = Math.min(this.nearestBucket, bucket);
        }
    }

    private void enqueueIfLit(int sx, int sy, int sz) {
        long key = sectionKey(sx, sy, sz);
        Section section = this.sections.get(key);
        if (section != null && (section.levels != null || !section.sources.isEmpty())) {
            this.enqueue(key, section, 3);
        }
    }


    public boolean hasWork() {
        return this.queued > 0;
    }

    // nearest first, up to maxSections. true if work is left
    public boolean process(int maxSections) {
        int done = 0;
        while (this.queued > 0 && done < maxSections) {
            while (this.queue[this.nearestBucket].isEmpty()) {
                this.nearestBucket++;
            }
            long key = this.queue[this.nearestBucket].dequeueLong();
            this.queued--;
            Section section = this.sections.get(key);
            if (section == null) {
                continue;
            }
            section.queued = false;
            this.recompute(key, section);
            done++;
        }
        if (this.queued == 0) {
            this.nearestBucket = 0;
        }
        return this.queued > 0;
    }

    private void recompute(long key, Section section) {
        this.recomputes++;
        int sx = sectionX(key), sy = sectionY(key), sz = sectionZ(key);
        int[] old = section.hue;
        byte[] before = section.computedLevels;
        section.computedLevels = section.levels;

        byte[] lv = this.passLevel;
        int[] src = this.passSource;
        if (section.levels != null) {
            System.arraycopy(section.levels, 0, lv, 0, 4096);
        }
        for (Int2IntMap.Entry e : section.sources.int2IntEntrySet()) {
            int i = e.getIntKey();
            src[i] = e.getIntValue();
            lv[i] = (byte) Math.max(lv[i], src[i] >>> 24);
        }
        try {
            this.recompute(key, section, sx, sy, sz, old, before, lv, src);
        } finally {
            Arrays.fill(lv, (byte) 0);
            for (int i : section.sources.keySet()) {
                src[i] = 0;
            }
        }
    }

    private void recompute(long key, Section section, int sx, int sy, int sz, int @Nullable [] old, byte @Nullable [] before,
            byte[] lv, int[] src) {
        // counting sort by level, brightest first
        int[] count = new int[16];
        boolean anyLit = false;
        for (int i = 0; i < 4096; i++) {
            int level = lv[i];
            count[level]++;
            anyLit |= level > 0;
        }
        if (!anyLit) {
            section.hue = null;
            if (old != null) {
                this.dirty.add(key);
                this.notifyNeighbors(sx, sy, sz, section, old, null, before);
            }
            return;
        }
        int[] start = this.bucketStart;
        start[15] = 0;
        for (int level = 14; level >= 0; level--) {
            start[level] = start[level + 1] + count[level + 1];
        }
        start[16] = 0;
        int[] fill = Arrays.copyOf(start, 16);
        for (int i = 0; i < 4096; i++) {
            int level = lv[i];
            if (level > 0) {
                this.order[fill[level]++] = i;
            }
        }

        int[] hue = new int[4096];
        for (int level = 15; level >= 1; level--) {
            int from = start[level], to = from + count[level];
            int plateauSize = 0;
            // brighter neighbor or own emission
            for (int o = from; o < to; o++) {
                int index = this.order[o];
                int h = this.fromBrighter(lv, src, hue, sx, sy, sz, index, level);
                if (h != 0) {
                    hue[index] = h;
                } else {
                    this.plateau[plateauSize++] = index;
                }
            }
            // plateau of equal light, spread color sideways from cells that have one
            boolean progress = true;
            while (plateauSize > 0 && progress) {
                progress = false;
                int remaining = 0;
                for (int p = 0; p < plateauSize; p++) {
                    int index = this.plateau[p];
                    int h = this.fromEqual(lv, hue, sx, sy, sz, index, level);
                    if (h != 0) {
                        hue[index] = h;
                        progress = true;
                    } else {
                        this.plateau[remaining++] = index;
                    }
                }
                plateauSize = remaining;
            }
            // untraceable (other mods light, unloaded neighbor): white. right after a break its the engine's
            // old light though, keep old color for that frame so it doesnt flash white
            boolean keep = section.awaitingLight && old != null;
            for (int p = 0; p < plateauSize; p++) {
                int index = this.plateau[p];
                hue[index] = keep && old[index] != 0 ? old[index] : WHITE;
            }
        }

        section.hue = hue;
        boolean hueChanged = !Arrays.equals(old, hue);
        if (hueChanged) {
            this.dirty.add(key);
        }
        // light changed but color didnt: still changes what a neighbor mixes from
        if (hueChanged || before != section.levels) {
            this.notifyNeighbors(sx, sy, sz, section, old, hue, before);
        }
    }

    // from own emission + strictly brighter neighbors, 0 if neither
    private int fromBrighter(byte[] lv, int[] src, int[] hue, int sx, int sy, int sz, int index, int level) {
        float r = 0f, g = 0f, b = 0f;
        int source = src[index];
        if (source != 0) {
            float w = WEIGHT[source >>> 24];
            r += ((source >> 16) & 0xFF) * w;
            g += ((source >> 8) & 0xFF) * w;
            b += (source & 0xFF) * w;
        }
        int x = index & 15, y = index >> 8, z = (index >> 4) & 15;
        for (int d = 0; d < 6; d++) {
            int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
            int neighborLevel;
            int neighborHue;
            // emitting neighbors always pass their color even to brighter blocks: JLR adds light up so air
            // next to a bunch of lights is brighter than any one of them
            if ((nx | ny | nz) >= 0 && nx < 16 && ny < 16 && nz < 16) {
                int ni = (ny << 8) | (nz << 4) | nx;
                neighborLevel = lv[ni];
                if (neighborLevel > level) {
                    neighborHue = hue[ni] != 0 ? hue[ni] : src[ni] & 0xFFFFFF;
                } else {
                    neighborHue = src[ni] & 0xFFFFFF;
                    neighborLevel = src[ni] >>> 24;
                }
            } else {
                Section other = this.sections.get(sectionKey(sx + DX[d], sy + DY[d], sz + DZ[d]));
                if (other == null) {
                    continue;
                }
                int ni = cellIndex(nx, ny, nz);
                neighborLevel = other.level(ni);
                if (neighborLevel > level) {
                    neighborHue = other.hue != null && other.hue[ni] != 0 ? other.hue[ni] : sourceColor(other, ni);
                } else {
                    neighborHue = sourceColor(other, ni);
                    neighborLevel = other.sourceEmission(ni);
                }
            }
            if (neighborHue == 0) {
                continue;
            }
            float w = WEIGHT[neighborLevel];
            r += ((neighborHue >> 16) & 0xFF) * w;
            g += ((neighborHue >> 8) & 0xFF) * w;
            b += (neighborHue & 0xFF) * w;
        }
        return normalize(r, g, b);
    }

    // average of equally lit neighbors that have a color, or 0
    private int fromEqual(byte[] lv, int[] hue, int sx, int sy, int sz, int index, int level) {
        float r = 0f, g = 0f, b = 0f;
        int x = index & 15, y = index >> 8, z = (index >> 4) & 15;
        for (int d = 0; d < 6; d++) {
            int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
            int neighborHue;
            if ((nx | ny | nz) >= 0 && nx < 16 && ny < 16 && nz < 16) {
                int ni = (ny << 8) | (nz << 4) | nx;
                if (lv[ni] != level) {
                    continue;
                }
                neighborHue = hue[ni];
            } else {
                Section other = this.sections.get(sectionKey(sx + DX[d], sy + DY[d], sz + DZ[d]));
                if (other == null || other.hue == null) {
                    continue;
                }
                int ni = cellIndex(nx, ny, nz);
                if (other.level(ni) != level) {
                    continue;
                }
                neighborHue = other.hue[ni];
            }
            if (neighborHue == 0) {
                continue;
            }
            r += (neighborHue >> 16) & 0xFF;
            g += (neighborHue >> 8) & 0xFF;
            b += neighborHue & 0xFF;
        }
        return normalize(r, g, b);
    }

    private static int sourceColor(Section section, int index) {
        int source = section.sources.isEmpty() ? 0 : section.sources.get(index);
        return source == 0 ? 0 : source & 0xFFFFFF;
    }

    // brightest channel -> 255, 0 for black
    private static int normalize(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b));
        if (max <= 0f) {
            return 0;
        }
        float s = 255f / max;
        int ri = Math.round(r * s), gi = Math.round(g * s), bi = Math.round(b * s);
        int packed = (ri << 16) | (gi << 8) | bi;
        return packed == 0 ? WHITE : packed;
    }

    // requeue neighbors across faces where a border block they read (before or after the edit) changed.
    // a neighbor only reads border blocks at least as bright as its own side, or sources. skipping the
    // rest stops work bouncing back and forth across borders on load. border sources: see setSource
    private void notifyNeighbors(int sx, int sy, int sz, Section section, int @Nullable [] old, int @Nullable [] now,
            byte @Nullable [] before) {
        for (int d = 0; d < 6; d++) {
            Section neighbor = this.sections.get(sectionKey(sx + DX[d], sy + DY[d], sz + DZ[d]));
            if (neighbor == null || (neighbor.levels == null && neighbor.sources.isEmpty())) {
                continue;
            }
            if (this.faceChanged(d, section, neighbor, old, now, before)) {
                this.enqueue(sectionKey(sx + DX[d], sy + DY[d], sz + DZ[d]), neighbor, 3);
            }
        }
    }

    private boolean faceChanged(int direction, Section section, Section neighbor, int @Nullable [] old, int @Nullable [] now,
            byte @Nullable [] before) {
        for (int a = 0; a < 16; a++) {
            for (int b = 0; b < 16; b++) {
                int index, across;
                switch (direction) {
                    case 0 -> { index = (a << 4) | b; across = (15 << 8) | (a << 4) | b; }  // down y=0
                    case 1 -> { index = (15 << 8) | (a << 4) | b; across = (a << 4) | b; }  // up y=15
                    case 2 -> { index = (a << 8) | b; across = (a << 8) | (15 << 4) | b; }  // north z=0
                    case 3 -> { index = (a << 8) | (15 << 4) | b; across = (a << 8) | b; }  // south z=15
                    case 4 -> { index = (a << 8) | (b << 4); across = (a << 8) | (b << 4) | 15; }  // west x=0
                    default -> { index = (a << 8) | (b << 4) | 15; across = (a << 8) | (b << 4); }  // east x=15
                }
                int o = old == null ? 0 : old[index];
                int n = now == null ? 0 : now[index];
                int levelNow = section.level(index);
                int levelBefore = Math.max(before == null ? 0 : before[index], section.sourceEmission(index));
                if (o == n && levelNow == levelBefore) {
                    continue;
                }
                if (now == null && o != n) {
                    return true;  // went dark, anything that read it has to look again
                }
                int theirs = neighbor.level(across);
                if (theirs > 0 && (levelNow >= theirs || levelBefore >= theirs || section.sourceEmission(index) > 0)) {
                    return true;
                }
            }
        }
        return false;
    }


    // 0xRRGGBB, 0 if unlit or unknown
    public int hueAt(int x, int y, int z) {
        Section section = this.sections.get(sectionKey(x >> 4, y >> 4, z >> 4));
        return section == null || section.hue == null ? 0 : section.hue[cellIndex(x, y, z)];
    }

    public void drainDirty(LongConsumer consumer) {
        var it = this.dirty.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            if (this.trackFar) {
                this.farDirty.remove(key);
            }
            consumer.accept(key);
        }
        this.dirty.clear();
    }

    // dynamic light only, skips ones already drained as dirty
    public void drainRepack(LongConsumer consumer) {
        var it = this.repack.iterator();
        while (it.hasNext()) {
            consumer.accept(it.nextLong());
        }
        this.repack.clear();
    }

    // color same but distant color might be complete now
    public void drainFarDirty(LongConsumer consumer) {
        var it = this.farDirty.iterator();
        while (it.hasNext()) {
            consumer.accept(it.nextLong());
        }
        this.farDirty.clear();
    }

    // half res: 512 Rgb9e5, average of hue * brightness per 2x2x2. brightness only weights the average,
    // shader gets brightness from the lightmap. sources own blocks left out (lava shouldnt tint itself).
    // dynamic lights mixed per block like LDL: level = max, color weighted by level^2.
    // false if unloaded or no light
    public boolean pack(long key, int[] out) {
        Section section = this.sections.get(key);
        if (section == null) {
            return false;
        }
        int sx = sectionX(key), sy = sectionY(key), sz = sectionZ(key);
        DynamicLight[] lights = this.dynamicReaching(sx, sy, sz);
        if (section.hue == null && lights.length == 0) {
            return false;
        }
        int[] hue = section.hue;
        boolean any = false;
        for (int cy = 0; cy < 8; cy++) {
            for (int cz = 0; cz < 8; cz++) {
                for (int cx = 0; cx < 8; cx++) {
                    float r = 0f, g = 0f, b = 0f;
                    for (int o = 0; o < 8; o++) {
                        int lx = cx * 2 + (o & 1), ly = cy * 2 + ((o >> 1) & 1), lz = cz * 2 + ((o >> 2) & 1);
                        int i = cellIndex(lx, ly, lz);
                        if (section.sourceEmission(i) > 0) {
                            continue;
                        }
                        int h = hue == null ? 0 : hue[i];
                        float level = h == 0 ? 0f : section.level(i);
                        if (lights.length > 0) {
                            this.mixDynamic(lights, sx * 16 + lx, sy * 16 + ly, sz * 16 + lz, h, level);
                            h = this.mixedHue;
                            level = this.mixedLevel;
                        }
                        if (h == 0) {
                            continue;
                        }
                        float w = level / (15f * 255f * 8f);
                        r += ((h >> 16) & 0xFF) * w;
                        g += ((h >> 8) & 0xFF) * w;
                        b += (h & 0xFF) * w;
                    }
                    int packed = Rgb9e5.pack(r, g, b, 1.0e-4f);
                    out[(cy << 6) | (cz << 3) | cx] = packed;
                    any |= packed != 0;
                }
            }
        }
        return any;
    }

    private DynamicLight[] dynamicReaching(int sx, int sy, int sz) {
        if (this.dynamic.length == 0) {
            return this.dynamic;
        }
        return Arrays.stream(this.dynamic).filter(light -> light.reaches(sx, sy, sz)).toArray(DynamicLight[]::new);
    }

    // mixDynamic output
    private int mixedHue;
    private float mixedLevel;

    // own hue/level with dynamic lights at the block mixed in
    private void mixDynamic(DynamicLight[] lights, int x, int y, int z, int hue, float level) {
        float dynamic = 0f, r = 0f, g = 0f, b = 0f;
        for (DynamicLight light : lights) {
            float l = (float) light.levelAt(x, y, z);
            if (l <= 0f) {
                continue;
            }
            dynamic = Math.max(dynamic, l);
            float w = l * l;
            r += ((light.rgb() >> 16) & 0xFF) * w;
            g += ((light.rgb() >> 8) & 0xFF) * w;
            b += (light.rgb() & 0xFF) * w;
        }
        if (dynamic <= 0f) {
            this.mixedHue = hue;
            this.mixedLevel = level;
            return;
        }
        int lit = normalize(r, g, b);
        if (hue != 0) {
            float own = level * level, theirs = dynamic * dynamic;
            float sum = own + theirs;
            lit = normalize(((hue >> 16) & 0xFF) * own / sum + ((lit >> 16) & 0xFF) * theirs / sum,
                    ((hue >> 8) & 0xFF) * own / sum + ((lit >> 8) & 0xFF) * theirs / sum,
                    (hue & 0xFF) * own / sum + (lit & 0xFF) * theirs / sum);
        }
        this.mixedHue = lit;
        this.mixedLevel = Math.max(level, dynamic);
    }

    // 8x8x8 cell, layout packFar() writes
    public static int farIndex(int x, int y, int z) {
        return (((y & 15) >> 3) << 2) | (((z & 15) >> 3) << 1) | ((x & 15) >> 3);
    }

    // 8 Rgb9e5, one per 8x8x8, averaged like pack(). what's kept for LOD terrain after the chunk unloads.
    // false if unloaded or no light
    public boolean packFar(long key, int[] out) {
        Section section = this.sections.get(key);
        if (section == null || section.hue == null) {
            return false;
        }
        int[] hue = section.hue;
        float[] sum = new float[24];
        for (int i = 0; i < 4096; i++) {
            int h = hue[i];
            if (h == 0 || section.sourceEmission(i) > 0) {
                continue;
            }
            int cell = farIndex(i & 15, i >> 8, (i >> 4) & 15) * 3;
            float w = section.level(i) / (15f * 255f * 512f);
            sum[cell] += ((h >> 16) & 0xFF) * w;
            sum[cell + 1] += ((h >> 8) & 0xFF) * w;
            sum[cell + 2] += (h & 0xFF) * w;
        }
        boolean any = false;
        for (int c = 0; c < 8; c++) {
            out[c] = Rgb9e5.pack(sum[c * 3], sum[c * 3 + 1], sum[c * 3 + 2], 1.0e-6f);
            any |= out[c] != 0;
        }
        return any;
    }

    // only needed when distant color is kept (LOD mod installed)
    public void trackFar() {
        this.trackFar = true;
    }

    // loaded, can still be dark
    public boolean isLoaded(long key) {
        return this.sections.containsKey(key);
    }

    // section + the 4 beside it loaded. at the loaded edge light from the unloaded side is white, not worth keeping for LODs
    public boolean isSurrounded(long key) {
        int sx = sectionX(key), sy = sectionY(key), sz = sectionZ(key);
        return this.sections.containsKey(key)
                && this.sections.containsKey(sectionKey(sx - 1, sy, sz)) && this.sections.containsKey(sectionKey(sx + 1, sy, sz))
                && this.sections.containsKey(sectionKey(sx, sy, sz - 1)) && this.sections.containsKey(sectionKey(sx, sy, sz + 1));
    }

    public int loadedSections() {
        return this.sections.size();
    }

    public String queueReasons() {
        return "queued by chunk load " + this.queuedBy[0] + ", new light " + this.queuedBy[1]
                + ", source change " + this.queuedBy[2] + ", neighbor border " + this.queuedBy[3];
    }

    public long recomputes() {
        return this.recomputes;
    }
}
