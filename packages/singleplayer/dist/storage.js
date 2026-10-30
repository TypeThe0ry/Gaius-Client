export class MemoryWorldStorage {
    #metadata = new Map();
    #chunks = new Map();
    async loadMetadata(worldId) {
        const metadata = this.#metadata.get(worldId);
        return metadata === undefined ? undefined : cloneValue(metadata);
    }
    async saveMetadata(metadata) {
        this.#metadata.set(metadata.id, cloneValue(metadata));
    }
    async deleteWorld(worldId) {
        this.#metadata.delete(worldId);
        for (const key of this.#chunks.keys()) {
            if (key.startsWith(`${worldId}:`)) {
                this.#chunks.delete(key);
            }
        }
    }
    async loadChunk(worldId, x, z) {
        const chunk = this.#chunks.get(`${worldId}:${x},${z}`);
        return chunk === undefined ? undefined : cloneValue(chunk);
    }
    async saveChunk(worldId, chunk) {
        this.#chunks.set(`${worldId}:${chunk.x},${chunk.z}`, cloneValue(chunk));
    }
}
function cloneValue(value) {
    return JSON.parse(JSON.stringify(value));
}
