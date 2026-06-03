package ch.uemasa.syncmatica.comm.exchange;

import ch.uemasa.syncmatica.comm.ExchangeTarget;
import ch.uemasa.syncmatica.net.PacketType;
import ch.uemasa.syncmatica.net.SyncByteBuf;

/** A stateful, multi-packet conversation with one {@link ExchangeTarget}. */
public interface Exchange {

    /** Called once when the exchange starts; may send the opening packet(s). */
    void init();

    /** Non-consuming test: does this exchange want to handle the given packet? */
    boolean checkPacket(PacketType type, byte[] body);

    /** Handle a packet previously accepted by {@link #checkPacket}. */
    void handle(PacketType type, SyncByteBuf body);

    boolean isFinished();

    boolean isSuccessful();

    void close(boolean notifyPartner);

    ExchangeTarget getPartner();
}
