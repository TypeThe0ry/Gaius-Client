package dev.gaius.shadercorpus;

import dev.gaius.shadercorpus.nat.NativeSpvc;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.BrowserSpvcToolchain;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/**
 * A {@link BrowserSpvcToolchain} on the native LWJGL SPIRV-Cross (through the
 * relocated NativeSpvc).  Every call is recorded with symbolic handles
 * (c = context, i/k/o/r/t + number = parsed IR, compiler, options, resources,
 * type) as one line per context in tape-spvc.jsonl, which the Node smoke
 * replays against the WebAssembly build.
 */
final class NativeSpvcToolchain implements BrowserSpvcToolchain {
    private final Corpus corpus;
    private final Map<Long, Session> contexts = new HashMap<>();
    private final Map<Long, Handle> handles = new HashMap<>();
    private int serial;
    int sessions;
    int calls;
    int compiles;
    double compileMs;

    private static final class Session {
        final int serial;
        final String phase;
        final List<String> calls = new ArrayList<>();
        final Map<Character, Integer> counters = new HashMap<>();

        Session(int serial, String phase) {
            this.serial = serial;
            this.phase = phase;
        }
    }

    private record Handle(Session session, String symbol) {
    }

    /** Label of the current harness phase, stored with each session. */
    String phase = "";

    NativeSpvcToolchain(Corpus corpus) {
        this.corpus = corpus;
    }

    private Session session(long handle) {
        Handle known = handles.get(handle);
        if (known == null) {
            throw new IllegalStateException("unknown native spvc handle 0x" + Long.toHexString(handle));
        }
        return known.session;
    }

    private String symbol(long handle) {
        Handle known = handles.get(handle);
        return known == null ? "?" + Long.toHexString(handle) : known.symbol;
    }

    private String bind(Session session, char kind, long handle) {
        if (handle == 0L) {
            return null;
        }
        Handle known = handles.get(handle);
        if (known != null && known.session == session) {
            return known.symbol;
        }
        int index = session.counters.merge(kind, 1, Integer::sum) - 1;
        String symbol = kind == 'c' ? "c" : kind + Integer.toString(index);
        handles.put(handle, new Handle(session, symbol));
        return symbol;
    }

    private void record(Session session, String call) {
        session.calls.add(call);
        calls++;
    }

    private static String q(String s) {
        return Corpus.q(s);
    }

    private static String sym(String symbol) {
        return symbol == null ? "null" : q(symbol);
    }

    @Override
    public int generation() {
        return 0;
    }

    @Override
    public int contextCreate(int generation, long[] context) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer out = stack.callocPointer(1);
            int result = NativeSpvc.spvc_context_create(out);
            context[0] = out.get(0);
            Session session = new Session(++serial, phase);
            sessions++;
            contexts.put(context[0], session);
            String symbol = bind(session, 'c', context[0]);
            record(session, "[\"contextCreate\"," + result + "," + sym(symbol) + "]");
            return result;
        }
    }

    @Override
    public void contextDestroy(int generation, long context) {
        Session session = session(context);
        NativeSpvc.spvc_context_destroy(context);
        record(session, "[\"contextDestroy\",\"c\"]");
        flush(session);
        contexts.remove(context);
        handles.values().removeIf(handle -> handle.session == session);
    }

    private void flush(Session session) {
        corpus.line("tape-spvc", "{\"session\":" + session.serial + ",\"phase\":" + q(session.phase)
                + ",\"calls\":[" + String.join(",", session.calls) + "]}");
    }

    /** Writes contexts the harness never destroyed (Minecraft keeps reflection contexts per module). */
    void flushOpen() {
        for (Session session : contexts.values()) {
            session.calls.add("[\"(open)\"]");
            flush(session);
        }
    }

    @Override
    public String contextLastError(int generation, long context) {
        String error = NativeSpvc.spvc_context_get_last_error_string(context);
        record(session(context), "[\"contextLastError\",\"c\"," + q(error) + "]");
        return error;
    }

    @Override
    public int parseSpirv(int generation, long context, int[] words, long[] parsedIr) {
        Session session = session(context);
        ByteBuffer bytes = ByteBuffer.allocate(words.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asIntBuffer().put(words);
        String hash = corpus.blob("tape-spirv", ".spv", bytes.array());
        IntBuffer spirv = MemoryUtil.memAllocInt(Math.max(1, words.length));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            spirv.put(words).flip();
            PointerBuffer out = stack.callocPointer(1);
            int result = NativeSpvc.spvc_context_parse_spirv(context, spirv, words.length, out);
            parsedIr[0] = out.get(0);
            String symbol = result == 0 ? bind(session, 'i', parsedIr[0]) : null;
            record(session, "[\"parseSpirv\"," + q(hash) + "," + words.length + "," + result + "," + sym(symbol)
                    + "]");
            return result;
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    @Override
    public int createCompiler(int generation, long context, int backend, long parsedIr, int captureMode,
            long[] compiler) {
        Session session = session(context);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer out = stack.callocPointer(1);
            int result = NativeSpvc.spvc_context_create_compiler(context, backend, parsedIr, captureMode, out);
            compiler[0] = out.get(0);
            String symbol = result == 0 ? bind(session, 'k', compiler[0]) : null;
            record(session, "[\"createCompiler\"," + backend + "," + sym(symbol(parsedIr)) + "," + captureMode + ","
                    + result + "," + sym(symbol) + "]");
            return result;
        }
    }

    @Override
    public int createCompilerOptions(int generation, long compiler, long[] options) {
        Session session = session(compiler);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer out = stack.callocPointer(1);
            int result = NativeSpvc.spvc_compiler_create_compiler_options(compiler, out);
            options[0] = out.get(0);
            String symbol = result == 0 ? bind(session, 'o', options[0]) : null;
            record(session, "[\"createCompilerOptions\"," + sym(symbol(compiler)) + "," + result + ","
                    + sym(symbol) + "]");
            return result;
        }
    }

    @Override
    public int optionsSetBool(int generation, long options, int option, boolean value) {
        int result = NativeSpvc.spvc_compiler_options_set_bool(options, option, value);
        record(session(options), "[\"optionsSetBool\"," + sym(symbol(options)) + "," + option + "," + value + ","
                + result + "]");
        return result;
    }

    @Override
    public int optionsSetUint(int generation, long options, int option, int value) {
        int result = NativeSpvc.spvc_compiler_options_set_uint(options, option, value);
        record(session(options), "[\"optionsSetUint\"," + sym(symbol(options)) + "," + option + ","
                + Integer.toUnsignedString(value) + "," + result + "]");
        return result;
    }

    @Override
    public int installCompilerOptions(int generation, long compiler, long options) {
        int result = NativeSpvc.spvc_compiler_install_compiler_options(compiler, options);
        record(session(compiler), "[\"installCompilerOptions\"," + sym(symbol(compiler)) + "," + sym(symbol(options))
                + "," + result + "]");
        return result;
    }

    @Override
    public int createShaderResources(int generation, long compiler, long[] resources) {
        Session session = session(compiler);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer out = stack.callocPointer(1);
            int result = NativeSpvc.spvc_compiler_create_shader_resources(compiler, out);
            resources[0] = out.get(0);
            String symbol = result == 0 ? bind(session, 'r', resources[0]) : null;
            record(session, "[\"createShaderResources\"," + sym(symbol(compiler)) + "," + result + ","
                    + sym(symbol) + "]");
            return result;
        }
    }

    @Override
    public int resourceList(int generation, long resources, int type, ResourceList list) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.callocPointer(1);
            PointerBuffer count = stack.callocPointer(1);
            int result = NativeSpvc.spvc_resources_get_resource_list_for_type(resources, type, pointer, count);
            StringBuilder entries = new StringBuilder();
            if (result == 0) {
                int size = (int) count.get(0);
                list.allocate(size);
                SpvcReflectedResource.Buffer buffer = SpvcReflectedResource.create(pointer.get(0), size);
                for (int i = 0; i < size; i++) {
                    SpvcReflectedResource resource = buffer.get(i);
                    list.ids[i] = resource.id();
                    list.baseTypeIds[i] = resource.base_type_id();
                    list.typeIds[i] = resource.type_id();
                    list.names[i] = resource.nameString();
                    entries.append(i == 0 ? "" : ",").append('[').append(list.ids[i]).append(',')
                            .append(list.baseTypeIds[i]).append(',').append(list.typeIds[i]).append(',')
                            .append(q(list.names[i])).append(']');
                }
            }
            record(session(resources), "[\"resourceList\"," + sym(symbol(resources)) + "," + type + "," + result
                    + ",[" + entries + "]]");
            return result;
        }
    }

    @Override
    public int getDecoration(int generation, long compiler, int id, int decoration) {
        int result = NativeSpvc.spvc_compiler_get_decoration(compiler, id, decoration);
        record(session(compiler), "[\"getDecoration\"," + sym(symbol(compiler)) + "," + id + "," + decoration + ","
                + result + "]");
        return result;
    }

    @Override
    public boolean getBinaryOffsetForDecoration(int generation, long compiler, int id, int decoration, int[] offset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer out = stack.callocInt(1);
            boolean found = NativeSpvc.spvc_compiler_get_binary_offset_for_decoration(compiler, id, decoration, out);
            offset[0] = found ? out.get(0) : 0;
            record(session(compiler), "[\"getBinaryOffsetForDecoration\"," + sym(symbol(compiler)) + "," + id + ","
                    + decoration + "," + found + "," + offset[0] + "]");
            return found;
        }
    }

    @Override
    public void setName(int generation, long compiler, int id, byte[] name) {
        String text = new String(name, StandardCharsets.UTF_8);
        NativeSpvc.spvc_compiler_set_name(compiler, id, text);
        record(session(compiler), "[\"setName\"," + sym(symbol(compiler)) + "," + id + "," + q(text) + "]");
    }

    @Override
    public String getName(int generation, long compiler, int id) {
        String name = NativeSpvc.spvc_compiler_get_name(compiler, id);
        record(session(compiler), "[\"getName\"," + sym(symbol(compiler)) + "," + id + "," + q(name) + "]");
        return name;
    }

    @Override
    public int setEntryPoint(int generation, long compiler, byte[] name, int executionModel) {
        String text = new String(name, StandardCharsets.UTF_8);
        int result = NativeSpvc.spvc_compiler_set_entry_point(compiler, text, executionModel);
        record(session(compiler), "[\"setEntryPoint\"," + sym(symbol(compiler)) + "," + q(text) + ","
                + executionModel + "," + result + "]");
        return result;
    }

    @Override
    public int compile(int generation, long compiler, String[] source) {
        long started = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer out = stack.callocPointer(1);
            int result = NativeSpvc.spvc_compiler_compile(compiler, out);
            source[0] = result == 0 ? MemoryUtil.memUTF8(out.get(0)) : null;
            compileMs += (System.nanoTime() - started) / 1e6;
            compiles++;
            String hash = source[0] == null ? null
                    : corpus.blob("tape-glsl", ".glsl", source[0].getBytes(StandardCharsets.UTF_8));
            record(session(compiler), "[\"compile\"," + sym(symbol(compiler)) + "," + result + "," + sym(hash) + "]");
            return result;
        }
    }

    @Override
    public int getDeclaredStructSize(int generation, long compiler, long type, long[] size) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer out = stack.callocPointer(1);
            int result = NativeSpvc.spvc_compiler_get_declared_struct_size(compiler, type, out);
            size[0] = out.get(0);
            record(session(compiler), "[\"getDeclaredStructSize\"," + sym(symbol(compiler)) + "," + sym(symbol(type))
                    + "," + result + "," + size[0] + "]");
            return result;
        }
    }

    @Override
    public long getTypeHandle(int generation, long compiler, int typeId) {
        Session session = session(compiler);
        long type = NativeSpvc.spvc_compiler_get_type_handle(compiler, typeId);
        String symbol = bind(session, 't', type);
        record(session, "[\"getTypeHandle\"," + sym(symbol(compiler)) + "," + typeId + "," + sym(symbol) + "]");
        return type;
    }

    private int typeQuery(String name, long type, int result) {
        record(session(type), "[\"" + name + "\"," + sym(symbol(type)) + "," + result + "]");
        return result;
    }

    @Override
    public int typeGetBasetype(int generation, long type) {
        return typeQuery("typeGetBasetype", type, NativeSpvc.spvc_type_get_basetype(type));
    }

    @Override
    public int typeGetImageDimension(int generation, long type) {
        return typeQuery("typeGetImageDimension", type, NativeSpvc.spvc_type_get_image_dimension(type));
    }

    @Override
    public int typeGetVectorSize(int generation, long type) {
        return typeQuery("typeGetVectorSize", type, NativeSpvc.spvc_type_get_vector_size(type));
    }

    @Override
    public int typeGetNumArrayDimensions(int generation, long type) {
        return typeQuery("typeGetNumArrayDimensions", type, NativeSpvc.spvc_type_get_num_array_dimensions(type));
    }

    @Override
    public int typeGetArrayDimension(int generation, long type, int dimension) {
        int result = NativeSpvc.spvc_type_get_array_dimension(type, dimension);
        record(session(type), "[\"typeGetArrayDimension\"," + sym(symbol(type)) + "," + dimension + "," + result
                + "]");
        return result;
    }
}
