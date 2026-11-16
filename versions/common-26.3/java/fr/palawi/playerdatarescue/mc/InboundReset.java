package fr.palawi.playerdatarescue.mc;

import fr.palawi.playerdatarescue.mixin.ConnectionAccess;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.UnconfiguredPipelineHandler;

/**
 * Lets one more placement set the game protocol up on a connection where a failed placement
 * already did (ARCHITECTURE.md, Cleanup after a failed placement).
 *
 * <p>{@code PlayerList#placeNewPlayer} calls {@code Connection#setupInboundProtocol}, which writes a
 * task that only the {@code UnconfiguredPipelineHandler.Inbound} handler of the end of
 * configuration can run: it swaps in the game decoder. After a failed placement that handler is
 * gone, so a second placement fails on the task. The handler added here runs the same task in
 * its place: on the channel's event loop, where packets are read, it removes the decoder of the
 * failed placement and lets the task put the new one where it stands. Packets keep flowing to the
 * old decoder until then, as the client is already in the game protocol.
 */
final class InboundReset extends ChannelOutboundHandlerAdapter {
    private static final String NAME = "inbound_config";

    private InboundReset() {}

    static void beforePlacement(Connection connection) {
        ChannelPipeline pipeline =
                ((ConnectionAccess) connection).playerdatarescue$channel().pipeline();
        if (pipeline.get(NAME) == null && pipeline.get("decoder") != null) {
            pipeline.addAfter("decoder", NAME, new InboundReset());
        }
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
        if (message instanceof UnconfiguredPipelineHandler.InboundConfigurationTask task) {
            try {
                if (context.pipeline().get("bundler") != null) {
                    context.pipeline().remove("bundler");
                }
                context.pipeline().remove("decoder");
                task.run(context);
            } finally {
                ReferenceCountUtil.release(message);
            }
            promise.setSuccess();
        } else {
            context.write(message, promise);
        }
    }
}
