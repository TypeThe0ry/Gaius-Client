import { Chunk, SuperflatGenerator, blockToChunk, blockToLocal, chunkKey, createBootstrapBlockStates, } from "@gaius/world";
const TICKS_PER_SECOND = 20;
const PLAYER_SPEED = 4.317;
const MAX_INTERACTION_DISTANCE_SQUARED = 6 * 6;
export class SingleplayerServer {
    #states = createBootstrapBlockStates();
    #generator = new SuperflatGenerator(this.#states);
    #chunks = new Map();
    #dirtyChunks = new Set();
    #storage;
    #metadata;
    #tick = 0;
    #player = {
        x: 0.5,
        y: this.#generator.spawnY,
        z: 0.5,
        forward: 0,
        strafe: 0,
        acknowledgedInput: 0,
    };
    constructor(storage) {
        this.#storage = storage;
    }
    async handle(command) {
        switch (command.type) {
            case "create-world":
                await this.createWorld(command.worldId, command.name, command.seed);
                return [await this.snapshot()];
            case "load-world":
                await this.loadWorld(command.worldId);
                return [await this.snapshot()];
            case "player-input":
                this.#requireWorld();
                this.#player.forward = clamp(command.forward, -1, 1);
                this.#player.strafe = clamp(command.strafe, -1, 1);
                this.#player.acknowledgedInput = command.sequence;
                return [];
            case "break-block":
                await this.breakBlock(command.x, command.y, command.z);
                return [await this.snapshot()];
            case "place-block":
                await this.placeBlock(command.x, command.y, command.z);
                return [await this.snapshot()];
        }
    }
    async tick() {
        if (this.#metadata === undefined) {
            return undefined;
        }
        this.#tick += 1;
        const magnitude = Math.hypot(this.#player.strafe, this.#player.forward);
        if (magnitude > 0) {
            const distance = PLAYER_SPEED / TICKS_PER_SECOND;
            this.#player.x += (this.#player.strafe / Math.max(1, magnitude)) * distance;
            this.#player.z += (this.#player.forward / Math.max(1, magnitude)) * distance;
            this.#player.y = await this.findStandingY(this.#player.x, this.#player.z);
        }
        if (this.#tick % 10 === 0) {
            await this.flushDirtyChunks();
        }
        return this.#tick % 2 === 0 ? await this.snapshot() : undefined;
    }
    async createWorld(id, name, seed) {
        await this.#storage.deleteWorld(id);
        const now = Date.now();
        this.#metadata = {
            id,
            name: name.trim().length === 0 ? "新的世界" : name.trim(),
            seed,
            createdAt: now,
            updatedAt: now,
            gameVersion: "1.21.11",
            generator: "minecraft:flat",
        };
        this.#chunks.clear();
        this.#dirtyChunks.clear();
        this.#tick = 0;
        this.#player = {
            x: 0.5,
            y: this.#generator.spawnY,
            z: 0.5,
            forward: 0,
            strafe: 0,
            acknowledgedInput: 0,
        };
        await this.#storage.saveMetadata(this.#metadata);
        await this.getChunk(0, 0);
    }
    async loadWorld(id) {
        const metadata = await this.#storage.loadMetadata(id);
        if (metadata === undefined) {
            throw new Error(`世界不存在：${id}`);
        }
        this.#metadata = metadata;
        this.#chunks.clear();
        this.#dirtyChunks.clear();
        this.#tick = 0;
        await this.getChunk(0, 0);
    }
    async flushDirtyChunks() {
        const metadata = this.#requireWorld();
        for (const key of this.#dirtyChunks) {
            const chunk = this.#chunks.get(key);
            if (chunk !== undefined) {
                await this.#storage.saveChunk(metadata.id, chunk.serialize());
            }
        }
        this.#dirtyChunks.clear();
        if (this.#metadata !== undefined) {
            this.#metadata = { ...this.#metadata, updatedAt: Date.now() };
            await this.#storage.saveMetadata(this.#metadata);
        }
    }
    async snapshot(radius = 8) {
        const metadata = this.#requireWorld();
        const centerX = Math.floor(this.#player.x);
        const centerZ = Math.floor(this.#player.z);
        const surface = [];
        for (let z = centerZ - radius; z <= centerZ + radius; z += 1) {
            for (let x = centerX - radius; x <= centerX + radius; x += 1) {
                const chunk = await this.getChunk(blockToChunk(x), blockToChunk(z));
                const y = chunk.highestBlockY(blockToLocal(x), blockToLocal(z));
                if (y !== undefined) {
                    surface.push({
                        x,
                        z,
                        y,
                        state: chunk.getBlock(blockToLocal(x), y, blockToLocal(z)),
                    });
                }
            }
        }
        return {
            type: "snapshot",
            tick: this.#tick,
            worldId: metadata.id,
            worldName: metadata.name,
            player: {
                x: this.#player.x,
                y: this.#player.y,
                z: this.#player.z,
                acknowledgedInput: this.#player.acknowledgedInput,
            },
            surface,
        };
    }
    async breakBlock(x, y, z) {
        this.#requireWorld();
        this.#assertInteractionRange(x, y, z);
        if (y <= -64) {
            return;
        }
        const chunk = await this.getChunk(blockToChunk(x), blockToChunk(z));
        if (chunk.setBlock(blockToLocal(x), y, blockToLocal(z), this.#states.air)) {
            this.#dirtyChunks.add(chunkKey(chunk.x, chunk.z));
        }
    }
    async placeBlock(x, y, z) {
        this.#requireWorld();
        this.#assertInteractionRange(x, y, z);
        const chunk = await this.getChunk(blockToChunk(x), blockToChunk(z));
        if (chunk.getBlock(blockToLocal(x), y, blockToLocal(z)) === this.#states.air &&
            chunk.setBlock(blockToLocal(x), y, blockToLocal(z), this.#states.dirt)) {
            this.#dirtyChunks.add(chunkKey(chunk.x, chunk.z));
        }
    }
    async getChunk(x, z) {
        const key = chunkKey(x, z);
        const cached = this.#chunks.get(key);
        if (cached !== undefined) {
            return cached;
        }
        const metadata = this.#requireWorld();
        const serialized = await this.#storage.loadChunk(metadata.id, x, z);
        const chunk = serialized === undefined
            ? this.#generator.generateChunk(x, z)
            : Chunk.deserialize(serialized, this.#states.air);
        this.#chunks.set(key, chunk);
        if (serialized === undefined) {
            this.#dirtyChunks.add(key);
        }
        return chunk;
    }
    async findStandingY(x, z) {
        const blockX = Math.floor(x);
        const blockZ = Math.floor(z);
        const chunk = await this.getChunk(blockToChunk(blockX), blockToChunk(blockZ));
        return ((chunk.highestBlockY(blockToLocal(blockX), blockToLocal(blockZ)) ??
            this.#generator.spawnY - 1) + 1);
    }
    #assertInteractionRange(x, y, z) {
        const dx = x + 0.5 - this.#player.x;
        const dy = y + 0.5 - (this.#player.y + 1.62);
        const dz = z + 0.5 - this.#player.z;
        if (dx * dx + dy * dy + dz * dz > MAX_INTERACTION_DISTANCE_SQUARED) {
            throw new Error("方块超出交互距离");
        }
    }
    #requireWorld() {
        if (this.#metadata === undefined) {
            throw new Error("尚未创建或加载世界");
        }
        return this.#metadata;
    }
}
function clamp(value, minimum, maximum) {
    return Math.min(maximum, Math.max(minimum, value));
}
