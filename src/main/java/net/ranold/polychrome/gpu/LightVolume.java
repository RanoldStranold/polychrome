package net.ranold.polychrome.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.field.HueField;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;

// hue field on the GPU: half res (2x2x2 blocks per cell) texel buffers in a 32^3 section window
// around the camera. buffer layout must match polychrome.glsl. GUI/items get a header with the
// tint off. render thread only, except publish() (hue worker)
public final class LightVolume {

    public static final String HEADER = "CL_Header";
    public static final String PAGES = "CL_Pages";
    public static final String CELLS = "CL_Cells";

    public static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
            .withUniform(HEADER, UniformType.UNIFORM_BUFFER)
            .withUniform(PAGES, UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT)
            .withUniform(CELLS, UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
            .build();

    // 8x8x8 cells, each 2x2x2 blocks
    public static final int CELLS_PER_SECTION = 512;
    // shader checks the same radius
    private static final int MAX_RADIUS = 15;
    private static final int PAGE_COUNT = 32 * 32 * 32;
    private static final int SLOTS = 8192;
    private static final int SLOT_BYTES = CELLS_PER_SECTION * 4;
    private static final int HEADER_BYTES = 32;
    private static final int UPLOADS_PER_FRAME = 256;

    // 1 = full color, distant shader uses it too
    public static final float SATURATION = 1.0f;

    private record Update(long key, int @Nullable [] packed) {
    }

    private final ConcurrentLinkedQueue<Update> incoming = new ConcurrentLinkedQueue<>();

    // every lit section, on the GPU or not
    private final Long2ObjectMap<int[]> sections = new Long2ObjectOpenHashMap<>();
    private final Long2IntMap resident = new Long2IntOpenHashMap();
    // missing or stale on the GPU
    private final LongOpenHashSet pending = new LongOpenHashSet();
    private final int[] pages = new int[PAGE_COUNT];
    private final long[] pageOwner = new long[PAGE_COUNT];
    private final int[] freeSlots = new int[SLOTS];
    private int freeCount;
    private boolean pagesDirty = true;
    private long cameraSection = Long.MIN_VALUE;
    private int radius = MAX_RADIUS;
    private boolean warnedFull;
    // after a reload recolor: upload it all next frame, no budget
    private volatile boolean uploadEverything;

    private @Nullable GpuBuffer header;
    private @Nullable GpuBuffer disabledHeader;
    private @Nullable GpuBuffer pageBuffer;
    private @Nullable GpuBuffer cellBuffer;
    private final ByteBuffer slotScratch = MemoryUtil.memAlloc(SLOT_BYTES);

    public LightVolume() {
        this.resident.defaultReturnValue(-1);
        this.clearResidency();
    }

    public void uploadEverythingNextFrame() {
        this.uploadEverything = true;
    }

    // from the hue worker, null = no light anymore
    public void publish(long key, int @Nullable [] packed) {
        this.incoming.add(new Update(key, packed));
    }

    // world changed etc
    public void reset() {
        this.incoming.clear();
        this.sections.clear();
        this.pending.clear();
        this.clearResidency();
    }

    private void clearResidency() {
        this.resident.clear();
        Arrays.fill(this.pages, -1);
        Arrays.fill(this.pageOwner, Long.MIN_VALUE);
        for (int i = 0; i < SLOTS; i++) {
            this.freeSlots[i] = SLOTS - 1 - i;
        }
        this.freeCount = SLOTS;
        this.pagesDirty = true;
        this.cameraSection = Long.MIN_VALUE;
    }


    // once per frame before drawing
    public void onFrame(Vec3 camera, boolean enabled) {
        GpuDevice device = RenderSystem.getDevice();
        this.ensureBuffers(device);
        CommandEncoder encoder = device.createCommandEncoder();

        int camX = (int) Math.floor(camera.x);
        int camY = (int) Math.floor(camera.y);
        int camZ = (int) Math.floor(camera.z);
        int csx = camX >> 4, csy = camY >> 4, csz = camZ >> 4;

        int wantedRadius = Math.min(MAX_RADIUS, Minecraft.getInstance().options.getEffectiveRenderDistance() + 1);
        this.applyIncoming();
        long section = HueField.sectionKey(csx, csy, csz);
        if (section != this.cameraSection || wantedRadius != this.radius) {
            this.cameraSection = section;
            this.radius = wantedRadius;
            this.recenter(csx, csy, csz);
        }
        this.upload(encoder, csx, csy, csz);

        if (this.pagesDirty) {
            ByteBuffer data = MemoryUtil.memAlloc(PAGE_COUNT * 4);
            try {
                data.asIntBuffer().put(this.pages);
                encoder.writeToBuffer(this.pageBuffer.slice(), data);
            } finally {
                MemoryUtil.memFree(data);
            }
            this.pagesDirty = false;
        }

        this.writeHeader(encoder, this.header, camera, camX, camY, camZ, enabled);
    }

    private void applyIncoming() {
        Update update;
        while ((update = this.incoming.poll()) != null) {
            if (update.packed() == null) {
                this.sections.remove(update.key());
                this.evict(update.key());
                this.pending.remove(update.key());
            } else {
                this.sections.put(update.key(), update.packed());
                this.pending.add(update.key());
            }
        }
    }

    // drop what left the window, queue everything inside it that isnt uploaded
    private void recenter(int csx, int csy, int csz) {
        LongArrayList leaving = new LongArrayList();
        for (Long2IntMap.Entry entry : this.resident.long2IntEntrySet()) {
            if (!this.inWindow(entry.getLongKey(), csx, csy, csz)) {
                leaving.add(entry.getLongKey());
            }
        }
        leaving.forEach(this::evict);
        for (long key : this.sections.keySet()) {
            if (!this.resident.containsKey(key) && this.inWindow(key, csx, csy, csz)) {
                this.pending.add(key);
            }
        }
    }

    // nearest first, per frame budget
    private void upload(CommandEncoder encoder, int csx, int csy, int csz) {
        if (this.pending.isEmpty()) {
            return;
        }
        LongArrayList candidates = new LongArrayList(this.pending.size());
        var it = this.pending.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            if (this.sections.containsKey(key) && this.inWindow(key, csx, csy, csz)) {
                candidates.add(key);
            } else {
                it.remove();  // outside the window, recenter() queues it again when it's back
            }
        }
        long[] order = candidates.toLongArray();
        long[] distance = new long[order.length];
        for (int i = 0; i < order.length; i++) {
            distance[i] = distanceSq(order[i], csx, csy, csz);
        }
        sortByDistance(order, distance);

        int uploads = 0;
        int budget = this.uploadEverything ? Integer.MAX_VALUE : UPLOADS_PER_FRAME;
        this.uploadEverything = false;
        for (long key : order) {
            if (uploads >= budget) {
                break;
            }
            int slot = this.resident.get(key);
            if (slot < 0) {
                slot = this.allocate(key, csx, csy, csz);
                if (slot < 0) {
                    break;  // full of nearer sections, rest stay pending
                }
            }
            int[] data = this.sections.get(key);
            this.slotScratch.clear();
            this.slotScratch.asIntBuffer().put(data);
            this.slotScratch.limit(SLOT_BYTES);
            encoder.writeToBuffer(this.cellBuffer.slice((long) slot * SLOT_BYTES, SLOT_BYTES), this.slotScratch);
            this.pending.remove(key);
            uploads++;
        }
    }

    // pool full: take the farthest section's slot
    private int allocate(long key, int csx, int csy, int csz) {
        int page = pageIndex(key);
        if (this.pageOwner[page] != Long.MIN_VALUE && this.pageOwner[page] != key) {
            this.evict(this.pageOwner[page]);
        }
        if (this.freeCount == 0) {
            long farthest = Long.MIN_VALUE;
            long farthestDistance = -1;
            for (long resident : this.resident.keySet()) {
                long d = distanceSq(resident, csx, csy, csz);
                if (d > farthestDistance) {
                    farthestDistance = d;
                    farthest = resident;
                }
            }
            if (farthestDistance <= distanceSq(key, csx, csy, csz)) {
                if (!this.warnedFull) {
                    this.warnedFull = true;
                    Polychrome.LOGGER.warn("Light volume full ({} sections); far light will stay white", SLOTS);
                }
                return -1;
            }
            this.evict(farthest);
            this.pending.add(farthest);
        }
        int slot = this.freeSlots[--this.freeCount];
        this.resident.put(key, slot);
        this.pageOwner[page] = key;
        this.pages[page] = slot;
        this.pagesDirty = true;
        return slot;
    }

    private void evict(long key) {
        int slot = this.resident.remove(key);
        if (slot < 0) {
            return;
        }
        this.freeSlots[this.freeCount++] = slot;
        int page = pageIndex(key);
        if (this.pageOwner[page] == key) {
            this.pageOwner[page] = Long.MIN_VALUE;
            this.pages[page] = -1;
            this.pagesDirty = true;
        }
    }

    private boolean inWindow(long key, int csx, int csy, int csz) {
        return Math.abs(HueField.sectionX(key) - csx) <= this.radius
                && Math.abs(HueField.sectionY(key) - csy) <= MAX_RADIUS
                && Math.abs(HueField.sectionZ(key) - csz) <= this.radius;
    }

    private static long distanceSq(long key, int csx, int csy, int csz) {
        long dx = HueField.sectionX(key) - csx, dy = HueField.sectionY(key) - csy, dz = HueField.sectionZ(key) - csz;
        return dx * dx + dy * dy + dz * dz;
    }

    // sorts keys by distance ascending, both arrays together
    private static void sortByDistance(long[] keys, long[] distance) {
        Integer[] index = new Integer[keys.length];
        for (int i = 0; i < index.length; i++) {
            index[i] = i;
        }
        Arrays.sort(index, (a, b) -> Long.compare(distance[a], distance[b]));
        long[] sorted = new long[keys.length];
        for (int i = 0; i < index.length; i++) {
            sorted[i] = keys[index[i]];
        }
        System.arraycopy(sorted, 0, keys, 0, keys.length);
    }

    private static int pageIndex(long key) {
        return (HueField.sectionX(key) & 31) | ((HueField.sectionZ(key) & 31) << 5) | ((HueField.sectionY(key) & 31) << 10);
    }

    private void writeHeader(CommandEncoder encoder, GpuBuffer target, Vec3 camera, int camX, int camY, int camZ, boolean enabled) {
        ByteBuffer data = MemoryUtil.memAlloc(HEADER_BYTES);
        try {
            data.putInt(camX).putInt(camY).putInt(camZ).putInt(enabled ? 1 : 0);
            data.putFloat((float) (camera.x - camX)).putFloat((float) (camera.y - camY)).putFloat((float) (camera.z - camZ)).putFloat(SATURATION);
            data.flip();
            encoder.writeToBuffer(target.slice(), data);
        } finally {
            MemoryUtil.memFree(data);
        }
    }


    private void ensureBuffers(GpuDevice device) {
        if (this.header != null) {
            return;
        }
        int texel = GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST;
        int uniform = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST;
        this.header = device.createBuffer(() -> "Polychrome header", uniform, HEADER_BYTES);
        this.disabledHeader = device.createBuffer(() -> "Polychrome header (off)", uniform, HEADER_BYTES);
        this.pageBuffer = device.createBuffer(() -> "Polychrome pages", texel, (long) PAGE_COUNT * 4);
        this.cellBuffer = device.createBuffer(() -> "Polychrome cells", texel, (long) SLOTS * SLOT_BYTES);

        CommandEncoder encoder = device.createCommandEncoder();
        this.writeHeader(encoder, this.disabledHeader, Vec3.ZERO, 0, 0, 0, false);
        this.writeHeader(encoder, this.header, Vec3.ZERO, 0, 0, 0, false);
        this.pagesDirty = true;
        Polychrome.LOGGER.info("Light volume created: {} slots, {} MiB", SLOTS, (long) SLOTS * SLOT_BYTES >> 20);
    }

    // world = false for anything but the level lightmap, those get the tint-off header
    public void bind(RenderPass pass, boolean world) {
        if (this.header == null) {
            return;
        }
        pass.setUniform(HEADER, world ? this.header : this.disabledHeader);
        pass.setUniform(PAGES, this.pageBuffer);
        pass.setUniform(CELLS, this.cellBuffer);
    }

    // diagnostics

    public int residentCount() {
        return this.resident.size();
    }

    public int pendingUploads() {
        return this.pending.size() + this.incoming.size();
    }

    public int knownSections() {
        return this.sections.size();
    }

    // 0 if no data
    public int packedAt(int x, int y, int z) {
        int[] data = this.sections.get(HueField.sectionKey(x >> 4, y >> 4, z >> 4));
        return data == null ? 0 : data[HueField.coarseIndex(x, y, z)];
    }

    public boolean isResident(int x, int y, int z) {
        return this.resident.containsKey(HueField.sectionKey(x >> 4, y >> 4, z >> 4));
    }

    // known / in window / resident / free slots / pending
    public String stats() {
        int csx = HueField.sectionX(this.cameraSection), csy = HueField.sectionY(this.cameraSection), csz = HueField.sectionZ(this.cameraSection);
        int inWindow = 0;
        for (long key : this.sections.keySet()) {
            if (this.inWindow(key, csx, csy, csz)) {
                inWindow++;
            }
        }
        return "known " + this.sections.size() + ", in window " + inWindow + ", resident " + this.resident.size()
                + ", free slots " + this.freeCount + ", pending " + this.pending.size() + ", radius " + this.radius;
    }
}
