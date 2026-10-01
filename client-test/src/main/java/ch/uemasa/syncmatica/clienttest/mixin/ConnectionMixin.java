package ch.uemasa.syncmatica.clienttest.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla only logs the message of whatever killed the connection. Log the full stack trace so a
 * failed test says where the exception came from.
 */
@Mixin(Connection.class)
abstract class ConnectionMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("syncmatica-client-test");

    @Inject(method = "exceptionCaught", at = @At("HEAD"))
    private void logException(ChannelHandlerContext ctx, Throwable cause, CallbackInfo ci) {
        LOGGER.error("Connection failed", cause);
    }
}
