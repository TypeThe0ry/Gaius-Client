package dev.gaius.browser;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSByRef;

/** Browser hot paths for vanilla bit-packed integer storage. */
public final class BrowserBitStorage {
    private BrowserBitStorage() {
    }

    @JSBody(params = {"packed", "index", "valuesPerLong", "bits"}, script = """
            const source = packed && packed.data ? packed.data : packed;
            const bitCount = bits | 0;
            const perLong = valuesPerLong | 0;
            const cell = ((index | 0) / perLong) | 0;
            const offset = ((index | 0) - cell * perLong) * bitCount;
            let words = source && source.__gaiusBitStorageWords;
            if (!words && source && source.buffer && source.BYTES_PER_ELEMENT === 8) {
              let littleEndian = globalThis.__gaiusBitStorageLittleEndian;
              if (littleEndian === undefined) {
                const marker = new Uint32Array(1);
                marker[0] = 0x01020304;
                littleEndian = new Uint8Array(marker.buffer)[0] === 4;
                globalThis.__gaiusBitStorageLittleEndian = littleEndian;
              }
              if (littleEndian) {
                words = new Uint32Array(source.buffer, source.byteOffset, source.length * 2);
                try {
                  Object.defineProperty(source, "__gaiusBitStorageWords", { value: words });
                } catch (ignored) {}
              }
            }
            if (words) {
              const wordIndex = cell * 2;
              const low = words[wordIndex] >>> 0;
              const high = words[wordIndex + 1] >>> 0;
              const numericMask = 0xFFFFFFFF >>> (32 - bitCount);
              let value;
              if (offset < 32) {
                value = low >>> offset;
                if (offset + bitCount > 32) {
                  value |= high << (32 - offset);
                }
              } else {
                value = high >>> (offset - 32);
              }
              return (value & numericMask) | 0;
            }
            const shift = BigInt(offset);
            const bigMask = (BigInt(1) << BigInt(bitCount)) - BigInt(1);
            return Number((source[cell] >> shift) & bigMask) | 0;
            """)
    public static native int get(
            @JSByRef long[] packed, int index, int valuesPerLong, int bits);

    @JSBody(params = {"packed", "index", "value", "valuesPerLong", "bits"}, script = """
            const source = packed && packed.data ? packed.data : packed;
            const bitCount = bits | 0;
            const perLong = valuesPerLong | 0;
            const cell = ((index | 0) / perLong) | 0;
            const offset = ((index | 0) - cell * perLong) * bitCount;
            let words = source && source.__gaiusBitStorageWords;
            if (!words && source && source.buffer && source.BYTES_PER_ELEMENT === 8) {
              let littleEndian = globalThis.__gaiusBitStorageLittleEndian;
              if (littleEndian === undefined) {
                const marker = new Uint32Array(1);
                marker[0] = 0x01020304;
                littleEndian = new Uint8Array(marker.buffer)[0] === 4;
                globalThis.__gaiusBitStorageLittleEndian = littleEndian;
              }
              if (littleEndian) {
                words = new Uint32Array(source.buffer, source.byteOffset, source.length * 2);
                try {
                  Object.defineProperty(source, "__gaiusBitStorageWords", { value: words });
                } catch (ignored) {}
              }
            }
            if (words) {
              const wordIndex = cell * 2;
              const numericMask = 0xFFFFFFFF >>> (32 - bitCount);
              const low = words[wordIndex] >>> 0;
              const high = words[wordIndex + 1] >>> 0;
              let previous;
              if (offset < 32) {
                previous = low >>> offset;
                if (offset + bitCount > 32) {
                  previous |= high << (32 - offset);
                }
              } else {
                previous = high >>> (offset - 32);
              }

              const numericReplacement = (value >>> 0) & numericMask;
              if (offset < 32) {
                const end = offset + bitCount;
                if (end <= 32) {
                  const lowWordMask = (numericMask << offset) >>> 0;
                  words[wordIndex] = ((low & ~lowWordMask)
                      | ((numericReplacement << offset) & lowWordMask)) >>> 0;
                } else {
                  const lowBits = 32 - offset;
                  const lowMask = Math.pow(2, lowBits) - 1;
                  const shiftedLowMask = (lowMask << offset) >>> 0;
                  const highMask = Math.pow(2, bitCount - lowBits) - 1;
                  words[wordIndex] = ((low & ~shiftedLowMask)
                      | ((numericReplacement << offset) & shiftedLowMask)) >>> 0;
                  words[wordIndex + 1] = ((high & ~highMask)
                      | ((numericReplacement >>> lowBits) & highMask)) >>> 0;
                }
              } else {
                const highOffset = offset - 32;
                const highWordMask = (numericMask << highOffset) >>> 0;
                words[wordIndex + 1] = ((high & ~highWordMask)
                    | ((numericReplacement << highOffset) & highWordMask)) >>> 0;
              }
              return (previous & numericMask) | 0;
            }
            const shift = BigInt(offset);
            const bigMask = (BigInt(1) << BigInt(bitCount)) - BigInt(1);
            const previousWord = source[cell];
            const previous = Number((previousWord >> shift) & bigMask) | 0;
            const bigShiftedMask = bigMask << shift;
            const bigReplacement = (BigInt(value | 0) & bigMask) << shift;
            source[cell] = BigInt.asIntN(64, (previousWord & ~bigShiftedMask) | bigReplacement);
            return previous;
            """)
    public static native int getAndSet(
            @JSByRef long[] packed,
            int index,
            int value,
            int valuesPerLong,
            int bits);

    /**
     * Unpacks {@code size} values into {@code output} through the Wasm hot path or, failing
     * that, the JavaScript loop. False means the caller keeps the vanilla loop.
     */
    public static boolean unpack(long[] packed, int[] output, int size, int bits,
            int valuesPerLong) {
        // The JavaScript-fallback counter is telemetry; a stripped build passes a constant false.
        return unpackJs(packed, output, size, bits, valuesPerLong, BrowserBuildFlags.telemetry());
    }

    @JSBody(params = {"packed", "output", "size", "bits", "valuesPerLong", "countFallback"},
            script = """
            try {
              var source = packed && packed.data ? packed.data : packed;
              var target = output && output.data ? output.data : output;
              var valueCount = size | 0;
              var bitCount = bits | 0;
              var perLong = valuesPerLong | 0;
              if (!source || !target || valueCount <= 0 || bitCount <= 0 || bitCount > 32 || perLong <= 0) {
                return false;
              }

              var hotpath = globalThis.__gaiusWasmHotpath;
              if (hotpath && hotpath.ready && hotpath.unpackBitStorage) {
                if (hotpath.unpackBitStorage(source, target, valueCount, bitCount, perLong)) {
                  return true;
                }
              }

              if (countFallback) {
                var counters = globalThis.__gaiusMinecraftCounters || (globalThis.__gaiusMinecraftCounters = {});
                counters.bitStorageJsUnpack = (counters.bitStorageJsUnpack || 0) + 1;
              }
              var bitMask = BigInt.asIntN(64, (BigInt(1) << BigInt(bitCount)) - BigInt(1));
              var out = 0;
              var fullCells = Math.floor(valueCount / perLong);
              for (var cell = 0; cell < fullCells; cell++) {
                var value = BigInt.asIntN(64, source[cell]);
                var base = out;
                for (var i = 0; i < perLong; i++) {
                  target[base + i] = Number(BigInt.asIntN(32, value & bitMask)) | 0;
                  value = BigInt.asIntN(64, BigInt.asUintN(64, value) >> BigInt(bitCount));
                }
                out = base + perLong;
              }
              var remaining = valueCount - out;
              if (remaining > 0) {
                var tail = BigInt.asIntN(64, source[fullCells]);
                for (var j = 0; j < remaining; j++) {
                  target[out + j] = Number(BigInt.asIntN(32, tail & bitMask)) | 0;
                  tail = BigInt.asIntN(64, BigInt.asUintN(64, tail) >> BigInt(bitCount));
                }
              }
              return true;
            } catch (e) {
              try {
                var events = globalThis.__gaiusMinecraftEvents || (globalThis.__gaiusMinecraftEvents = []);
                events.push({ event: "bit-storage-hotpath-fallback", detail: String(e && e.message ? e.message : e), at: Date.now() });
                if (events.length > 500) events.splice(0, events.length - 500);
              } catch (ignored) {}
              return false;
            }
            """)
    private static native boolean unpackJs(
            @JSByRef long[] packed,
            @JSByRef int[] output,
            int size,
            int bits,
            int valuesPerLong,
            boolean countFallback);
}
