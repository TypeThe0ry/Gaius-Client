package dev.gaius.parity;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;

/** A deterministic in-process PLAY peer; all actions still pass vanilla decoding and handlers. */
public final class TickParityClient extends Connection {
    private final Map<String, Integer> serverboundIds;
    private final ArrayDeque<byte[]> replies = new ArrayDeque<>();
    private PacketListener listener;
    private boolean connected = true;
    private ServerPlayer player;

    public TickParityClient(Map<String, Integer> ids) {
        super(PacketFlow.SERVERBOUND);
        serverboundIds = ids;
    }

    public void join(MinecraftServer server) {
        JsonObject script = TickPacketRecorder.script();
        String name = script.get("player").getAsString();
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), name);
        ClientInformation defaults = ClientInformation.createDefault();
        ClientInformation information = new ClientInformation(defaults.language(), script.get("viewDistance").getAsInt(),
                defaults.chatVisibility(), defaults.chatColors(), defaults.modelCustomisation(), defaults.mainHand(),
                defaults.textFilteringEnabled(), defaults.allowsListing(), defaults.particleStatus());
        player = new ServerPlayer(server, server.overworld(), profile, information);
        TickPacketRecorder.playerConstruct(player);
        setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess())));
        server.getPlayerList().placeNewPlayer(this, player,
                new CommonListenerCookie(profile, 0, information, false));
        player.setGameMode(GameType.CREATIVE);
        JsonObject spawn = script.getAsJsonObject("spawn");
        player.connection.teleport(spawn.get("x").getAsDouble() + 0.5, spawn.get("y").getAsDouble(),
                spawn.get("z").getAsDouble() + 0.5, 0.0F, 0.0F);
        TickPacketRecorder.playerSpawn(this, player);
    }

    public void beforeTick(MinecraftServer server) {
        if (player == null) join(server);
        int count = replies.size();
        while (count-- > 0) TickPacketRecorder.response(this, replies.removeFirst());
        player.connection.tick();
    }

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> protocol) {
        TickPacketRecorder.outboundProtocol(this, protocol);
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T handler) {
        listener = handler;
        TickPacketRecorder.inboundProtocol(this, protocol, handler);
    }

    @Override
    public PacketListener getPacketListener() { return listener; }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener completion, boolean flush) {
        TickPacketRecorder.outgoing(this, packet);
        acknowledge(packet);
    }

    private void acknowledge(Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.BundlePacket<?> bundle) {
            for (Packet<?> child : bundle.subPackets()) acknowledge(child);
            return;
        }
        if (packet instanceof ClientboundPlayerPositionPacket position) {
            respond("accept_teleportation", buffer -> {
                buffer.writeVarInt(position.id());
                if (TickPacketRecorder.script().get("teleportAcknowledgement").getAsString().equals("id-position")) {
                    if (!position.relatives().isEmpty()) throw new IllegalStateException("relative teleport acknowledgement unsupported");
                    var change = position.change();
                    var target = change.position();
                    buffer.writeDouble(target.x);
                    buffer.writeDouble(target.y);
                    buffer.writeDouble(target.z);
                    buffer.writeFloat(change.yRot());
                    buffer.writeFloat(change.xRot());
                }
            });
        } else if (packet instanceof ClientboundChunkBatchFinishedPacket) {
            respond("chunk_batch_received", buffer -> buffer.writeFloat(32.0F));
        } else if (packet instanceof ClientboundKeepAlivePacket keepAlive) {
            respond("keep_alive", buffer -> buffer.writeLong(keepAlive.getId()));
        } else if (packet instanceof ClientboundPingPacket ping) {
            respond("pong", buffer -> buffer.writeInt(ping.getId()));
        }
    }

    private void respond(String name, java.util.function.Consumer<FriendlyByteBuf> writer) {
        Integer id = serverboundIds.get(name);
        if (id == null) throw new IllegalArgumentException("missing serverbound packet " + name);
        FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            buffer.writeVarInt(id);
            writer.accept(buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            replies.addLast(bytes);
        } finally {
            buffer.release();
        }
    }

    @Override
    public boolean isConnected() { return connected; }
    @Override
    public boolean isMemoryConnection() { return true; }
    @Override
    public SocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 0); }
    @Override
    public void disconnect(DisconnectionDetails details) {
        connected = false;
        if (TickPacketRecorder.enabled) TickPacketRecorder.failure("virtual client disconnected: " + details);
    }
    @Override
    public void flushChannel() {
    }
}
