package dev.gaius.parity;

import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * JVM side of the packet parity gate (port/scripts/packet-parity.mjs): a vanilla server, built
 * like the seed-parity harness server (headless, no EULA prompt, no Mojang services; the version
 * layer is worldgen-seed-parity/compat/&lt;profile&gt;/ParityCompat.java), that hosts the world a
 * browser singleplayer session created and listens on a loopback TCP port, so that
 * packet-parity/jvm-replay.mjs can replay the browser client's packets against it.
 *
 * <p>The world matches a singleplayer world of the browser's create-world screen: the seed,
 * creative mode, commands allowed, the difficulty and the spawn the browser server sent (its
 * change_difficulty and set_default_spawn_position packets; the spawn is the fast initial spawn,
 * so vanilla's spawn search does not move it) and the vanilla data pack. The browser singleplayer
 * Worker runs vanilla's DedicatedServer with the server.properties of BrowserIntegratedServerMain,
 * so this server has the dedicated command set, max-players 8 and the MOTD "Gaius Integrated Server";
 * the joining player is the singleplayer owner (an operator, as the browser host is),
 * authentication is off, and the view and simulation distances are the browser server's. The
 * connection is uncompressed, as the browser host's is (vanilla singleplayer's memory connection
 * is not compressed either); the LAN guests of the Worker keep threshold 256.
 *
 * <p>usage: {@code PacketParityServer --protocol-table <json>} writes the packet id table of every
 * connection protocol of this version (handshake, status, login, configuration, play; both flows).
 * {@code PacketParityServer --seed <long> --universe <dir> --port <n> --player <name> --spawn x,y,z
 * --view-distance <n> --simulation-distance <n> [--difficulty <id>] [--duration-s <s>] [--ready <file>]
 * [--stop <file>]}
 * runs the server until the stop file appears or the duration ends.
 */
public final class PacketParityServer {
    private PacketParityServer() {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (args.length == 2 && args[0].equals("--protocol-table")) {
            writeProtocolTable(Path.of(args[1]));
            System.exit(0);
        }
        Options options = Options.parse(args);
        Files.createDirectories(options.universe);
        String worldName = "packet-parity-" + Long.toUnsignedString(options.seed);
        LevelStorageSource source = LevelStorageSource.createDefault(options.universe);
        LevelStorageSource.LevelStorageAccess access = source.createAccess(worldName);
        PackRepository packs = ServerPacksSource.createPackRepository(access);
        SessionServer server = MinecraftServer.spin(thread -> {
            WorldStem stem = ParityCompat.loadStem(packs, options.seed, GameType.CREATIVE, options.difficulty,
                    true, true, net.minecraft.commands.Commands.CommandSelection.DEDICATED);
            return new SessionServer(thread, access, packs, stem, options);
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(options.durationSeconds);
        while (server.isRunning() || !server.finished) {
            Thread.sleep(100);
            if (server.failure != null) {
                break;
            }
            if (System.nanoTime() > deadline || (options.stop != null && Files.exists(options.stop))) {
                server.halt(false);
                deadline = Long.MAX_VALUE;
            }
        }
        if (server.failure != null) {
            server.failure.printStackTrace();
            System.out.println("PACKET_PARITY_SERVER_FAIL " + server.failure);
            System.exit(1);
        }
        System.out.println("PACKET_PARITY_SERVER_STOPPED");
        System.exit(0);
    }

    record Options(long seed, Path universe, int port, String player, BlockPos spawn, int viewDistance,
            int simulationDistance, Difficulty difficulty, long durationSeconds, Path ready, Path stop) {
        static Options parse(String[] args) {
            Long seed = null;
            Path universe = null;
            int port = 0;
            String player = null;
            BlockPos spawn = null;
            int view = 0;
            int simulation = 0;
            long duration = 600;
            Difficulty difficulty = Difficulty.NORMAL;
            Path ready = null;
            Path stop = null;
            for (int index = 0; index < args.length; index++) {
                String name = args[index];
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException(name + " needs a value");
                }
                String value = args[++index];
                switch (name) {
                    case "--seed" -> seed = Long.parseLong(value);
                    case "--universe" -> universe = Path.of(value);
                    case "--port" -> port = Integer.parseInt(value);
                    case "--player" -> player = value;
                    case "--spawn" -> {
                        String[] parts = value.split(",");
                        spawn = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[2]));
                    }
                    case "--view-distance" -> view = Integer.parseInt(value);
                    case "--simulation-distance" -> simulation = Integer.parseInt(value);
                    case "--duration-s" -> duration = Long.parseLong(value);
                    case "--difficulty" -> difficulty = Difficulty.byId(Integer.parseInt(value));
                    case "--ready" -> ready = Path.of(value);
                    case "--stop" -> stop = Path.of(value);
                    default -> throw new IllegalArgumentException("unknown argument " + name);
                }
            }
            if (seed == null || universe == null || port <= 0 || player == null || spawn == null
                    || view <= 0 || simulation <= 0) {
                throw new IllegalArgumentException("--seed, --universe, --port, --player, --spawn, "
                        + "--view-distance and --simulation-distance are required");
            }
            return new Options(seed, universe, port, player, spawn, view, simulation, difficulty, duration, ready, stop);
        }
    }

    /** The singleplayer-like vanilla server of one session. */
    static final class SessionServer extends ParityCompat.ServerBase {
        private final Options options;
        volatile boolean finished;
        volatile Throwable failure;

        SessionServer(Thread thread, LevelStorageSource.LevelStorageAccess access, PackRepository packs,
                WorldStem stem, Options options) {
            super(thread, access, packs, stem);
            this.options = options;
        }

        @Override
        protected boolean initServer() {
            boolean started = super.initServer();
            try {
                setUsesAuthentication(false);
                setSingleplayerProfile(new GameProfile(UUID.nameUUIDFromBytes(
                        ("OfflinePlayer:" + options.player).getBytes(StandardCharsets.UTF_8)), options.player));
                setDefaultGameType(GameType.CREATIVE);
                setMotd("Gaius Integrated Server");
                overworld().setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, options.spawn, 0.0F, 0.0F));
                getPlayerList().setViewDistance(options.viewDistance);
                getPlayerList().setSimulationDistance(options.simulationDistance);
                removeJfrCommand();
                getConnection().startTcpServerListener(InetAddress.getLoopbackAddress(), options.port);
                System.out.println("PACKET_PARITY_SERVER_READY port=" + options.port + " spawn=" + options.spawn.getX()
                        + "," + options.spawn.getY() + "," + options.spawn.getZ() + " view=" + options.viewDistance
                        + " simulation=" + options.simulationDistance);
                if (options.ready != null) {
                    Files.writeString(options.ready, "ready\n", StandardCharsets.UTF_8);
                }
            } catch (IOException exception) {
                failure = exception;
                return false;
            }
            return started;
        }

        /**
         * The browser host's connection is not compressed, like the memory connection of vanilla
         * singleplayer (the Worker's host tunnel skips ClientboundLoginCompressionPacket, only Open
         * to LAN guests keep the dedicated server's threshold of 256). This server listens on TCP,
         * which vanilla compresses, so it turns the threshold off to send the same login sequence.
         */
        @Override
        public int getCompressionThreshold() {
            return -1;
        }

        /**
         * Vanilla registers /jfr only where JvmProfiler is available (Java Flight Recorder); the
         * browser has no JFR, so its command tree has no jfr node. The same node is taken out of this
         * server's root (brigadier keeps a node's children in the "children" and "literals" maps).
         */
        private void removeJfrCommand() {
            Object root = getCommands().getDispatcher().getRoot();
            try {
                for (String field : new String[] {"children", "literals"}) {
                    java.lang.reflect.Field map = com.mojang.brigadier.tree.CommandNode.class.getDeclaredField(field);
                    map.setAccessible(true);
                    ((java.util.Map<?, ?>) map.get(root)).remove("jfr");
                }
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("brigadier CommandNode changed", exception);
            }
        }

        @Override
        protected void parityTick(BooleanSupplier haveTime) {
            vanillaTick(haveTime);
        }

        @Override
        protected boolean realTimeTicks() {
            return true;
        }

        @Override
        protected void parityExit() {
            finished = true;
        }

        @Override
        protected void parityCrash(String report) {
            failure = new IllegalStateException(report);
        }

        @Override
        public boolean isSingleplayerOwner(NameAndId player) {
            return options.player.equals(player.name());
        }

        /** The browser server's player limit (its server.properties: max-players=8). */
        @Override
        public int getMaxPlayers() {
            return 8;
        }

        /** The browser singleplayer Worker runs vanilla's DedicatedServer. */
        @Override
        public boolean isDedicatedServer() {
            return true;
        }
    }

    /**
     * Every connection protocol of this version as {"&lt;protocol&gt;/&lt;flow&gt;": {"&lt;id&gt;":
     * "&lt;packet type&gt;"}}: the static protocol templates of the *Protocols classes, listed
     * through ProtocolInfo.DetailsProvider; plus "blockStates", the size of the block state id
     * registry (the bits of a chunk section's direct palette).
     */
    static void writeProtocolTable(Path output) throws IOException, ReflectiveOperationException {
        String[] owners = {
            "net.minecraft.network.protocol.handshake.HandshakeProtocols",
            "net.minecraft.network.protocol.status.StatusProtocols",
            "net.minecraft.network.protocol.login.LoginProtocols",
            "net.minecraft.network.protocol.configuration.ConfigurationProtocols",
            "net.minecraft.network.protocol.game.GameProtocols",
        };
        TreeMap<String, TreeMap<Integer, String>> tables = new TreeMap<>();
        for (String owner : owners) {
            for (Field field : Class.forName(owner).getFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Object value = field.get(null);
                if (!(value instanceof ProtocolInfo.DetailsProvider provider)) {
                    continue;
                }
                ProtocolInfo.Details details = provider.details();
                String key = details.id().id() + "/" + details.flow().id();
                TreeMap<Integer, String> table = tables.computeIfAbsent(key, ignored -> new TreeMap<>());
                details.listPackets((type, id) -> table.put(id, type.id().toString()));
            }
        }
        List<String> entries = new ArrayList<>();
        for (var table : tables.entrySet()) {
            List<String> packets = new ArrayList<>();
            for (var packet : table.getValue().entrySet()) {
                packets.add(String.format(Locale.ROOT, "\"%d\": \"%s\"", packet.getKey(), packet.getValue()));
            }
            entries.add("  \"" + table.getKey() + "\": {" + String.join(", ", packets) + "}");
        }
        // Command argument types by network id, for decoding the commands packet's parser properties.
        var argumentTypes = net.minecraft.core.registries.BuiltInRegistries.COMMAND_ARGUMENT_TYPE;
        List<String> arguments = new ArrayList<>();
        for (int id = 0; id < argumentTypes.size(); id++) {
            var info = argumentTypes.byId(id);
            arguments.add(String.format(Locale.ROOT, "{\"name\": \"%s\", \"singleton\": %s}", argumentTypes.getKey(info),
                    info instanceof net.minecraft.commands.synchronization.SingletonArgumentInfo<?>));
        }
        String json = "{\n  \"minecraft\": \"" + SharedConstants.getCurrentVersion().name() + "\",\n"
                + "  \"commandArgumentTypes\": [" + String.join(", ", arguments) + "],\n"
                + "  \"protocol\": " + SharedConstants.getProtocolVersion() + ",\n"
                + "  \"blockStates\": " + net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.size() + ",\n"
                + "  \"sectionShorts\": " + (java.util.Arrays.stream(
                        net.minecraft.world.level.chunk.LevelChunkSection.class.getDeclaredFields())
                        .anyMatch(field -> field.getName().equals("fluidCount")) ? 2 : 1) + ",\n"
                + String.join(",\n", entries) + "\n}\n";
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, json, StandardCharsets.UTF_8);
        System.out.println("PACKET_PARITY_PROTOCOL_TABLE " + output + " protocols=" + tables.size());
    }
}
