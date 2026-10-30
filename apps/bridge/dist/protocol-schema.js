export const RELAY_KEYS = Object.freeze({
    login: {
        clientboundDisconnect: ["login", "clientbound", "minecraft:login_disconnect"],
        clientboundEncryptionRequest: ["login", "clientbound", "minecraft:hello"],
        clientboundLoginFinished: ["login", "clientbound", "minecraft:login_finished"],
        clientboundCompression: ["login", "clientbound", "minecraft:login_compression"],
        serverboundHello: ["login", "serverbound", "minecraft:hello"],
        serverboundKey: ["login", "serverbound", "minecraft:key"],
        serverboundLoginAcknowledged: ["login", "serverbound", "minecraft:login_acknowledged"],
    },
    configuration: {
        clientboundDisconnect: ["configuration", "clientbound", "minecraft:disconnect"],
        clientboundFinish: ["configuration", "clientbound", "minecraft:finish_configuration"],
        clientboundKeepAlive: ["configuration", "clientbound", "minecraft:keep_alive"],
        clientboundPing: ["configuration", "clientbound", "minecraft:ping"],
        clientboundKnownPacks: ["configuration", "clientbound", "minecraft:select_known_packs"],
        clientboundResourcePackPush: ["configuration", "clientbound", "minecraft:resource_pack_push"],
        clientboundShowDialog: ["configuration", "clientbound", "minecraft:show_dialog"],
        clientboundCodeOfConduct: ["configuration", "clientbound", "minecraft:code_of_conduct"],
        serverboundFinish: ["configuration", "serverbound", "minecraft:finish_configuration"],
        serverboundKeepAlive: ["configuration", "serverbound", "minecraft:keep_alive"],
        serverboundPong: ["configuration", "serverbound", "minecraft:pong"],
        serverboundSelectKnownPacks: ["configuration", "serverbound", "minecraft:select_known_packs"],
        serverboundResourcePack: ["configuration", "serverbound", "minecraft:resource_pack"],
        serverboundCustomClickAction: ["configuration", "serverbound", "minecraft:custom_click_action"],
        serverboundAcceptCodeOfConduct: ["configuration", "serverbound", "minecraft:accept_code_of_conduct"],
    },
    play: {
        clientboundChunkBatchFinished: ["play", "clientbound", "minecraft:chunk_batch_finished"],
        clientboundChunkBatchStart: ["play", "clientbound", "minecraft:chunk_batch_start"],
        clientboundCustomPayload: ["play", "clientbound", "minecraft:custom_payload"],
        clientboundDisconnect: ["play", "clientbound", "minecraft:disconnect"],
        clientboundKeepAlive: ["play", "clientbound", "minecraft:keep_alive"],
        clientboundPing: ["play", "clientbound", "minecraft:ping"],
        clientboundLogin: ["play", "clientbound", "minecraft:login"],
        clientboundChunk: ["play", "clientbound", "minecraft:level_chunk_with_light"],
        clientboundSetChunkCacheCenter: ["play", "clientbound", "minecraft:set_chunk_cache_center"],
        clientboundSetChunkCacheRadius: ["play", "clientbound", "minecraft:set_chunk_cache_radius"],
        clientboundSetSimulationDistance: ["play", "clientbound", "minecraft:set_simulation_distance"],
        clientboundStartConfiguration: ["play", "clientbound", "minecraft:start_configuration"],
        serverboundCustomPayload: ["play", "serverbound", "minecraft:custom_payload"],
        serverboundChunkBatchReceived: ["play", "serverbound", "minecraft:chunk_batch_received"],
        serverboundKeepAlive: ["play", "serverbound", "minecraft:keep_alive"],
        serverboundPong: ["play", "serverbound", "minecraft:pong"],
        serverboundPlayerLoaded: ["play", "serverbound", "minecraft:player_loaded"],
        serverboundClientTickEnd: ["play", "serverbound", "minecraft:client_tick_end"],
        serverboundConfigurationAcknowledged: ["play", "serverbound", "minecraft:configuration_acknowledged"],
    },
});

export const PROTOCOL_SCHEMA_VERSION = 1;
export const LOGIN_LAYOUT_VERSION = 1;

export function validateProtocolTable(table, protocolVersion) {
    if (table?.schemaVersion !== PROTOCOL_SCHEMA_VERSION ||
        !Number.isSafeInteger(table.protocolVersion) || table.protocolVersion < 0 || table.protocolVersion > 0x7fffffff ||
        table.protocolVersion !== protocolVersion ||
        typeof table.name !== "string" || table.name.length === 0 ||
        !Number.isSafeInteger(table.loginLayoutVersion) || table.loginLayoutVersion < 1) {
        throw new Error(`Invalid relay protocol table for ${protocolVersion}`);
    }
    if (table.loginLayoutVersion !== LOGIN_LAYOUT_VERSION) return undefined;
    for (const [state, keys] of Object.entries(RELAY_KEYS)) {
        if (table[state] === null || typeof table[state] !== "object" ||
            Array.isArray(table[state]) ||
            Object.keys(table[state]).length !== Object.keys(keys).length) {
            throw new Error(`Invalid ${state} packet table for ${protocolVersion}`);
        }
        for (const key of Object.keys(keys)) {
            if (!Object.hasOwn(table[state], key) || !Number.isInteger(table[state][key]) || table[state][key] < 0) {
                throw new Error(`Invalid packet id ${state}.${key} for ${protocolVersion}`);
            }
            if (table[state][key] > 127) return undefined;
        }
        for (const direction of ["clientbound", "serverbound"]) {
            const ids = Object.keys(keys).filter(key => key.startsWith(direction))
                .map(key => table[state][key]);
            if (new Set(ids).size !== ids.length) {
                throw new Error(`Duplicate ${state}/${direction} packet id for ${protocolVersion}`);
            }
        }
    }
    const serverSource = table.source?.kind === "vanilla-server-reports" &&
        /^[0-9a-f]{40}$/u.test(table.source.serverSha1 ?? "");
    const clientSource = table.source?.kind === "vanilla-client-reports" &&
        /^[0-9a-f]{40}$/u.test(table.source.clientSha1 ?? "") &&
        /^[0-9a-f]{40}$/u.test(table.source.expectedServerSha1 ?? "");
    if ((!serverSource && !clientSource) ||
        !/^[0-9a-f]{64}$/u.test(table.source?.packetsSha256 ?? "")) {
        throw new Error(`Invalid report provenance for ${protocolVersion}`);
    }
    return Object.freeze({
        name: table.name,
        protocolVersion,
        loginLayoutVersion: table.loginLayoutVersion,
        login: Object.freeze({...table.login}),
        configuration: Object.freeze({...table.configuration}),
        play: Object.freeze({...table.play}),
    });
}
