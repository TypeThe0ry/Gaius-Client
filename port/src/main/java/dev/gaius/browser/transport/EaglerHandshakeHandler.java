package dev.gaius.browser.transport;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;

/** Hands off a message-oriented offline Eagler session to the Java configuration codec. */
final class EaglerHandshakeHandler extends ChannelDuplexHandler {
    private static final int MAX_HANDSHAKE_FRAME = 64 * 1024;
    private EaglerHandshakeCodec codec;
    private boolean consumedIntention;
    private boolean finished;

    static void install(ChannelPipeline pipeline) {
        EaglerHandshakeHandler wire = new EaglerHandshakeHandler();
        // The WebSocket message is the packet boundary for this route.
        pipeline.remove("splitter");
        pipeline.remove("prepender");
        pipeline.addFirst("gaius-eagler-wire", wire);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        if (!(message instanceof ByteBuf frame)) {
            ctx.write(message, promise);
            return;
        }
        // Suppress bytes after vanilla encoding so its terminal packets still swap protocol codecs.
        try {
            if (!consumedIntention) {
                consumedIntention = true;
            } else if (codec == null) {
                Object packet = LoginProtocols.SERVERBOUND.codec().decode(frame.duplicate());
                if (!(packet instanceof ServerboundHelloPacket hello)) {
                    throw new EaglerHandshakeCodec.ProtocolException("expected Java login start");
                }
                begin(ctx, hello.name());
            } else if (finished && frame.readableBytes() == 1 && frame.getUnsignedByte(frame.readerIndex()) == 3) {
                // The gateway already acknowledges Java login on its side.
                ctx.pipeline().remove(this);
            } else {
                throw new EaglerHandshakeCodec.ProtocolException("unexpected Java packet during Eagler login");
            }
            promise.setSuccess();
        } catch (RuntimeException error) {
            promise.setFailure(error);
            ctx.fireExceptionCaught(error);
            ctx.close();
        } finally {
            frame.release();
        }
    }

    private void begin(ChannelHandlerContext ctx, String name) {
        if (codec != null) throw new IllegalStateException("Eagler login already started");
        String guest = name.matches("[A-Za-z0-9_]{3,16}") ? name : "Guest";
        int protocol = SharedConstants.getProtocolVersion();
        codec = new EaglerHandshakeCodec(guest, "", null);
        byte[] hello = codec.begin(new int[] {5}, new int[] {protocol}, "Gaius", "Gaius");
        ctx.writeAndFlush(Unpooled.wrappedBuffer(hello));
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (finished || !(message instanceof ByteBuf frame)) {
            ctx.fireChannelRead(message);
            return;
        }
        try {
            if (codec == null || frame.readableBytes() > MAX_HANDSHAKE_FRAME) {
                throw new EaglerHandshakeCodec.ProtocolException("unexpected Eagler handshake frame");
            }
            byte[] bytes = new byte[frame.readableBytes()];
            frame.readBytes(bytes);
            byte[] response = codec.receive(bytes);
            if (response != null) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(response));
            } else {
                finish(ctx);
            }
        } catch (RuntimeException error) {
            ctx.fireExceptionCaught(error);
            ctx.close();
        } finally {
            frame.release();
        }
    }

    private void finish(ChannelHandlerContext ctx) {
        // Encode through the profile's ordinary login codec, including its terminal-packet transition.
        finished = true;
        ByteBuf packet = ctx.alloc().buffer();
        try {
            GameProfile profile = new GameProfile(codec.acceptedId(), codec.acceptedName());
            LoginProtocols.CLIENTBOUND.codec().encode(packet, Connection.gaiusEaglerLoginFinished(profile));
            ctx.fireChannelRead(packet.retain());
        } finally {
            packet.release();
        }
    }
}
