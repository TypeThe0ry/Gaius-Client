package dev.gaius.tools.runtime;

import java.lang.reflect.Field;
import java.util.ArrayList;
import org.teavm.backend.wasm.generate.classes.WasmGCClassInfoProvider;
import org.teavm.backend.wasm.generate.methods.WasmGCGenerationUtil;
import org.teavm.backend.wasm.generate.strings.WasmGCStringProvider;
import org.teavm.backend.wasm.model.WasmArray;
import org.teavm.backend.wasm.model.WasmFunction;
import org.teavm.backend.wasm.model.WasmGlobal;
import org.teavm.backend.wasm.model.instruction.WasmInstructionBuilder;
import org.teavm.model.ValueType;

/** Emits the resource descriptor table in V8-safe chunks for the patched TeaVM intrinsic. */
public final class GaiusWasmResourceChunks {
    private static final int CHUNK_SIZE = 4096;
    private static final Field NAME = field("name");
    private static final Field ADDRESS = field("address");
    private static final Field END = field("end");

    private GaiusWasmResourceChunks() {
    }

    public static void emit(WasmGCClassInfoProvider classes, WasmGCStringProvider strings,
            WasmGlobal baseGlobal, ArrayList<?> descriptors, WasmFunction create,
            WasmArray outerArray, WasmInstructionBuilder builder) {
        if (descriptors.size() > CHUNK_SIZE * CHUNK_SIZE) {
            throw new IllegalStateException("Resource table exceeds " + (CHUNK_SIZE * CHUNK_SIZE) + " entries");
        }
        // Java arrays are wrapper structs around wasm arrays. Use TeaVM's allocator for
        // each chunk so the outer table contains Java Resource[] objects, not raw wasm arrays.
        ValueType resource = ValueType.object(
                "org.teavm.backend.wasm.runtime.WasmGCResources$Resource");
        int chunkCount = 0;
        for (int start = 0; start < descriptors.size(); start += CHUNK_SIZE) {
            int from = start;
            int end = Math.min(start + CHUNK_SIZE, descriptors.size());
            WasmGCGenerationUtil.allocateArray(classes, resource, builder, (array, chunk) -> {
                for (int i = from; i < end; i++) {
                    Object descriptor = descriptors.get(i);
                    try {
                        String name = (String) NAME.get(descriptor);
                        int address = ADDRESS.getInt(descriptor);
                        int limit = END.getInt(descriptor);
                        chunk.getGlobal(strings.getStringConstant(name).global);
                        chunk.getGlobal(baseGlobal).i32Const(address)
                                .intBinary(org.teavm.backend.wasm.model.instruction.WasmIntType.INT32,
                                        org.teavm.backend.wasm.model.instruction.WasmIntBinaryOperation.ADD);
                        chunk.i32Const(limit - address).call(create);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException("cannot read TeaVM resource descriptor", e);
                    }
                }
                chunk.arrayNewFixed(array, end - from);
            });
            chunkCount++;
        }
        builder.arrayNewFixed(outerArray, chunkCount);
    }

    private static Field field(String name) {
        try {
            Field field = Class.forName(
                    "org.teavm.backend.wasm.intrinsics.WasmGCResourcesIntrinsic$ResourceDescriptor")
                    .getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
