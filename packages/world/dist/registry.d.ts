export interface BlockStateDefinition {
    readonly name: string;
    readonly properties?: Readonly<Record<string, string>>;
}
export interface RegisteredBlockState extends BlockStateDefinition {
    readonly runtimeId: number;
}
export declare class BlockStateRegistry {
    #private;
    register(definition: BlockStateDefinition): number;
    id(definition: BlockStateDefinition): number;
    state(runtimeId: number): RegisteredBlockState;
    get size(): number;
}
export interface BootstrapBlockStates {
    readonly registry: BlockStateRegistry;
    readonly air: number;
    readonly bedrock: number;
    readonly dirt: number;
    readonly grassBlock: number;
    readonly stone: number;
}
export declare function createBootstrapBlockStates(): BootstrapBlockStates;
export declare function blockStateKey(definition: BlockStateDefinition): string;
