import { concatBytes } from "./binary.js";
import { decodeVarInt, encodeVarInt } from "./varint.js";
export function encodePacket(id, payload = new Uint8Array()) {
    const body = concatBytes(encodeVarInt(id), payload);
    return concatBytes(encodeVarInt(body.byteLength), body);
}
export class PacketStream {
    #maximumPacketBytes;
    #buffer = new Uint8Array();
    constructor(maximumPacketBytes = 2 * 1024 * 1024) {
        this.#maximumPacketBytes = maximumPacketBytes;
    }
    push(chunk) {
        if (chunk.byteLength === 0) {
            return [];
        }
        this.#buffer = concatBytes(this.#buffer, chunk);
        const packets = [];
        let consumed = 0;
        while (consumed < this.#buffer.byteLength) {
            const packetLength = decodeVarInt(this.#buffer, consumed);
            if (packetLength === undefined) {
                break;
            }
            if (packetLength.value < 0 ||
                packetLength.value > this.#maximumPacketBytes) {
                throw new RangeError(`Invalid packet length: ${packetLength.value}`);
            }
            const packetStart = consumed + packetLength.bytesRead;
            const packetEnd = packetStart + packetLength.value;
            if (packetEnd > this.#buffer.byteLength) {
                break;
            }
            const packetId = decodeVarInt(this.#buffer, packetStart);
            if (packetId === undefined) {
                throw new Error("Complete packet did not contain a packet id");
            }
            packets.push({
                id: packetId.value,
                payload: this.#buffer.slice(packetStart + packetId.bytesRead, packetEnd),
            });
            consumed = packetEnd;
        }
        if (consumed > 0) {
            this.#buffer = this.#buffer.slice(consumed);
        }
        return packets;
    }
    get bufferedBytes() {
        return this.#buffer.byteLength;
    }
}
