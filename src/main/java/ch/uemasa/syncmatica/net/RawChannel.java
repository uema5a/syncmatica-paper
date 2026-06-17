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
 * <p>This writes the payload straight to the player's connection instead of using Bukkit's
 * {@code Player#sendPluginMessage}, which only delivers on channels the client has registered and is
 * prone to handshake-timing races. That mirrors upstream Syncmatica's own server: it builds a clientbound
 * custom-payload packet and sends it directly, bypassing the "can the partner receive this" gate rather
 * than trusting channel registration. The payload is a {@code DiscardedPayload}, exactly like the one
 * CraftBukkit builds for plugin messages.
 *
 * <p>The NMS access is reflective so the plugin compiles against {@code paper-api} alone (no server jar
 * or paperweight needed), and so a single source tree works across Minecraft generations. Paper has run
 * Mojang-mapped at runtime since 1.20.5, but two member shapes drift between the 1.21.x line and the 2026
 * builds, so both are probed at load:
 * <ul>
 *   <li>the resource-id class — {@code ResourceLocation} on 1.21.x, renamed to {@code Identifier} in the
 *       2026 unobfuscation;</li>
 *   <li>the data-carrying {@code DiscardedPayload} constructor — {@code (id, byte[])} since 1.21.5, but
 *       {@code (id, ByteBuf)} on 1.21.4 and earlier.</li>
 * </ul>
 */
public final class RawChannel {

    private static final Logger LOGGER = Logger.getLogger("SyncmaticaPaper");

    private static final boolean AVAILABLE;
    private static final Object CHANNEL_ID;
    private static final Constructor<?> DISCARDED_CTOR;
    /** True when {@link #DISCARDED_CTOR} takes {@code byte[]} (1.21.5+); false when it takes a Netty {@code ByteBuf} (<=1.21.4). */
    private static final boolean DISCARDED_TAKES_BYTES;
    /** {@code Unpooled.wrappedBuffer(byte[])}, resolved only on the {@code ByteBuf} branch. */
    private static final Method WRAP_BUFFER;
    private static final Constructor<?> PACKET_CTOR;
    private static final Method GET_HANDLE;
    private static final Field CONNECTION_FIELD;
    private static final Method SEND;

    static {
        boolean ok = false;
        Object channelId = null;
        Constructor<?> discardedCtor = null;
        boolean takesBytes = true;
        Method wrapBuffer = null;
        Constructor<?> packetCtor = null;
        Method getHandle = null;
        Field connection = null;
        Method send = null;
        try {
            // Resource-id class: 1.21.x = ResourceLocation, 2026 = Identifier. Both expose a static
            // parse(String) (older builds only tryParse), returning the same instance type.
            Class<?> idClass = resolveClass(
                    "net.minecraft.resources.Identifier", "net.minecraft.resources.ResourceLocation");
            Method parse;
            try {
                parse = idClass.getMethod("parse", String.class);
            } catch (NoSuchMethodException e) {
                parse = idClass.getMethod("tryParse", String.class);
            }
            channelId = parse.invoke(null, Reference.CHANNEL);

            // DiscardedPayload data constructor: (id, byte[]) since 1.21.5, (id, ByteBuf) on <=1.21.4.
            Class<?> discardedPayload = Class.forName("net.minecraft.network.protocol.common.custom.DiscardedPayload");
            try {
                discardedCtor = discardedPayload.getConstructor(idClass, byte[].class);
            } catch (NoSuchMethodException e) {
                Class<?> byteBuf = Class.forName("io.netty.buffer.ByteBuf");
                discardedCtor = discardedPayload.getConstructor(idClass, byteBuf);
                takesBytes = false;
                wrapBuffer = Class.forName("io.netty.buffer.Unpooled").getMethod("wrappedBuffer", byte[].class);
            }

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
        DISCARDED_TAKES_BYTES = takesBytes;
        WRAP_BUFFER = wrapBuffer;
        PACKET_CTOR = packetCtor;
        GET_HANDLE = getHandle;
        CONNECTION_FIELD = connection;
        SEND = send;
    }

    /** Returns the first of {@code names} that resolves, so one build runs across renamed NMS classes. */
    private static Class<?> resolveClass(String... names) throws ClassNotFoundException {
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {
                // try the next candidate
            }
        }
        throw new ClassNotFoundException(String.join(" / ", names));
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
            Object payload = DISCARDED_TAKES_BYTES
                    ? DISCARDED_CTOR.newInstance(CHANNEL_ID, data)
                    : DISCARDED_CTOR.newInstance(CHANNEL_ID, WRAP_BUFFER.invoke(null, (Object) data));
            Object packet = PACKET_CTOR.newInstance(payload);
            SEND.invoke(connection, packet);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Syncmatica raw send failed", e);
        }
    }
}
