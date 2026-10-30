export interface CreateWorldCommand {
    readonly type: "create-world";
    readonly worldId: string;
    readonly name: string;
    readonly seed: string;
}
export interface LoadWorldCommand {
    readonly type: "load-world";
    readonly worldId: string;
}
export interface PlayerInputCommand {
    readonly type: "player-input";
    readonly sequence: number;
    readonly forward: number;
    readonly strafe: number;
}
export interface BreakBlockCommand {
    readonly type: "break-block";
    readonly x: number;
    readonly y: number;
    readonly z: number;
}
export interface PlaceBlockCommand {
    readonly type: "place-block";
    readonly x: number;
    readonly y: number;
    readonly z: number;
}
export type SingleplayerCommand = CreateWorldCommand | LoadWorldCommand | PlayerInputCommand | BreakBlockCommand | PlaceBlockCommand;
export interface SurfaceCell {
    readonly x: number;
    readonly z: number;
    readonly y: number;
    readonly state: number;
}
export interface WorldSnapshot {
    readonly type: "snapshot";
    readonly tick: number;
    readonly worldId: string;
    readonly worldName: string;
    readonly player: {
        readonly x: number;
        readonly y: number;
        readonly z: number;
        readonly acknowledgedInput: number;
    };
    readonly surface: readonly SurfaceCell[];
}
export interface ServerReady {
    readonly type: "ready";
}
export interface ServerError {
    readonly type: "error";
    readonly message: string;
}
export type SingleplayerEvent = ServerReady | ServerError | WorldSnapshot;
