package ch.uemasa.syncmatica.net;

import ch.uemasa.syncmatica.Reference;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Sends a Syncmatica payload straight down the player's connection.
 *
 * <p>Bukkit's {@code Player#sendPluginMessage} drops payloads to clients that have not registered the
 * channel via {@code minecraft:register}. The unmodified Fabric Syncmatica client uses raw payload types
 * and never registers, so a normal plugin message never reaches it. This builds the same
 * {@code ClientboundCustomPayloadPacket} (a {@code DiscardedPayload}, exactly like CraftBukkit) and sends
 * it directly via the connection, bypassing the listening check.
 *
 * <p>The NMS access is reflective so the plugin compiles against {@code paper-api} alone (no server jar
 * or paperweight needed). MC 26.1 ships unobfuscated, so the class/member names below are stable.
 */
public final class RawChannel {

    private static final Logger LOGGER = Logger.getLogger("SyncmaticaPaper");

    private static final boolean AVAILABLE;
    private static final Object CHANNEL_ID;
    private static final Constructor<?> DISCARDED_CTOR;
    private static final Constructor<?> PACKET_CTOR;
    private static final Method GET_HANDLE;
    private static final Field CONNECTION_FIELD;
    private static final Method SEND;

    static {
        boolean ok = false;
        Object channelId = null;
        Constructor<?> discardedCtor = null;
        Constructor<?> packetCtor = null;
        Method getHandle = null;
        Field connection = null;
        Method send = null;
        try {
            Class<?> identifier = Class.forName("net.minecraft.resources.Identifier");
            channelId = identifier.getMethod("parse", String.class).invoke(null, Reference.CHANNEL);

            Class<?> discardedPayload = Class.forName("net.minecraft.network.protocol.common.custom.DiscardedPayload");
            discardedCtor = discardedPayload.getConstructor(identifier, byte[].class);

            Class<?> customPayload = Class.forName("net.minecraft.network.protocol.common.custom.CustomPacketPayload");
            Class<?> packet = Class.forName("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket");
            packetCtor = packet.getConstructor(customPayload);

            Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            getHandle = craftPlayer.getMethod("getHandle");
            Class<?> serverPlayer = getHandle.getReturnType();
            connection = serverPlayer.getField("connection");
            connection.setAccessible(true);

            Class<?> packetIface = Class.forName("net.minecraft.network.protocol.Packet");
            send = connection.getType().getMethod("send", packetIface);

            ok = true;
        } catch (Throwable t) {
            LOGGER.severe("Syncmatica: could not wire the NMS custom-payload transport; "
                    + "the plugin will not reach clients. " + t);
        }
        AVAILABLE = ok;
        CHANNEL_ID = channelId;
        DISCARDED_CTOR = discardedCtor;
        PACKET_CTOR = packetCtor;
        GET_HANDLE = getHandle;
        CONNECTION_FIELD = connection;
        SEND = send;
    }

    private RawChannel() {
    }

    /** @return whether the reflective NMS transport resolved (false means sends are no-ops). */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static void send(Player player, byte[] data) {
        if (!AVAILABLE) {
            return;
        }
        try {
            Object handle = GET_HANDLE.invoke(player);
            Object connection = CONNECTION_FIELD.get(handle);
            // connection can be null briefly during login/teardown even while isOnline() is true.
            if (connection == null) {
                return;
            }
            Object packet = PACKET_CTOR.newInstance(DISCARDED_CTOR.newInstance(CHANNEL_ID, data));
            SEND.invoke(connection, packet);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Syncmatica raw send failed", e);
        }
    }
}
