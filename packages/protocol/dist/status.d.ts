export interface StatusDescriptionObject {
    readonly text?: string;
    readonly extra?: readonly StatusDescriptionObject[];
    readonly [key: string]: unknown;
}
export interface ServerStatus {
    readonly version?: {
        readonly name?: string;
        readonly protocol?: number;
    };
    readonly players?: {
        readonly max?: number;
        readonly online?: number;
        readonly sample?: readonly {
            readonly id?: string;
            readonly name?: string;
        }[];
    };
    readonly description?: string | StatusDescriptionObject;
    readonly favicon?: string;
    readonly enforcesSecureChat?: boolean;
    readonly [key: string]: unknown;
}
export declare function createStatusHandshake(host: string, port?: number, protocol?: number | string | import("./constants.js").MinecraftProtocolProfile | null): Uint8Array;
export declare function createStatusRequest(): Uint8Array;
export declare function createPingRequest(timestamp: bigint): Uint8Array;
export declare function parseStatusResponse(payload: Uint8Array): ServerStatus;
export declare function parsePingResponse(payload: Uint8Array): bigint;
export declare function flattenStatusDescription(description: ServerStatus["description"]): string;
