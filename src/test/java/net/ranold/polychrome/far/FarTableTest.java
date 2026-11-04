package net.ranold.polychrome.far;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.ranold.polychrome.field.HueField;
import net.ranold.polychrome.field.Rgb9e5;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarTableTest {

    private static int[] cells(int seed) {
        int[] cells = new int[FarTable.CELLS];
        for (int c = 0; c < cells.length; c++) {
            cells[c] = Rgb9e5.pack(0.1f + (seed % 7) * 0.1f, 0.05f * c, 0.3f, 0f);
        }
        return cells;
    }

    // every cell through the shader lookup
    private static void assertSection(FarTable table, int sx, int sy, int sz, int[] expected) {
        for (int c = 0; c < FarTable.CELLS; c++) {
            int x = sx * 16 + (c & 1) * 8, y = sy * 16 + (c >> 2) * 8, z = sz * 16 + ((c >> 1) & 1) * 8;
            assertEquals(expected == null ? 0 : expected[c], table.lookup(x + 3, y + 5, z + 1),
                    "section " + sx + "," + sy + "," + sz + " cell " + c);
        }
    }

    @Test
    void whatIsPutCanBeLookedUpPerCell() {
        FarTable table = new FarTable();
        table.setCenter(0, 0);
        int[] a = cells(1);
        table.put(HueField.sectionKey(3, -2, -7), a);
        assertSection(table, 3, -2, -7, a);
        assertSection(table, 3, -2, -6, null);
    }

    // random put/remove vs a plain map, catches probe runs broken by removal
    @Test
    void randomEditsMatchAPlainMap() {
        Random random = new Random(7);
        FarTable table = new FarTable();
        table.setCenter(0, 0);
        Long2ObjectOpenHashMap<int[]> reference = new Long2ObjectOpenHashMap<>();
        for (int step = 0; step < 40_000; step++) {
            int sx = random.nextInt(200) - 100, sy = random.nextInt(24) - 4, sz = random.nextInt(200) - 100;
            long key = HueField.sectionKey(sx, sy, sz);
            if (random.nextInt(3) == 0) {
                table.remove(key);
                reference.remove(key);
            } else {
                int[] c = cells(step);
                table.put(key, c);
                reference.put(key, c);
            }
        }
        assertEquals(reference.size(), table.size());
        for (var e : reference.long2ObjectEntrySet()) {
            long key = e.getLongKey();
            assertSection(table, HueField.sectionX(key), HueField.sectionY(key), HueField.sectionZ(key), e.getValue());
        }
        for (int i = 0; i < 2000; i++) {
            int sx = random.nextInt(200) - 100, sy = random.nextInt(24) - 4, sz = random.nextInt(200) - 100;
            if (!reference.containsKey(HueField.sectionKey(sx, sy, sz))) {
                assertSection(table, sx, sy, sz, null);
            }
        }
    }

    @Test
    void onlySectionsNearTheCameraAreOnTheGpuButAllAreKept() {
        FarTable table = new FarTable();
        table.setCenter(0, 0);
        int[] near = cells(1), far = cells(2);
        table.put(HueField.sectionKey(10, 4, 10), near);
        table.put(HueField.sectionKey(FarTable.RADIUS + 50, 4, 0), far);
        assertSection(table, 10, 4, 10, near);
        assertSection(table, FarTable.RADIUS + 50, 4, 0, null);
        assertEquals(far[0], table.stored((FarTable.RADIUS + 50) * 16, 64, 0), "kept in the store");

        table.setCenter(FarTable.RADIUS + 40, 0);  // moved: far one is near now, near one far
        while (!table.recenterStep(1)) {
            // a slice at a time like per frame
        }
        assertSection(table, FarTable.RADIUS + 50, 4, 0, far);
        assertSection(table, 10, 4, 10, null);
        assertEquals(1, table.residentCount());
    }

    // shader builds the key halves from section coords, has to equal the java key
    @Test
    void shaderKeyHalvesMatchJava() {
        Random random = new Random(3);
        for (int i = 0; i < 10_000; i++) {
            int sx = random.nextInt(4_000_000) - 2_000_000, sy = random.nextInt(4096) - 2048, sz = random.nextInt(4_000_000) - 2_000_000;
            long key = HueField.sectionKey(sx, sy, sz);
            int lo = (sy & 0xFFFFF) | ((sz & 0xFFF) << 20);
            int hi = ((sz >>> 12) & 0x3FF) | ((sx & 0x3FFFFF) << 10);
            assertEquals((int) key, lo);
            assertEquals((int) (key >>> 32), hi);
        }
    }

    @Test
    void uploadedBufferHasTheShaderLayout() {
        FarTable table = new FarTable();
        table.setCenter(0, 0);
        int[] a = cells(4);
        long key = HueField.sectionKey(1, 2, 3);
        table.put(key, a);
        IntBuffer buffer = IntBuffer.allocate(table.totalInts());
        buffer.put(new int[FarTable.HEADER_INTS]);
        table.writeIndex(0, table.indexEntries(), buffer);
        table.writeData(0, table.dataSlots(), buffer);
        int[] all = buffer.array();
        int mask = table.indexEntries() - 1;
        int i = FarTable.hash((int) key, (int) (key >>> 32)) & mask;
        while (all[FarTable.HEADER_INTS + i * 3 + 2] != -1 && all[FarTable.HEADER_INTS + i * 3] != (int) key) {
            i = (i + 1) & mask;
        }
        int slot = all[FarTable.HEADER_INTS + i * 3 + 2];
        for (int c = 0; c < FarTable.CELLS; c++) {
            assertEquals(a[c], all[table.dataStart() + slot * FarTable.CELLS + c]);
        }
    }

    @Test
    void savedAndLoadedBack(@TempDir Path dir) throws Exception {
        FarTable table = new FarTable();
        table.setCenter(0, 0);
        for (int i = 0; i < 200; i++) {
            table.put(HueField.sectionKey(i, i % 20, -i), cells(i));
        }
        long[] keys = table.snapshotKeys();
        Path file = dir.resolve("far_minecraft_overworld.bin");
        FarFile.save(file, keys, table.snapshotCells(keys));

        FarTable loaded = new FarTable();
        loaded.setCenter(0, 0);
        assertEquals(200, FarFile.load(file, loaded::putIfAbsent));
        for (int i = 0; i < 200; i++) {
            assertSection(loaded, i, i % 20, -i, cells(i));
        }
    }

    // fades red -> blue instead of stepping every 8 blocks
    @Test
    void distantColorBlendsBetweenNeighboringLights() {
        FarTable table = new FarTable();
        table.setCenter(0, 0);
        int red = Rgb9e5.pack(0.5f, 0f, 0f, 0f), blue = Rgb9e5.pack(0f, 0f, 0.5f, 0f);
        table.put(HueField.sectionKey(0, 0, 0), new int[] {red, red, red, red, red, red, red, red});
        table.put(HueField.sectionKey(1, 0, 0), new int[] {blue, blue, blue, blue, blue, blue, blue, blue});
        int previousBlue = -1;
        for (int x = 4; x <= 28; x++) {
            int hue = table.smoothHue(x, 8, 8);
            int r = (hue >> 16) & 0xFF, b = hue & 0xFF;
            int blueShare = Math.round(255f * b / (r + b));
            assertTrue(blueShare >= previousBlue, "blue grows steadily, x=" + x);
            assertTrue(previousBlue < 0 || blueShare - previousBlue <= 40, "no step at x=" + x + ": " + previousBlue + " -> " + blueShare);
            previousBlue = blueShare;
        }
        assertEquals(0xFF0000, table.smoothHue(4, 8, 8), "deep in the red section");
        assertEquals(0x0000FF, table.smoothHue(28, 8, 8), "deep in the blue section");
    }

    @Test
    void sectionColorIsKeptPerEightBlockCorner() {
        HueField field = new HueField();
        Int2IntOpenHashMap sources = new Int2IntOpenHashMap();
        sources.put(HueField.cellIndex(2, 2, 2), HueField.source(14, 0xFF0000));
        byte[] levels = new byte[4096];
        for (int i = 0; i < 4096; i++) {
            int d = Math.abs((i & 15) - 2) + Math.abs((i >> 8) - 2) + Math.abs(((i >> 4) & 15) - 2);
            levels[i] = (byte) Math.max(0, 14 - d);
        }
        field.loadSection(0, 0, 0, levels, sources);
        field.process(Integer.MAX_VALUE);
        int[] out = new int[8];
        assertTrue(field.packFar(HueField.sectionKey(0, 0, 0), out));
        float[] corner = Rgb9e5.unpack(out[HueField.farIndex(0, 0, 0)]);
        assertTrue(corner[0] > 0f && corner[1] == 0f && corner[2] == 0f, "red near the light");
        assertEquals(0, out[HueField.farIndex(15, 15, 15)], "the far corner is dark (14 - 39 < 0)");
    }
}
