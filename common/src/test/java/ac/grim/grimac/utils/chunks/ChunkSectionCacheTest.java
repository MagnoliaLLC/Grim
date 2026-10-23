package ac.grim.grimac.utils.chunks;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v1_16.Chunk_v1_9;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.GlobalPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.PaletteType;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.SingletonPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.BitStorage;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Chunk sharing hands every player the same section until one of them writes to it, then
 * {@link ChunkSectionCache#detachSectionForWrite} gives that player a copy. The copy has to read exactly like
 * the shared section did, cells and block/fluid counts alike: collisions skip a section whose count reads empty,
 * so a wrong count is a block the player cannot stand on.
 */
class ChunkSectionCacheTest {

    private static final ClientVersion VERSION = ClientVersion.V_26_3;
    private static final int AIR = 0;

    private static int stone;
    private static int dirt;
    private static int water;
    private static int barrier;

    @BeforeAll
    static void setUp() {
        if (PacketEvents.getAPI() == null) PacketEvents.setAPI(new TestApi());

        stone = WrappedBlockState.getDefaultState(VERSION, StateTypes.STONE).getGlobalId();
        dirt = WrappedBlockState.getDefaultState(VERSION, StateTypes.DIRT).getGlobalId();
        water = WrappedBlockState.getDefaultState(VERSION, StateTypes.WATER).getGlobalId();
        barrier = WrappedBlockState.getDefaultState(VERSION, StateTypes.BARRIER).getGlobalId();
    }

    @Test
    void sharedAirSectionDetachedAndWrittenOnceHoldsOneBlock() {
        Column column = sharedColumn(airSection());

        BaseChunk section = ChunkSectionCache.detachSectionForWrite(column, 0, column.chunks()[0]);
        section.set(3, 4, 5, barrier);

        assertAll(
                () -> assertEquals(1, ((Chunk_v1_18) section).getBlockCount(), "block count"),
                () -> assertFalse(section.isEmpty(), "a section holding a block reads as empty"),
                () -> assertEquals(List.of(index(3, 4, 5)), cellsHolding(section, barrier), "cells holding the block"));
    }

    @Test
    void copyKeepsTheExactCellsAndCounts() {
        Chunk_v1_18 underground = underground();
        Chunk_v1_18 surface = section(index -> {
            int y = index >> 8;
            return y < 4 ? stone : y == 4 ? water : AIR;
        });

        assertAll(
                () -> assertEquals(contents(underground), contents(ChunkSectionCache.copySection(underground)), "underground section"),
                () -> assertEquals(contents(surface), contents(ChunkSectionCache.copySection(surface)), "surface section"));
    }

    @Test
    void breakThenReplaceInDetachedSectionKeepsTheCount() {
        Column column = sharedColumn(underground());

        BaseChunk section = ChunkSectionCache.detachSectionForWrite(column, 0, column.chunks()[0]);
        section.set(8, 8, 8, AIR);
        int afterBreak = ((Chunk_v1_18) section).getBlockCount();
        section.set(8, 8, 8, stone);
        int afterReplace = ((Chunk_v1_18) section).getBlockCount();

        assertAll(
                () -> assertEquals(4095, afterBreak, "block count after the break"),
                () -> assertEquals(4096, afterReplace, "block count after the replace"),
                () -> assertFalse(section.isEmpty(), "a full section reads as empty"));
    }

    @Test
    void copiesSectionWithMoreThan256StatesAndIdsPastFifteenBits() {
        // A 26.3 server sends a section with this many states in its 16 bit global palette
        int[] states = new int[300];
        for (int i = 0; i < states.length; i++) states[i] = (1 << 15) + i;

        DataPalette data = new DataPalette(GlobalPalette.INSTANCE, new BitStorage(16, 4096), PaletteType.CHUNK);
        for (int index = 0; index < 4096; index++) data.storage.set(index, states[index % states.length]);
        Chunk_v1_18 source = withServerCounts(data);

        Chunk_v1_18 copy = (Chunk_v1_18) assertDoesNotThrow(() -> ChunkSectionCache.copySection(source));

        assertAll(
                () -> assertEquals(contents(source), contents(copy)),
                () -> assertInstanceOf(GlobalPalette.class, copy.getChunkData().palette),
                () -> assertEquals(16, copy.getChunkData().storage.getBitsPerEntry(), "storage bits"));
    }

    @Test
    void sharedLegacyAirSectionDetachedAndWrittenOnceHoldsOneBlock() {
        DataPalette data = PaletteType.CHUNK.create();
        data.set(0, 0, 0, AIR);
        Column column = sharedColumn(new Chunk_v1_9(0, data));

        BaseChunk section = ChunkSectionCache.detachSectionForWrite(column, 0, column.chunks()[0]);
        section.set(3, 4, 5, barrier);

        assertAll(
                () -> assertFalse(section.isEmpty(), "a section holding a block reads as empty"),
                () -> assertEquals(List.of(index(3, 4, 5)), cellsHolding(section, barrier), "cells holding the block"));
    }

    // The copy is a clone, so it must not share a palette or storage with the section other players still read
    @Test
    void writesToTheDetachedCopyNeverReachTheSharedSection() {
        Column column = sharedColumn(underground());
        BaseChunk shared = column.chunks()[0];
        Contents before = contents(shared);
        int paletteSize = ((Chunk_v1_18) shared).getChunkData().palette.size();

        // Enough new states to outgrow the copied palette after writing into it
        BaseChunk section = ChunkSectionCache.detachSectionForWrite(column, 0, shared);
        for (int index = 0; index < 20; index++) section.set(index & 15, index >> 8, (index >> 4) & 15, index == 0 ? AIR : barrier + index);

        assertAll(
                () -> assertEquals(before, contents(shared)),
                () -> assertEquals(paletteSize, ((Chunk_v1_18) shared).getChunkData().palette.size(), "shared palette size"),
                () -> assertEquals(0L, column.sectionKeys()[0], "detached section key"));
    }

    private static Column sharedColumn(BaseChunk section) {
        ChunkSectionCache.SharedRef ref = ChunkSectionCache.internRef(section);
        return new Column(0, 0, new BaseChunk[]{ref.section()}, new long[]{ref.key()}, 0);
    }

    // What a 26.3 server sends for a section of nothing but air
    private static Chunk_v1_18 airSection() {
        return new Chunk_v1_18(VERSION, 0, 0, new DataPalette(new SingletonPalette(AIR), null, PaletteType.CHUNK), null);
    }

    // No air at all: stone with dirt pockets
    private static Chunk_v1_18 underground() {
        return section(index -> index % 7 == 0 ? dirt : stone);
    }

    private static Chunk_v1_18 section(IntUnaryOperator stateAt) {
        DataPalette data = PaletteType.CHUNK.create();
        for (int index = 0; index < 4096; index++) {
            data.set(index & 15, index >> 8, (index >> 4) & 15, stateAt.applyAsInt(index));
        }
        return withServerCounts(data);
    }

    // The counts the server sends with the section
    private static Chunk_v1_18 withServerCounts(DataPalette data) {
        int blocks = 0;
        int fluids = 0;
        for (int index = 0; index < 4096; index++) {
            WrappedBlockState state = WrappedBlockState.getByGlobalId(VERSION, data.get(index & 15, index >> 8, (index >> 4) & 15), false);
            if (!state.getType().isAir()) blocks++;
            if (state.isFluid()) fluids++;
        }
        return new Chunk_v1_18(VERSION, blocks, fluids, data, null);
    }

    private static int index(int x, int y, int z) {
        return y << 8 | z << 4 | x;
    }

    private static List<Integer> cells(BaseChunk section) {
        List<Integer> cells = new ArrayList<>(4096);
        for (int index = 0; index < 4096; index++) cells.add(section.getBlockId(index & 15, index >> 8, (index >> 4) & 15));
        return cells;
    }

    private static List<Integer> cellsHolding(BaseChunk section, int state) {
        List<Integer> cells = cells(section);
        List<Integer> holding = new ArrayList<>();
        for (int index = 0; index < cells.size(); index++) {
            if (cells.get(index) == state) holding.add(index);
        }
        return holding;
    }

    private static Contents contents(BaseChunk section) {
        Chunk_v1_18 chunk = (Chunk_v1_18) section;
        return new Contents(chunk.getBlockCount(), chunk.getFluidCount(), cells(chunk));
    }

    private record Contents(int blockCount, int fluidCount, List<Integer> cells) {
        @Override
        public String toString() {
            Map<Integer, Integer> tally = new TreeMap<>();
            for (int state : cells) tally.merge(state, 1, Integer::sum);
            return "blockCount " + blockCount + ", fluidCount " + fluidCount + ", cells per state " + tally;
        }
    }

    private static final class TestApi extends PacketEventsAPI<Object> {
        @Override
        public boolean isLoaded() {
            return true;
        }

        @Override
        public void init() {
        }

        @Override
        public boolean isInitialized() {
            return true;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public Object getPlugin() {
            return null;
        }

        @Override
        public ServerManager getServerManager() {
            return () -> ServerVersion.V_26_3;
        }

        @Override
        public ProtocolManager getProtocolManager() {
            return null;
        }

        @Override
        public PlayerManager getPlayerManager() {
            return null;
        }

        @Override
        public NettyManager getNettyManager() {
            return null;
        }

        @Override
        public ChannelInjector getInjector() {
            return null;
        }
    }
}
