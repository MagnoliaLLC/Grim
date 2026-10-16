package ac.grim.grimac.events.packets;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;

// A player info update leads with its action set, a fixed bit set holding each Action constant's bit at its
// ordinal, read here without decoding the entries behind it: profiles, chat sessions and display names, for
// every latency and display name refresh, are most of what the full read costs.
// PacketEvents rewinds the buffer before each listener, so it reads from the packet's start.
final class PlayerInfoActions {

    private PlayerInfoActions() {
    }

    static boolean lacks(PacketSendEvent event, WrapperPlayServerPlayerInfoUpdate.Action action) {
        final Object buffer = event.getByteBuf();
        final int ordinal = action.ordinal();
        final int index = ByteBufHelper.readerIndex(buffer) + (ordinal >> 3);
        // A packet too short to hold the set is left to the full read, as before
        if (index >= ByteBufHelper.writerIndex(buffer)) return false;
        return (ByteBufHelper.getUnsignedByte(buffer, index) >> (ordinal & 7) & 1) == 0;
    }
}
