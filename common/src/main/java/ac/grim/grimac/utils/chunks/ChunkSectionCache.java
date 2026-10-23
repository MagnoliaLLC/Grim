package ac.grim.grimac.utils.chunks;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.utils.latency.CompensatedWorld;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.ByteArray3d;
import com.github.retrooper.packetevents.protocol.world.chunk.NibbleArray3d;
import com.github.retrooper.packetevents.protocol.world.chunk.ShortArray3d;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v1_16.Chunk_v1_9;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v1_7.Chunk_v1_7;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v1_8.Chunk_v1_8;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.GlobalPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.ListPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.MapPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.Palette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.PaletteType;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.SingletonPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.BaseStorage;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.BitStorage;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.LegacyFlexibleStorage;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ChunkSectionCache {

    // Optimization n.4: PacketEvents does not expose Chunk_v1_9's DataPalette.
    // A MethodHandle getter compiles down to a plain field load after JIT warmup.
    private static final MethodHandle V1_9_DATA = resolveV19Getter("dataPalette", DataPalette.class);
    // Nor its block count, which a copy of the section has to keep.
    private static final MethodHandle V1_9_BLOCK_COUNT = resolveV19Getter("blockCount", int.class);

    private static MethodHandle resolveV19Getter(String field, Class<?> type) {
        try {
            return MethodHandles.privateLookupIn(Chunk_v1_9.class, MethodHandles.lookup())
                    .findGetter(Chunk_v1_9.class, field, type);
        } catch (Throwable t) {
            return null;
        }
    }

    private static DataPalette v19Data(Chunk_v1_9 section) {
        if (V1_9_DATA == null) return null;

        try {
            return (DataPalette) V1_9_DATA.invokeExact(section);
        } catch (Throwable t) {
            throw new IllegalStateException("Chunk_v1_9.dataPalette accessor failed", t);
        }
    }

    private static int v19BlockCount(Chunk_v1_9 section) {
        try {
            return (int) V1_9_BLOCK_COUNT.invokeExact(section);
        } catch (Throwable t) {
            throw new IllegalStateException("Chunk_v1_9.blockCount accessor failed", t);
        }
    }

    private record Entry(BaseChunk section, AtomicInteger refs) { }

    public record SharedRef(BaseChunk section, long key) { }

    private static final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();

    // Optimization n.1: most chunk sections are all-air, so we can hash once and reuse.
    public static final long AIR_HASH = airHash();
    // Two types of chunks exist, so cache both types.
    private static final AtomicReference<Entry> AIR_V1_18 = new AtomicReference<>();
    private static final AtomicReference<Entry> AIR_V1_9 = new AtomicReference<>();
    private static final AtomicReference<Entry> AIR_V1_8 = new AtomicReference<>();
    private static final AtomicReference<Entry> AIR_V1_7 = new AtomicReference<>();

    ChunkSectionCache() {
    }

    public static boolean isSharingEnabled() {
        try {
            return GrimAPI.INSTANCE.getConfigManager().getConfig()
                    .getBooleanElse("experimental-chunk-sharing", false);
        } catch (Exception e) {
            // Config not ready (early startup, unit tests): behave as today
            return false;
        }
    }

    private static long airHash() {
        long hash = 0xcbf29ce484222325L;

        for (int i = 0; i < 4096; i++) {
            hash *= 0x100000001b3L;
        }

        return hash;
    }

    // Experimental encoding-based hash: mixes palette size + mapping + storage bits
    // + raw storage longs instead of decoding 4096 cells. 20-80x faster with modern chunks.
    // This needs further testing before making it the default implementation.
    public static long optimizedHashSection(BaseChunk section) {
        long hash = 0xcbf29ce484222325L;

        if (section instanceof Chunk_v1_18 chunk) {
            if (section.isEmpty()) return AIR_HASH;
            return rawHashDataPalette(chunk.getChunkData());
        }

        // Optimization n.3: dedicated methods for each chunk version (inline)
        if (section instanceof Chunk_v1_8 chunk) {
            for (short block : chunk.getBlocks().getData()) {
                hash ^= (block & 0xFFFFL);
                hash *= 0x100000001b3L;
            }

            return hash == 0 ? 1 : hash;
        }

        if (section instanceof Chunk_v1_7 chunk) {
            for (byte block : chunk.getBlocks().getData()) {
                hash ^= (block & 0xFFL);
                hash *= 0x100000001b3L;
            }

            for (byte meta : chunk.getMetadata().getData()) {
                hash ^= (meta & 0xFFL);
                hash *= 0x100000001b3L;
            }

            if (chunk.getExtendedBlocks() != null) {
                for (byte block : chunk.getExtendedBlocks().getData()) {
                    hash ^= (block & 0xFFL);
                    hash *= 0x100000001b3L;
                }
            }

            return hash == 0 ? 1 : hash;
        }

        if (section instanceof Chunk_v1_9 chunk) {
            DataPalette data = v19Data(chunk);

            // Fast path may not be available; fallback.
            if (data != null) {
                return rawHashDataPalette(data);
            }

            // MethodHandle unavailable (newer PacketEvents moved the field):
            // fall back to decoding 4096 cells.
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        hash ^= (section.getBlockId(x, y, z) & 0xFFFFFFFFL);
                        hash *= 0x100000001b3L;
                    }
                }
            }

            return hash == 0 ? 1 : hash;
        }

        throw new IllegalArgumentException("Unsupported chunk version");
    }

    // Shared raw hash over palette mapping + storage bits. Bit-identical to the
    // former inline v1_18 body for indirect palettes.
    private static long rawHashDataPalette(DataPalette data) {
        long hash = 0xcbf29ce484222325L;

        if (data.palette instanceof GlobalPalette) {
            // Direct encoding: the mapping is the identity, storage longs alone
            // identify the content. (GlobalPalette.size() is Integer.MAX_VALUE,
            // so the palette loop below must be skipped, not just slow.)
            hash ^= 0x165667b19e3779f9L;
            hash *= 0x100000001b3L;
        } else {
            int size = data.palette.size();
            hash ^= (size & 0xFFFFFFFFL);
            hash *= 0x100000001b3L;

            for (int i = 0; i < size; i++) {
                hash ^= (data.palette.idToState(i) & 0xFFFFFFFFL);
                hash *= 0x100000001b3L;
            }
        }

        BaseStorage storage = data.storage;

        // SingletonPalette (single-block-type sections) has no storage
        if (storage != null) {
            hash ^= (storage.getBitsPerEntry() & 0xFFFFFFFFL);
            hash *= 0x100000001b3L;

            for (long word : storage.getData()) {
                hash ^= word;
                hash *= 0x100000001b3L;
            }
        } else {
            // Palette size + single mapping already hashed above uniquely identifies content
            hash ^= 0x9e3779b97f4a7c15L;
            hash *= 0x100000001b3L;
        }

        return hash == 0 ? 1 : hash;
    }

    public static boolean sectionsEqual(BaseChunk a, BaseChunk b) {
        if (a == b) return true;

        if (a.isEmpty() || b.isEmpty()) {
            return a.isEmpty() && b.isEmpty();
        }

        // Optimization n.2: Same encoding => identical raw storage + palette mapping proves equal
        // content without decoding 4096 cells. Different encodings fall through to the content scan.
        if (a instanceof Chunk_v1_18 aChunk && b instanceof Chunk_v1_18 bChunk
                && rawPalettesEqual(aChunk.getChunkData(), bChunk.getChunkData())) {
            return true;
        }

        if (a instanceof Chunk_v1_9 aChunk && b instanceof Chunk_v1_9 bChunk) {
            DataPalette da = v19Data(aChunk);
            DataPalette db = v19Data(bChunk);

            if (da != null && db != null && rawPalettesEqual(da, db)) {
                return true;
            }
        }

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (a.getBlockId(x, y, z) != b.getBlockId(x, y, z)) return false;
                }
            }
        }

        return true;
    }

    private static boolean rawPalettesEqual(DataPalette da, DataPalette db) {
        if (da == db) return true;

        // Direct encoding uses the identity mapping: storage alone decides.
        // (GlobalPalette.size() is Integer.MAX_VALUE; looping it would hang.)
        boolean globalA = da.palette instanceof GlobalPalette;
        boolean globalB = db.palette instanceof GlobalPalette;

        if (globalA | globalB) {
            return globalA & globalB && storagesEqual(da.storage, db.storage);
        }

        // Same sections must have same palette size
        if (da.palette.size() != db.palette.size()) return false;

        if (!storagesEqual(da.storage, db.storage)) return false;

        // Fallback in case that the two sections have the same storage but different palettes
        return paletteMappingsEqual(da.palette, db.palette);
    }

    private static boolean storagesEqual(BaseStorage sa, BaseStorage sb) {
        if (sa == sb) return true;

        // SingletonPalette doesn't hold a storage
        if (sa == null || sb == null) return false;

        // Same sections must have same bits per entry
        if (sa.getBitsPerEntry() != sb.getBitsPerEntry()) return false;

        return Arrays.equals(sa.getData(), sb.getData());
    }

    private static boolean paletteMappingsEqual(Palette pa, Palette pb) {
        if (pa == pb) return true;

        int size = pa.size();

        if (size != pb.size()) return false;

        for (int i = 0; i < size; i++) {
            if (pa.idToState(i) != pb.idToState(i)) return false;
        }

        return true;
    }

    // Clones the section: same palette, same storage at the same bit width, same block and fluid counts.
    // Replaying 4096 set() calls into an empty section did not give the same section back. Every cell of an
    // empty palette reads its first entry, so set() counted blocks against the wrong state (an all-air section
    // took its first block into all 4096 cells and kept a block count of 0, which Collisions skips), and the
    // palette grew back through PacketEvents' 15 bit global palette, which cannot hold 26.3's block ids.
    public static BaseChunk copySection(BaseChunk source) {
        if (source instanceof Chunk_v1_18 chunk) {
            return new Chunk_v1_18(chunk.getBlockCount(), chunk.getFluidCount(),
                    copyData(chunk.getChunkData()), copyData(chunk.getBiomeData()));
        }

        if (source instanceof Chunk_v1_9 chunk) return copyV19(chunk);

        if (source instanceof Chunk_v1_8 chunk) {
            return new Chunk_v1_8(new ShortArray3d(chunk.getBlocks().getData().clone()),
                    copyNibbles(chunk.getBlockLight()), copyNibbles(chunk.getSkyLight()));
        }

        if (source instanceof Chunk_v1_7 chunk) {
            return new Chunk_v1_7(new ByteArray3d(chunk.getBlocks().getData().clone()), copyNibbles(chunk.getMetadata()),
                    copyNibbles(chunk.getBlockLight()), copyNibbles(chunk.getSkyLight()), copyNibbles(chunk.getExtendedBlocks()));
        }

        throw new IllegalArgumentException("Unsupported section type: " + source.getClass());
    }

    private static Chunk_v1_9 copyV19(Chunk_v1_9 section) {
        DataPalette data = v19Data(section);

        if (data != null && V1_9_BLOCK_COUNT != null) {
            return new Chunk_v1_9(v19BlockCount(section), copyData(data),
                    copyNibbles(section.getBlockLight()), copyNibbles(section.getSkyLight()));
        }

        // MethodHandles unavailable (newer PacketEvents moved the fields): rebuild cell by cell.
        // Setting a block first puts air in palette slot 0, as CompensatedWorld.create() does,
        // so the copy starts as real air and set() counts every block written into it.
        Chunk_v1_9 copy = new Chunk_v1_9(0, PaletteType.CHUNK.create());
        copy.set(0, 0, 0, 0);

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    copy.set(x, y, z, section.getBlockId(x, y, z));
                }
            }
        }

        return copy;
    }

    private static DataPalette copyData(DataPalette data) {
        if (data == null) return null; // biomes are dropped when the chunk is read

        return new DataPalette(copyPalette(data.palette), copyStorage(data.storage), data.paletteType);
    }

    private static Palette copyPalette(Palette palette) {
        // Neither changes after creation: a state a singleton palette lacks replaces the palette
        if (palette instanceof GlobalPalette || palette instanceof SingletonPalette) return palette;

        int[] states = new int[palette.size()];
        for (int id = 0; id < states.length; id++) {
            states[id] = palette.idToState(id);
        }

        if (palette instanceof ListPalette) return new ListPalette(palette.getBits(), states);

        if (palette instanceof MapPalette) {
            MapPalette copy = new MapPalette(palette.getBits());

            for (int id = 0; id < states.length; id++) {
                // A repeated entry would shift every id after it; a list palette keeps them all in place
                if (copy.stateToId(states[id]) != id) return new ListPalette(palette.getBits(), states);
            }

            return copy;
        }

        throw new IllegalArgumentException("Unsupported palette type: " + palette.getClass());
    }

    private static BaseStorage copyStorage(BaseStorage storage) {
        if (storage == null) return null; // SingletonPalette doesn't hold a storage

        if (storage instanceof BitStorage bits) {
            return new BitStorage(bits.getBitsPerEntry(), bits.getSize(), bits.getData().clone());
        }

        if (storage instanceof LegacyFlexibleStorage legacy) {
            return new LegacyFlexibleStorage(legacy.getBitsPerEntry(), legacy.getData().clone());
        }

        throw new IllegalArgumentException("Unsupported storage type: " + storage.getClass());
    }

    private static NibbleArray3d copyNibbles(NibbleArray3d nibbles) {
        return nibbles == null ? null : new NibbleArray3d(nibbles.getData().clone());
    }

    // Merges a partial (non ground-up) section update into an existing column.
    // Incoming sections are interned when chunk sharing is enabled, and any
    // shared section they replace is released.
    public static void mergeIncomingSections(CompensatedWorld world, int chunkX, int chunkZ, BaseChunk[] incoming) {
        Column existing = world.getChunk(chunkX, chunkZ);

        if (existing == null) {
            // Corrupting the player's empty chunk is actually quite meaningless
            // You are able to set blocks inside it, and they do apply, it just always returns air despite what its data says
            // So go ahead, corrupt the player's empty chunk and make it no longer all air, it doesn't matter
            //
            // LogUtil.warn("Invalid non-ground up continuous sent for empty chunk " + chunkX + " " + chunkZ + " for " + player.user.getProfile().getName() + "! This corrupts the player's empty chunk!");
            return;
        }

        boolean share = ChunkSectionCache.isSharingEnabled();

        BaseChunk[] current = existing.chunks();
        BaseChunk[] interned = new BaseChunk[incoming.length];

        long[] keys = existing.sectionKeys();
        long[] internedKeys = new long[incoming.length];

        for (int i = 0; i < incoming.length; i++) {
            if (incoming[i] == null) continue;
            if (share) {
                try {
                    ChunkSectionCache.SharedRef ref = internRef(incoming[i]);
                    interned[i] = ref.section();
                    internedKeys[i] = ref.key();
                } catch (RuntimeException e) {
                    // Roll back refs acquired so far so a hashing bug can't leak entries
                    for (int j = 0; j < i; j++) {
                        if (internedKeys[j] != 0L) release(internedKeys[j], interned[j]);
                    }
                    throw e;
                }
            } else {
                interned[i] = incoming[i];
            }
        }

        for (int i = 0; i < current.length && i < incoming.length; i++) {
            if (interned[i] == null) continue;
            if (keys[i] != 0L) release(keys[i], current[i]);
            current[i] = interned[i];
            keys[i] = internedKeys[i];
        }
    }

    public static Column shareColumnSections(Column column) {
        if (!ChunkSectionCache.isSharingEnabled()) return column;

        BaseChunk[] sections = column.chunks();
        long[] keys = column.sectionKeys();

        for (int i = 0; i < sections.length; i++) {
            if (sections[i] == null) continue;

            // Checks if there is already a shared reference
            try {
                ChunkSectionCache.SharedRef ref = internRef(sections[i]);
                sections[i] = ref.section();
                keys[i] = ref.key();
            } catch (RuntimeException e) {
                // Fresh columns start with keys[i] == 0, so only the refs acquired
                // in this loop can leak: release them and zero the keys.
                for (int j = 0; j < i; j++) {
                    if (keys[j] != 0L) {
                        release(keys[j], sections[j]);
                        keys[j] = 0L;
                    }
                }
                throw e;
            }
        }

        return column;
    }

    public static void releaseColumnSections(Column column) {
        BaseChunk[] sections = column.chunks();
        long[] keys = column.sectionKeys();

        if (sections == null || keys == null) return;

        for (int i = 0; i < sections.length && i < keys.length; i++) {
            if (keys[i] != 0L) {
                release(keys[i], sections[i]);
                keys[i] = 0L;
            }
        }
    }

    public static void releaseSharedSections(CompensatedWorld world) {
        for (Column column : world.chunks.values()) {
            if (column != null) releaseColumnSections(column);
        }
    }

    public static BaseChunk detachSectionForWrite(Column column, int sectionIndex, BaseChunk section) {
        long[] sectionKeys = column.sectionKeys();

        if (sectionKeys[sectionIndex] == 0L) return section;

        BaseChunk copy = ChunkSectionCache.copySection(section);
        release(sectionKeys[sectionIndex], section);

        column.chunks()[sectionIndex] = copy;
        sectionKeys[sectionIndex] = 0L;

        return copy;
    }

    public static SharedRef internRef(BaseChunk fresh) {
        if (fresh.isEmpty()) {
            // Fast-path for all-air chunks
            return internAir(fresh);
        }

        final SharedRef[] result = new SharedRef[1];
        entries.compute(optimizedHashSection(fresh), (hash, existing) -> {
            if (existing != null && sectionsEqual(existing.section, fresh)) {
                existing.refs.incrementAndGet();
                result[0] = new SharedRef(existing.section, hash);
                return existing;
            }

            if (existing == null) {
                result[0] = new SharedRef(fresh, hash);
                return new Entry(fresh, new AtomicInteger(1));
            }

            // Genuine hash collision: keep the stored entry, newcomer stays private
            result[0] = new SharedRef(fresh, 0L);
            return existing;
        });
        return result[0];
    }

    private static SharedRef internAir(BaseChunk fresh) {
        AtomicReference<Entry> slot = null;

        if (fresh instanceof Chunk_v1_18) slot = AIR_V1_18;
        if (fresh instanceof Chunk_v1_9) slot = AIR_V1_9;
        if (fresh instanceof Chunk_v1_8) slot = AIR_V1_8;
        if (fresh instanceof Chunk_v1_7) slot = AIR_V1_7;

        if (slot == null) {
            throw new IllegalArgumentException("Unsupported section type: " + fresh.getClass());
        }

        Entry canonical = slot.get();

        if (canonical == null) {
            slot.compareAndSet(null, new Entry(fresh, new AtomicInteger(0)));
            canonical = slot.get();
        }

        canonical.refs.incrementAndGet();
        return new SharedRef(canonical.section, AIR_HASH);
    }

    private static AtomicReference<Entry> airSlotFor(BaseChunk section) {
        if (section instanceof Chunk_v1_18) return AIR_V1_18;
        if (section instanceof Chunk_v1_9) return AIR_V1_9;
        if (section instanceof Chunk_v1_8) return AIR_V1_8;
        if (section instanceof Chunk_v1_7) return AIR_V1_7;
        throw new IllegalArgumentException("Unsupported section type: " + section.getClass());
    }

    public static void release(long key, BaseChunk section) {
        if (key == 0L) return; // private section, never entered the cache

        if (section instanceof Chunk_v1_18 || section instanceof Chunk_v1_9
                || section instanceof Chunk_v1_8 || section instanceof Chunk_v1_7) {
            Entry air = airSlotFor(section).get();

            if (air != null && air.section == section) {
                air.refs.decrementAndGet(); // canonical air is never evicted
                return;
            }
        }

        entries.computeIfPresent(key, (hash, existing) -> {
            if (existing.section != section) return existing; // not ours, leave it
            return existing.refs.decrementAndGet() <= 0 ? null : existing;
        });
    }
}
