// Generates synthetic test packets with the locally available Minecraft codecs.
// This entry point only loads built-in registry data and encodes packets.
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.phys.Vec3;

class EncodeWorld {
    // Bootstrap redirects System.out and System.err through Log4j.
    private static final PrintStream PACKETS = new PrintStream(new FileOutputStream(FileDescriptor.out));

    private static void emit(String phase, Function<ByteBuf, ?> decode, Consumer<ByteBuf> encode) {
        ByteBuf buf = Unpooled.buffer();
        try {
            encode.accept(buf);
            ByteBuf verify = buf.duplicate();
            decode.apply(verify);
            if (verify.isReadable()) throw new IllegalStateException("Trailing bytes in encoded " + phase + " packet");
            PACKETS.println("EAGLER_FIXTURE " + phase + " " + Base64.getEncoder().encodeToString(ByteBufUtil.getBytes(buf)));
        } finally {
            buf.release();
        }
    }

    public static void main(String[] args) {
        try {
            encodeWorld(Integer.parseInt(args[0]), args[1]);
        } catch (Exception error) {
            error.printStackTrace(new PrintStream(new FileOutputStream(FileDescriptor.err)));
            System.exit(1);
        }
    }

    private static void encodeWorld(int chunkId, String chatText) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var layers = RegistryLayer.createRegistryAccess();
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(ServerPacksSource.createVanillaPackSource().fullResources()))) {
            var base = layers.getAccessForLoading(RegistryLayer.WORLD);
            var pendingTags = TagLoader.loadTagsForExistingRegistries(resources, base);
            var world = RegistryDataLoader.load(resources, TagLoader.buildUpdatedLookups(base, pendingTags),
                RegistryDataLoader.WORLD_REGISTRIES, Runnable::run).join();
            pendingTags.forEach(Registry.PendingTags::apply);
            layers = layers.replaceFrom(RegistryLayer.WORLD, world);
        }
        var all = layers.compositeAccess();
        var cfg = ConfigurationProtocols.CLIENTBOUND.codec();
        RegistrySynchronization.packRegistries(RegistryOps.create(NbtOps.INSTANCE, all), all, Set.of(),
            (key, entries) -> emit("config", cfg::decode, buf -> cfg.encode(buf, new ClientboundRegistryDataPacket(key, entries))));
        var tags = TagNetworkSerialization.serializeTagsToNetwork(layers);
        emit("config", cfg::decode, buf -> cfg.encode(buf, new ClientboundUpdateTagsPacket(tags)));
        emit("config", cfg::decode, buf -> cfg.encode(buf, ClientboundFinishConfigurationPacket.INSTANCE));

        var play = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(all)).codec();
        var serverbound = GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(all), new GameProtocols.Context() {
            public boolean hasInfiniteMaterials() { return true; }
            public boolean canUseCommandBlocks() { return false; }
        }).codec();
        emit("move", serverbound::decode,
            buf -> serverbound.encode(buf, new ServerboundMovePlayerPacket.Pos(9.5, 66, 8.5, false, false)));
        emit("move", serverbound::decode,
            buf -> serverbound.encode(buf, new ServerboundMovePlayerPacket.PosRot(10.5, 66, 8.5, 0, 15, false, false)));
        emit("chat", buf -> {
            var packet = (ClientboundSystemChatPacket) play.decode(buf);
            if (packet.overlay() || !packet.content().getString().equals(chatText)) {
                throw new IllegalStateException("Decoded chat text does not match fixture");
            }
            return packet;
        }, buf -> play.encode(buf, new ClientboundSystemChatPacket(Component.literal(chatText), false)));
        var dimension = all.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD);
        var spawn = new CommonPlayerSpawnInfo(dimension, Level.OVERWORLD, 0L, GameType.CREATIVE,
            Optional.empty(), false, true, Optional.empty(), 0, 63);
        emit("play", play::decode, buf -> play.encode(buf, new ClientboundLoginPacket(1, false, Set.of(Level.OVERWORLD),
            1, 4, 4, false, true, false, spawn, false, false)));
        var abilities = new Abilities();
        abilities.invulnerable = abilities.flying = abilities.mayfly = abilities.instabuild = true;
        emit("play", play::decode, buf -> play.encode(buf, new ClientboundPlayerAbilitiesPacket(abilities)));
        var pose = new PositionMoveRotation(new Vec3(8.5, 66, 8.5), Vec3.ZERO, 0, 15);
        emit("play", play::decode, buf -> play.encode(buf, ClientboundPlayerPositionPacket.of(1, pose, Set.of())));
        emit("play", play::decode, buf -> play.encode(buf, new ClientboundSetChunkCacheCenterPacket(0, 0)));
        emit("play", play::decode, buf -> play.encode(buf, new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0)));
        emit("play", play::decode, buf -> play.encode(buf, ClientboundChunkBatchStartPacket.INSTANCE));

        // Original flat test pad, with full sky light. Send one unframed packet per chunk.
        var factory = PalettedContainerFactory.create(all);
        var air = new LevelChunkSection(factory);
        var pad = new LevelChunkSection(factory);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            var block = ((x / 4 + z / 4) % 2 == 0 ? Blocks.SMOOTH_STONE : Blocks.POLISHED_ANDESITE).defaultBlockState();
            pad.setBlockState(x, 0, z, block);
        }
        ByteBuf raw = Unpooled.buffer();
        byte[] sections;
        int minY = dimension.value().minY();
        int sectionCount = dimension.value().height() / 16;
        int padSection = (64 - minY) / 16;
        try {
            var buf = new FriendlyByteBuf(raw);
            for (int i = 0; i < sectionCount; i++) {
                (i == padSection ? pad : air).write(buf);
            }
            sections = ByteBufUtil.getBytes(buf);
        } finally {
            raw.release();
        }
        var skyMask = new BitSet();
        skyMask.set(padSection + 1);
        byte[] sky = new byte[2048];
        Arrays.fill(sky, (byte)0xff);
        var light = new ClientboundLightUpdatePacketData(skyMask, new BitSet(), new BitSet(), new BitSet(), List.of(sky), List.of());
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            int chunkX = x;
            int chunkZ = z;
            emit("play", verify -> {
                var packet = (ClientboundLevelChunkWithLightPacket) play.decode(verify);
                if (packet.x() != chunkX || packet.z() != chunkZ) throw new IllegalStateException("Wrong chunk position");
                if (chunkX == 0 && chunkZ == 0) {
                    var content = packet.chunkData().getReadBuffer();
                    for (int i = 0; i < sectionCount; i++) {
                        var section = new LevelChunkSection(factory);
                        section.read(content);
                        if (i == padSection) {
                            if (!section.getBlockState(0, 0, 0).is(Blocks.SMOOTH_STONE)
                                    || !section.getBlockState(4, 0, 0).is(Blocks.POLISHED_ANDESITE)) {
                                throw new IllegalStateException("Decoded spawn pad does not match fixture");
                            }
                        } else if (!section.hasOnlyAir()) throw new IllegalStateException("Unexpected non-air section");
                    }
                    if (content.isReadable()) throw new IllegalStateException("Trailing chunk section bytes");
                }
                return packet;
            }, rawBuf -> {
                var buf = new RegistryFriendlyByteBuf(rawBuf, all);
                buf.writeVarInt(chunkId).writeInt(chunkX).writeInt(chunkZ);
                buf.writeVarInt(0); // heightmaps
                buf.writeByteArray(sections);
                buf.writeVarInt(0); // block entities
                ClientboundLightUpdatePacketData.STREAM_CODEC.encode(buf, light);
            });
        }
        emit("play", play::decode, buf -> play.encode(buf, new ClientboundChunkBatchFinishedPacket(81)));
    }
}
