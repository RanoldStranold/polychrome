package net.ranold.polychrome.field;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// small stand-in for the light engine: vanilla flood fill, -1 per block, opaque stops it.
// the field only colors whatever light this says is there
class HueFieldTest {

    private static final int RED = 0xFF0000;
    private static final int BLUE = 0x0000FF;

    // box of sections with vanilla light, feeds a HueField like the worker does
    private static final class World {
        final int minS, maxS;
        final Map<Long, Integer> sources = new HashMap<>();  // block key -> HueField.source
        final LongSet opaque = new LongOpenHashSet();
        final HueField field = new HueField();

        World(int minS, int maxS) {
            this.minS = minS;
            this.maxS = maxS;
        }

        static long key(int x, int y, int z) {
            return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
        }

        void source(int x, int y, int z, int emission, int rgb) {
            this.sources.put(key(x, y, z), HueField.source(emission, rgb));
        }

        void removeSource(int x, int y, int z) {
            this.sources.remove(key(x, y, z));
        }

        void wall(int x, int y, int z) {
            this.opaque.add(key(x, y, z));
        }

        boolean inside(int x, int y, int z) {
            int lo = this.minS * 16, hi = (this.maxS + 1) * 16;
            return x >= lo && x < hi && y >= lo && y < hi && z >= lo && z < hi;
        }

        // max over sources of emission - distance, through non opaque blocks
        Long2IntOpenHashMap light() {
            Long2IntOpenHashMap level = new Long2IntOpenHashMap();
            LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
            Map<Long, int[]> pos = new HashMap<>();
            for (var e : this.sources.entrySet()) {
                long k = e.getKey();
                int emission = e.getValue() >>> 24;
                if (level.get(k) < emission) {
                    level.put(k, emission);
                    queue.enqueue(k);
                }
            }
            int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            while (!queue.isEmpty()) {
                long k = queue.dequeueLong();
                int x = (int) (k << 1 >> 43), y = (int) (k << 22 >> 43), z = (int) (k << 43 >> 43);
                int l = level.get(k);
                for (int[] d : dirs) {
                    int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                    long nk = key(nx, ny, nz);
                    if (!this.inside(nx, ny, nz) || this.opaque.contains(nk)) {
                        continue;
                    }
                    if (level.get(nk) < l - 1) {
                        level.put(nk, l - 1);
                        queue.enqueue(nk);
                    }
                }
            }
            return level;
        }

        // hand everything to the field and let it settle
        void sync() {
            Long2IntOpenHashMap level = this.light();
            for (int sx = this.minS; sx <= this.maxS; sx++) {
                for (int sy = this.minS; sy <= this.maxS; sy++) {
                    for (int sz = this.minS; sz <= this.maxS; sz++) {
                        byte[] levels = new byte[4096];
                        boolean any = false;
                        Int2IntOpenHashMap sectionSources = new Int2IntOpenHashMap();
                        for (int i = 0; i < 4096; i++) {
                            int x = sx * 16 + (i & 15), y = sy * 16 + (i >> 8), z = sz * 16 + ((i >> 4) & 15);
                            long k = key(x, y, z);
                            levels[i] = (byte) level.get(k);
                            any |= levels[i] != 0;
                            Integer s = this.sources.get(k);
                            if (s != null) {
                                sectionSources.put(i, s.intValue());
                            }
                        }
                        if (this.field.isLoaded(sx, sy, sz)) {
                            this.field.setLight(sx, sy, sz, any ? levels : null);
                            for (int i = 0; i < 4096; i++) {
                                int x = sx * 16 + (i & 15), y = sy * 16 + (i >> 8), z = sz * 16 + ((i >> 4) & 15);
                                this.field.setSource(x, y, z, sectionSources.get(i));
                            }
                        } else {
                            this.field.loadSection(sx, sy, sz, any ? levels : null, sectionSources);
                        }
                    }
                }
            }
            while (this.field.process(Integer.MAX_VALUE)) {
            }
        }

        private final Map<Long, byte[]> sentLight = new HashMap<>();
        private final Map<Long, Integer> sentSources = new HashMap<>();

        // like the game: setLight only where light changed, setSource only where emission changed. then settle
        void syncChanges() {
            Long2IntOpenHashMap level = this.light();
            for (int sx = this.minS; sx <= this.maxS; sx++) {
                for (int sy = this.minS; sy <= this.maxS; sy++) {
                    for (int sz = this.minS; sz <= this.maxS; sz++) {
                        byte[] levels = new byte[4096];
                        boolean any = false;
                        for (int i = 0; i < 4096; i++) {
                            levels[i] = (byte) level.get(key(sx * 16 + (i & 15), sy * 16 + (i >> 8), sz * 16 + ((i >> 4) & 15)));
                            any |= levels[i] != 0;
                        }
                        long section = HueField.sectionKey(sx, sy, sz);
                        if (!this.field.isLoaded(sx, sy, sz)) {
                            this.field.loadSection(sx, sy, sz, any ? levels : null, new Int2IntOpenHashMap());
                        } else if (!Arrays.equals(this.sentLight.get(section), any ? levels : null)) {
                            this.field.setLight(sx, sy, sz, any ? levels : null);
                        }
                        this.sentLight.put(section, any ? levels : null);
                    }
                }
            }
            for (long k : new LongOpenHashSet(concat(this.sources.keySet(), this.sentSources.keySet()))) {
                int now = this.sources.getOrDefault(k, 0);
                if (now != this.sentSources.getOrDefault(k, 0)) {
                    this.field.setSource((int) (k << 1 >> 43), (int) (k << 22 >> 43), (int) (k << 43 >> 43), now);
                }
            }
            this.sentSources.clear();
            this.sentSources.putAll(this.sources);
            while (this.field.process(Integer.MAX_VALUE)) {
            }
        }

        private static long[] concat(java.util.Set<Long> a, java.util.Set<Long> b) {
            return java.util.stream.Stream.concat(a.stream(), b.stream()).mapToLong(Long::longValue).toArray();
        }

        // fixed order
        int[] hues() {
            int lo = this.minS * 16, size = (this.maxS - this.minS + 1) * 16;
            int[] out = new int[size * size * size];
            int n = 0;
            for (int x = lo; x < lo + size; x++) {
                for (int y = lo; y < lo + size; y++) {
                    for (int z = lo; z < lo + size; z++) {
                        out[n++] = this.field.hueAt(x, y, z);
                    }
                }
            }
            return out;
        }

        // recompute every section again, returns how many hues changed
        int staleAfterRecomputingAll() {
            int[] before = this.hues();
            for (int sx = this.minS; sx <= this.maxS; sx++) {
                for (int sy = this.minS; sy <= this.maxS; sy++) {
                    for (int sz = this.minS; sz <= this.maxS; sz++) {
                        // same light + sources, just requeues
                        Int2IntOpenHashMap sectionSources = new Int2IntOpenHashMap();
                        for (var e : this.sources.entrySet()) {
                            long k = e.getKey();
                            int x = (int) (k << 1 >> 43), y = (int) (k << 22 >> 43), z = (int) (k << 43 >> 43);
                            if (x >> 4 == sx && y >> 4 == sy && z >> 4 == sz) {
                                sectionSources.put(HueField.cellIndex(x, y, z), e.getValue().intValue());
                            }
                        }
                        this.field.loadSection(sx, sy, sz, this.sentLight.get(HueField.sectionKey(sx, sy, sz)), sectionSources);
                    }
                }
            }
            while (this.field.process(Integer.MAX_VALUE)) {
            }
            int[] after = this.hues();
            int differ = 0;
            for (int i = 0; i < before.length; i++) {
                if (before[i] != after[i]) {
                    differ++;
                }
            }
            return differ;
        }

        int lightAt(int x, int y, int z) {
            return this.light().get(key(x, y, z));
        }
    }

    private static int red(int hue) {
        return (hue >> 16) & 0xFF;
    }

    private static int blue(int hue) {
        return hue & 0xFF;
    }

    @Test
    void lightKeepsItsColorAllTheWay() {
        World world = new World(-1, 1);
        world.source(0, 8, 0, 14, RED);
        world.sync();
        Long2IntOpenHashMap light = world.light();
        for (int x = -16; x < 32; x++) {
            int level = light.get(World.key(x, 8, 0));
            int hue = world.field.hueAt(x, 8, 0);
            if (level > 0) {
                assertEquals(RED, hue, "lit block at x=" + x + " (level " + level + ") must be red");
            } else {
                assertEquals(0, hue, "unlit block at x=" + x + " must have no color");
            }
        }
    }

    @Test
    void twoColorsMixWhereTheyMeet() {
        World world = new World(-1, 1);
        world.source(-5, 8, 0, 14, RED);
        world.source(5, 8, 0, 14, BLUE);
        world.sync();
        int middle = world.field.hueAt(0, 8, 0);
        assertEquals(red(middle), blue(middle), "equal distance mixes equally: " + Integer.toHexString(middle));
        assertTrue(red(middle) > 0);
        int nearRed = world.field.hueAt(-4, 8, 0);
        assertTrue(red(nearRed) == 255 && blue(nearRed) < 255, "red wins next to the red light: " + Integer.toHexString(nearRed));
    }

    @Test
    void colorStopsWhereTheLightStops() {
        World world = new World(-1, 1);
        for (int y = -16; y < 32; y++) {
            for (int z = -16; z < 32; z++) {
                world.wall(3, y, z);
            }
        }
        world.source(0, 8, 0, 15, RED);
        world.sync();
        assertEquals(RED, world.field.hueAt(2, 8, 0));
        assertEquals(0, world.field.hueAt(4, 8, 0), "nothing behind the wall");
    }

    @Test
    void colorCrossesSectionBorders() {
        World world = new World(-1, 1);
        world.source(15, 8, 8, 15, BLUE);
        world.sync();
        assertEquals(BLUE, world.field.hueAt(16, 8, 8));
        assertEquals(BLUE, world.field.hueAt(26, 8, 8));
    }

    @Test
    void removingALightRemovesItsColor() {
        World world = new World(-1, 1);
        world.source(0, 8, 0, 14, RED);
        world.sync();
        world.removeSource(0, 8, 0);
        world.sync();
        for (int x = -16; x < 32; x++) {
            assertEquals(0, world.field.hueAt(x, 8, 0));
        }
    }

    @Test
    void breakingALightDoesNotFlashWhiteBeforeItsLightGoes() {
        // game order: block change now, engine light a frame later. old light still there with nothing to trace
        World world = new World(-1, 1);
        world.source(15, 8, 8, 14, RED);  // on a section border so the next section reads it directly
        world.syncChanges();
        int[] lit = world.hues();
        world.removeSource(15, 8, 8);
        world.sentSources.remove(World.key(15, 8, 8));
        world.field.setSource(15, 8, 8, 0);
        while (world.field.process(Integer.MAX_VALUE)) {
        }
        int[] between = world.hues();
        for (int i = 0; i < lit.length; i++) {
            assertEquals(lit[i], between[i], "the old light keeps its color until the new light arrives");
        }
        world.syncChanges();
        for (int x = -16; x < 32; x++) {
            assertEquals(0, world.field.hueAt(x, 8, 8), "and goes with it, x=" + x);
        }
        assertEquals(0, world.staleAfterRecomputingAll());
    }

    @Test
    void recoloringALightRecolorsItsWholeArea() {
        World world = new World(-1, 1);
        world.source(14, 8, 8, 14, RED);
        world.sync();
        world.source(14, 8, 8, 14, BLUE);
        world.sync();
        assertEquals(BLUE, world.field.hueAt(26, 8, 8), "color changed in the next section too");
    }

    @Test
    void lightThatAddsUpPastItsSourceKeepsTheSourceColor() {
        // JLR adds light up: lichen on two faces emits 7 but the air next to it is 12. color still has to flow out
        HueField field = new HueField();
        byte[] levels = new byte[4096];
        int lichen = HueField.cellIndex(8, 8, 8);
        for (int x = 0; x < 16; x++) {
            int distance = Math.abs(x - 8);
            if (distance > 0) {
                levels[HueField.cellIndex(x, 8, 8)] = (byte) Math.max(0, 13 - distance);
            }
        }
        Int2IntOpenHashMap sources = new Int2IntOpenHashMap();
        sources.put(lichen, HueField.source(7, 0x9CFFC8));
        field.loadSection(0, 0, 0, levels, sources);
        field.process(Integer.MAX_VALUE);
        assertEquals(0x9CFFC8, field.hueAt(9, 8, 8), "air beside the lichen (level 12)");
        assertEquals(0x9CFFC8, field.hueAt(12, 8, 8), "further out (level 9)");
    }

    @Test
    void lightFromAnotherModWithNoKnownSourceIsWhite() {
        HueField field = new HueField();
        byte[] levels = new byte[4096];
        levels[HueField.cellIndex(5, 5, 5)] = 10;
        field.loadSection(0, 0, 0, levels, new Int2IntOpenHashMap());
        field.process(Integer.MAX_VALUE);
        assertEquals(HueField.WHITE, field.hueAt(5, 5, 5));
    }

    @Test
    void aLavaSeaIsCheap() {
        // 9x9 sections of lava, 2 deep, fully lit. the nether case that took 10s before
        World world = new World(-4, 4);
        for (int x = -64; x < 80; x++) {
            for (int z = -64; z < 80; z++) {
                for (int y = -64; y < -32; y++) {
                    world.source(x, y, z, 15, 0xFF6A1F);
                }
            }
        }
        long start = System.nanoTime();
        world.sync();
        long ms = (System.nanoTime() - start) / 1_000_000;
        long passes = world.field.recomputes();
        System.out.printf("[probe] lava sea: %d section passes, %d ms including test light flood%n", passes, ms);
        assertNotEquals(0, world.field.hueAt(0, -20, 0));
    }

    @Test
    void aSourceDoesNotTintItselfButFullLightAroundItIsTinted() {
        // JLR adds two torches up to 15 in the air between them, that air still needs color. only the torch blocks are left out
        HueField field = new HueField();
        byte[] levels = new byte[4096];
        Arrays.fill(levels, (byte) 15);
        Int2IntOpenHashMap sources = new Int2IntOpenHashMap();
        sources.put(HueField.cellIndex(0, 0, 0), HueField.source(14, RED));
        sources.put(HueField.cellIndex(1, 0, 0), HueField.source(14, RED));
        sources.put(HueField.cellIndex(0, 1, 0), HueField.source(14, RED));
        sources.put(HueField.cellIndex(1, 1, 0), HueField.source(14, RED));
        sources.put(HueField.cellIndex(0, 0, 1), HueField.source(14, RED));
        sources.put(HueField.cellIndex(1, 0, 1), HueField.source(14, RED));
        sources.put(HueField.cellIndex(0, 1, 1), HueField.source(14, RED));
        sources.put(HueField.cellIndex(1, 1, 1), HueField.source(14, RED));
        field.loadSection(0, 0, 0, levels, sources);
        field.process(Integer.MAX_VALUE);
        int[] packed = new int[512];
        field.pack(HueField.sectionKey(0, 0, 0), packed);
        assertEquals(0, packed[HueField.coarseIndex(0, 0, 0)], "a 2x2x2 cell of only light sources carries no color");
        float[] beside = Rgb9e5.unpack(packed[HueField.coarseIndex(2, 0, 0)]);
        assertTrue(beside[0] > 0f && beside[1] == 0f && beside[2] == 0f, "level-15 air beside them is red");
    }

    // 14 at the cell, -1 per step, inside one section
    private static byte[] litAround(int cx, int cy, int cz) {
        byte[] levels = new byte[4096];
        for (int i = 0; i < 4096; i++) {
            int d = Math.abs((i & 15) - cx) + Math.abs((i >> 8) - cy) + Math.abs(((i >> 4) & 15) - cz);
            levels[i] = (byte) Math.max(0, 14 - d);
        }
        return levels;
    }

    @Test
    void darkSectionsCostNothing() {
        HueField field = new HueField();
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                field.loadSection(x, 0, z, null, new Int2IntOpenHashMap());
            }
        }
        assertTrue(!field.process(Integer.MAX_VALUE));
        assertEquals(0, field.recomputes(), "no light and no sources: no work");
    }

    @Test
    void theSameLightDeliveredAgainIsSkipped() {
        HueField field = new HueField();
        byte[] levels = new byte[4096];
        levels[HueField.cellIndex(5, 5, 5)] = 10;
        field.loadSection(0, 0, 0, levels, new Int2IntOpenHashMap());
        field.process(Integer.MAX_VALUE);
        long before = field.recomputes();
        field.setLight(0, 0, 0, levels.clone());
        field.process(Integer.MAX_VALUE);
        assertEquals(before, field.recomputes());
    }

    @Test
    void sectionsNearestThePlayerGoFirst() {
        HueField field = new HueField();
        Int2IntOpenHashMap far = new Int2IntOpenHashMap();
        far.put(HueField.cellIndex(8, 8, 8), HueField.source(14, RED));
        Int2IntOpenHashMap near = new Int2IntOpenHashMap();
        near.put(HueField.cellIndex(8, 8, 8), HueField.source(14, BLUE));
        field.setFocus(10, 0, 0);
        field.loadSection(0, 0, 0, litAround(8, 8, 8), far);  // queued first, 10 sections away
        field.loadSection(10, 0, 0, litAround(8, 8, 8), near);  // queued second, where the player is
        field.process(1);
        assertEquals(BLUE, field.hueAt(10 * 16 + 8, 8, 8), "the player's section was done first");
        assertEquals(0, field.hueAt(8, 8, 8), "the far one waits");
    }

    @Test
    void movingFarSortsTheWaitingWorkAgain() {
        HueField field = new HueField();
        Int2IntOpenHashMap a = new Int2IntOpenHashMap();
        a.put(HueField.cellIndex(8, 8, 8), HueField.source(14, RED));
        Int2IntOpenHashMap b = new Int2IntOpenHashMap();
        b.put(HueField.cellIndex(8, 8, 8), HueField.source(14, BLUE));
        field.loadSection(0, 0, 0, litAround(8, 8, 8), a);
        field.loadSection(20, 0, 0, litAround(8, 8, 8), b);
        field.setFocus(20, 0, 0);  // joined away from origin after the chunks came
        field.process(1);
        assertEquals(BLUE, field.hueAt(20 * 16 + 8, 8, 8));
    }

    // place/break one at a time like a player, has to end up the same as recomputing everything (no stale color)
    @Test
    void placingAndBreakingLeavesNoStaleColor() {
        int[] colors = {RED, BLUE, 0x00FF00, 0xFFAA33, 0x33CCFF};
        for (int seed = 0; seed < 60; seed++) {
            java.util.Random random = new java.util.Random(seed);
            World world = new World(-1, 1);
            for (int w = 0; w < 300; w++) {
                world.wall(random.nextInt(48) - 16, random.nextInt(48) - 16, random.nextInt(48) - 16);
            }
            java.util.List<int[]> placed = new java.util.ArrayList<>();
            world.syncChanges();
            for (int edit = 0; edit < 25; edit++) {
                if (!placed.isEmpty() && random.nextInt(3) == 0) {
                    int[] p = placed.remove(random.nextInt(placed.size()));
                    world.removeSource(p[0], p[1], p[2]);
                } else {
                    // near borders where color has to cross
                    int[] p = {random.nextInt(12) - 6 + 16 * (random.nextInt(2)), random.nextInt(12) - 6 + 16 * random.nextInt(2),
                            random.nextInt(12) - 6};
                    placed.add(p);
                    world.source(p[0], p[1], p[2], 8 + random.nextInt(8), colors[random.nextInt(colors.length)]);
                }
                world.syncChanges();
                int stale = world.staleAfterRecomputingAll();
                assertEquals(0, stale, "seed " + seed + ", edit " + edit + ": blocks whose color was out of date");
            }
        }
    }

    @Test
    void onlySectionsWithAllSideNeighborsLoadedCountAsSurrounded() {
        HueField field = new HueField();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                field.loadSection(x, 0, z, null, new Int2IntOpenHashMap());
            }
        }
        assertTrue(field.isSurrounded(HueField.sectionKey(0, 0, 0)));
        assertTrue(!field.isSurrounded(HueField.sectionKey(1, 0, 0)), "the edge of the loaded area");
        field.unloadSection(0, 0, 1);
        assertTrue(!field.isSurrounded(HueField.sectionKey(0, 0, 0)));
    }

    // section colored before its neighbors loaded gets distant color once they do
    @Test
    void aNeighborLoadingLaterOffersTheDistantColorAgain() {
        HueField field = new HueField();
        field.trackFar();
        Int2IntOpenHashMap sources = new Int2IntOpenHashMap();
        sources.put(HueField.cellIndex(8, 8, 8), HueField.source(14, RED));
        field.loadSection(0, 0, 0, litAround(8, 8, 8), sources);
        field.process(Integer.MAX_VALUE);
        field.drainDirty(key -> { });
        long[] offered = new long[1];
        field.loadSection(1, 0, 0, null, new Int2IntOpenHashMap());
        field.drainFarDirty(key -> offered[0] = key);
        assertEquals(HueField.sectionKey(0, 0, 0), offered[0]);
    }

    @Test
    void withoutDistantColorNothingIsTrackedForIt() {
        HueField field = new HueField();
        Int2IntOpenHashMap sources = new Int2IntOpenHashMap();
        sources.put(HueField.cellIndex(8, 8, 8), HueField.source(14, RED));
        field.loadSection(0, 0, 0, litAround(8, 8, 8), sources);
        field.process(Integer.MAX_VALUE);
        field.loadSection(1, 0, 0, null, new Int2IntOpenHashMap());
        long[] offered = new long[1];
        field.drainFarDirty(key -> offered[0]++);
        assertEquals(0, offered[0]);
    }

    @Test
    void packRoundTrips() {
        int packed = Rgb9e5.pack(0.5f, 0.25f, 0.125f, 0f);
        float[] back = Rgb9e5.unpack(packed);
        assertEquals(0.5f, back[0], 1e-3f);
        assertEquals(0.25f, back[1], 1e-3f);
        assertEquals(0.125f, back[2], 1e-3f);
        assertEquals(0, Rgb9e5.pack(1e-9f, 0f, 0f, 1e-6f));
    }

    // dynamic lights

    // {r, g, b} scaled so brightest = 1, null if empty
    private static float[] cellColor(HueField field, int x, int y, int z) {
        int[] packed = new int[512];
        if (!field.pack(HueField.sectionKey(x >> 4, y >> 4, z >> 4), packed)) {
            return null;
        }
        int cell = packed[HueField.coarseIndex(x, y, z)];
        if (cell == 0) {
            return null;
        }
        float[] c = Rgb9e5.unpack(cell);
        float max = Math.max(c[0], Math.max(c[1], c[2]));
        return new float[] {c[0] / max, c[1] / max, c[2] / max};
    }

    @Test
    void dynamicLightFallsOffLikeLambDynamicLights() {
        DynamicLight light = new DynamicLight(0.5, 0.5, 0.5, 14, RED);
        assertEquals(14.0, light.levelAt(0, 0, 0), 1e-9, "full luminance in its own block");
        assertEquals(14.0 - 3.0 / 7.75 * 15.0, light.levelAt(3, 0, 0), 1e-9);
        assertEquals(0.0, light.levelAt(8, 0, 0), "out of reach");
        assertTrue(light.reaches(0, 0, 0) && !light.reaches(1, 0, 0) && light.reaches(-1, 0, 0),
                "7.75 blocks from x=0.5 reaches x=-8 (section -1) but not x=16");
    }

    @Test
    void aDarkPlaceLitOnlyByADynamicLightTakesItsColor() {
        HueField field = new HueField();
        field.loadSection(0, 0, 0, null, new Int2IntOpenHashMap());
        field.process(Integer.MAX_VALUE);
        assertEquals(null, cellColor(field, 4, 8, 8), "dark before");
        field.setDynamicLights(new DynamicLight[] {new DynamicLight(4.5, 8.5, 8.5, 14, BLUE)});
        LongSet repacked = new LongOpenHashSet();
        field.drainRepack(repacked::add);
        assertTrue(repacked.contains(HueField.sectionKey(0, 0, 0)));
        float[] near = cellColor(field, 4, 8, 8);
        assertTrue(near != null && near[2] == 1f && near[0] == 0f, "blue beside the light");
        assertEquals(null, cellColor(field, 14, 8, 8), "nothing beyond its reach");
    }

    @Test
    void aDynamicLightMixesWithPlacedLightAndLeavesNoTrace() {
        HueField field = new HueField();
        Int2IntOpenHashMap sources = new Int2IntOpenHashMap();
        sources.put(HueField.cellIndex(8, 8, 8), HueField.source(14, BLUE));
        field.loadSection(0, 0, 0, litAround(8, 8, 8), sources);
        field.process(Integer.MAX_VALUE);
        int[] before = new int[512];
        field.pack(HueField.sectionKey(0, 0, 0), before);

        field.setDynamicLights(new DynamicLight[] {new DynamicLight(2.5, 8.5, 8.5, 14, RED)});
        float[] atLight = cellColor(field, 2, 8, 8);
        float[] between = cellColor(field, 5, 8, 8);
        assertTrue(atLight[0] == 1f && atLight[2] < 0.5f, "red wins where it is brighter: " + Arrays.toString(atLight));
        assertTrue(between[0] > 0.2f && between[2] > 0.2f, "both mix in between: " + Arrays.toString(between));

        field.setDynamicLights(new DynamicLight[0]);
        int[] after = new int[512];
        field.pack(HueField.sectionKey(0, 0, 0), after);
        assertTrue(Arrays.equals(before, after), "gone again: exactly the placed light's color");
    }

    @Test
    void unchangedDynamicLightsRepackNothing() {
        HueField field = new HueField();
        field.loadSection(0, 0, 0, null, new Int2IntOpenHashMap());
        DynamicLight light = new DynamicLight(4.5, 8.5, 8.5, 14, BLUE);
        field.setDynamicLights(new DynamicLight[] {light});
        field.drainRepack(key -> { });
        field.setDynamicLights(new DynamicLight[] {new DynamicLight(4.5, 8.5, 8.5, 14, BLUE)});
        LongSet repacked = new LongOpenHashSet();
        field.drainRepack(repacked::add);
        assertTrue(repacked.isEmpty(), "same lights: " + repacked);
    }
}
