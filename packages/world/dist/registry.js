export class BlockStateRegistry {
    #states = [];
    #idsByKey = new Map();
    register(definition) {
        const key = blockStateKey(definition);
        const existing = this.#idsByKey.get(key);
        if (existing !== undefined) {
            return existing;
        }
        const runtimeId = this.#states.length;
        const registered = {
            runtimeId,
            name: definition.name,
            ...(definition.properties === undefined
                ? {}
                : { properties: Object.freeze({ ...definition.properties }) }),
        };
        this.#states.push(Object.freeze(registered));
        this.#idsByKey.set(key, runtimeId);
        return runtimeId;
    }
    id(definition) {
        const id = this.#idsByKey.get(blockStateKey(definition));
        if (id === undefined) {
            throw new Error(`Block state is not registered: ${blockStateKey(definition)}`);
        }
        return id;
    }
    state(runtimeId) {
        const state = this.#states[runtimeId];
        if (state === undefined) {
            throw new RangeError(`Unknown block state runtime id: ${runtimeId}`);
        }
        return state;
    }
    get size() {
        return this.#states.length;
    }
}
export function createBootstrapBlockStates() {
    const registry = new BlockStateRegistry();
    return {
        registry,
        air: registry.register({ name: "minecraft:air" }),
        bedrock: registry.register({ name: "minecraft:bedrock" }),
        dirt: registry.register({ name: "minecraft:dirt" }),
        grassBlock: registry.register({
            name: "minecraft:grass_block",
            properties: { snowy: "false" },
        }),
        stone: registry.register({ name: "minecraft:stone" }),
    };
}
export function blockStateKey(definition) {
    const properties = Object.entries(definition.properties ?? {}).sort(([a], [b]) => a.localeCompare(b));
    return `${definition.name}[${properties
        .map(([name, value]) => `${name}=${value}`)
        .join(",")}]`;
}
