import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.server.level.ServerBossEvent;

public final class DragonPacketCodecFixture {
  private static net.minecraft.core.RegistryAccess REGISTRIES = net.minecraft.core.RegistryAccess.EMPTY;

  private static <T> void encode(String name, StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
    RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), REGISTRIES);
    try {
      codec.encode(buf, value);
      System.out.println("PACKET_OK " + name + " bytes=" + buf.readableBytes());
    } catch (Throwable failure) {
      System.err.println("PACKET_FAIL " + name);
      failure.printStackTrace(System.err);
      throw failure;
    } finally {
      buf.release();
    }
  }

  public static void main(String[] args) {
    net.minecraft.SharedConstants.tryDetectVersion();
    net.minecraft.server.Bootstrap.bootStrap();
    REGISTRIES = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
        net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
    for (int phaseId = 0; phaseId < EnderDragonPhase.getCount(); phaseId++) {
      if (EnderDragonPhase.getById(phaseId).createInstance(null) == null) {
        throw new IllegalStateException("null phase instance for id " + phaseId);
      }
    }
    System.out.println("DRAGON_PHASE_FACTORY_PASS count=" + EnderDragonPhase.getCount());
    encode("ClientboundAddEntityPacket.ender_dragon", ClientboundAddEntityPacket.STREAM_CODEC,
        new ClientboundAddEntityPacket(42, UUID.randomUUID(), 7.8, 67.0, 21.0f, 0.0f, 0.0f,
            net.minecraft.world.entity.EntityTypes.ENDER_DRAGON, 0,
            new net.minecraft.world.phys.Vec3(0.0, 0.0, 0.0), 0.0));
    SynchedEntityData.DataValue<Integer> phase =
        SynchedEntityData.DataValue.create(EnderDragon.DATA_PHASE, 0);
    encode("ClientboundSetEntityDataPacket.phase", ClientboundSetEntityDataPacket.STREAM_CODEC,
        new ClientboundSetEntityDataPacket(42, List.of(phase)));

    encode("ClientboundSetEntityDataPacket.serializers", ClientboundSetEntityDataPacket.STREAM_CODEC,
        new ClientboundSetEntityDataPacket(43, List.of(
            new SynchedEntityData.DataValue<>(0, EntityDataSerializers.INT, 0),
            new SynchedEntityData.DataValue<>(1, EntityDataSerializers.ROTATIONS, new net.minecraft.core.Rotations(0.0f, 0.0f, 0.0f)),
            new SynchedEntityData.DataValue<>(2, EntityDataSerializers.OPTIONAL_UNSIGNED_INT, java.util.OptionalInt.of(42)))));

    ServerBossEvent boss = new ServerBossEvent(UUID.randomUUID(), Component.literal("Dragon"),
        BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
    encode("ClientboundBossEventPacket.add", ClientboundBossEventPacket.STREAM_CODEC,
        ClientboundBossEventPacket.createAddPacket(boss));
    encode("ClientboundBossEventPacket.update", ClientboundBossEventPacket.STREAM_CODEC,
        ClientboundBossEventPacket.createUpdateProgressPacket(boss));

    encode("ClientboundSetEquipmentPacket", ClientboundSetEquipmentPacket.STREAM_CODEC,
        new ClientboundSetEquipmentPacket(42, List.of(
            com.mojang.datafixers.util.Pair.of(EquipmentSlot.MAINHAND, ItemStack.EMPTY))));
    encode("ClientboundUpdateAttributesPacket.empty", ClientboundUpdateAttributesPacket.STREAM_CODEC,
        new ClientboundUpdateAttributesPacket(42, List.of()));
    System.out.println("DRAGON_PACKET_CODEC_PASS");
  }
}
