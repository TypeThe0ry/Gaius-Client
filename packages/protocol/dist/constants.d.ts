export interface MinecraftProtocolProfile {
    readonly name: string;
    readonly protocolVersion: number;
    readonly worldVersion: number;
    readonly resourcePackVersion: string;
    readonly dataPackVersion: string;
}
export declare const MINECRAFT_1_21_11: {
    readonly name: "1.21.11";
    readonly protocolVersion: 774;
    readonly worldVersion: 4671;
    readonly resourcePackVersion: "75.0";
    readonly dataPackVersion: "94.1";
};
export declare const MINECRAFT_26_2: {
    readonly name: "26.2";
    readonly protocolVersion: 776;
    readonly worldVersion: 4903;
    readonly resourcePackVersion: "88.0";
    readonly dataPackVersion: "107.1";
};
export declare const MINECRAFT_26_3: {
    readonly name: "26.3";
    readonly protocolVersion: 777;
    readonly worldVersion: 5023;
    readonly resourcePackVersion: "97.1";
    readonly dataPackVersion: "121.0";
};
export declare const MINECRAFT_PROTOCOLS: readonly MinecraftProtocolProfile[];
export declare function resolveMinecraftProtocol(value?: number | string | MinecraftProtocolProfile | null): MinecraftProtocolProfile;
export declare const DEFAULT_MINECRAFT_PORT = 25565;
