package org.lwjgl.util.spvc;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Browser implementation of the org.lwjgl.util.spvc.Spvc entry points (PLAN
 * D5).  LwjglSpvcBrowserPatcher replaces the body of every Spvc method whose
 * name and descriptor also exist here with a call to this class; the other
 * Spvc methods throw UnsupportedOperationException.
 *
 * <p>Every spvc_context_create opens a session.  The handles given to Java
 * encode (kind, session, index) and map to the backend's handles, so a stale
 * or foreign handle is rejected instead of reaching the backend, and every
 * call passes the backend generation of its session.  Memory that SPIRV-Cross
 * would own through the context - reflected resource arrays and their names,
 * compiled source text, names returned by pointer - is allocated with
 * MemoryUtil and released by spvc_context_destroy.
 *
 * <p>spvc_compiler_compile of a GLSL compiler post-processes the GLSL 330 text
 * into WebGL2 ESSL 3.00 ({@link BrowserEsslPostProcessor}).  If compilation
 * fails, the source pointer receives a one-line "#error" text that carries the
 * spvc error, so the GL shader log names the cause.
 *
 * <p>Not thread-safe: the browser runs every TeaVM thread on one JavaScript
 * thread and none of these methods can suspend.
 */
public final class BrowserSpvc {
    static final int SPVC_SUCCESS = 0;
    static final int SPVC_ERROR_INVALID_ARGUMENT = -4;
    static final int SPVC_ERROR_OUT_OF_MEMORY = -3;
    private static final int BACKEND_GLSL = 1;
    private static final int EXECUTION_MODEL_FRAGMENT = 4;

    private static final int TAG_CONTEXT = 1;
    private static final int TAG_IR = 2;
    private static final int TAG_COMPILER = 3;
    private static final int TAG_OPTIONS = 4;
    private static final int TAG_RESOURCES = 5;
    private static final int TAG_TYPE = 6;
    private static final int MAX_SESSION = 0xffffff;

    private static final Map<Integer, Session> SESSIONS = new HashMap<>();
    private static int nextSession = 1;
    private static BrowserSpvcToolchain toolchain;
    private static boolean esslPostProcessing = true;

    private BrowserSpvc() {
    }

    /** Installs the backend (tests); the browser default is WebAssembly. */
    public static void setToolchain(BrowserSpvcToolchain value) {
        toolchain = value;
    }

    /** Whether spvc_compiler_compile turns GLSL 330 into ESSL 3.00 (default true; tests turn it off). */
    public static void setEsslPostProcessing(boolean enabled) {
        esslPostProcessing = enabled;
    }

    /** Number of open spvc contexts (leak diagnostics). */
    public static int liveContexts() {
        return SESSIONS.size();
    }

    static BrowserSpvcToolchain toolchain() {
        if (toolchain == null) {
            toolchain = new BrowserSpvcWasm();
        }
        return toolchain;
    }

    private static final class Session {
        final int id;
        final int generation;
        long[] backend = new long[16];
        byte[] tags = new byte[16];
        /** Compilers: the SPVC backend; others unused. */
        int[] kinds = new int[16];
        /** Compilers: the execution model of the entry point, -1 until set. */
        int[] models = new int[16];
        int size;
        final Map<Long, Integer> types = new HashMap<>();
        final ArrayList<ByteBuffer> allocations = new ArrayList<>();

        Session(int id, int generation) {
            this.id = id;
            this.generation = generation;
        }

        long add(int tag, long backendHandle, int kind) {
            if (size == backend.length) {
                int capacity = size * 2;
                backend = java.util.Arrays.copyOf(backend, capacity);
                tags = java.util.Arrays.copyOf(tags, capacity);
                kinds = java.util.Arrays.copyOf(kinds, capacity);
                models = java.util.Arrays.copyOf(models, capacity);
            }
            int index = size++;
            backend[index] = backendHandle;
            tags[index] = (byte) tag;
            kinds[index] = kind;
            models[index] = -1;
            return ((long) tag << 56) | ((long) id << 32) | index;
        }

        long keep(ByteBuffer buffer) {
            allocations.add(buffer);
            return MemoryUtil.memAddress(buffer);
        }

        void free() {
            for (ByteBuffer buffer : allocations) {
                MemoryUtil.memFree(buffer);
            }
            allocations.clear();
        }
    }

    private static Session sessionOf(long handle, int tag) {
        if (handle == 0L || (int) (handle >>> 56) != tag) {
            return null;
        }
        Session session = SESSIONS.get((int) ((handle >>> 32) & MAX_SESSION));
        int index = (int) handle;
        if (session == null || index < 0 || index >= session.size || session.tags[index] != tag) {
            return null;
        }
        return session;
    }

    private static int indexOf(long handle) {
        return (int) handle;
    }

    private static Session openSession(int generation) {
        for (int attempt = 0; attempt <= MAX_SESSION; attempt++) {
            int id = nextSession;
            nextSession = nextSession == MAX_SESSION ? 1 : nextSession + 1;
            if (!SESSIONS.containsKey(id)) {
                Session session = new Session(id, generation);
                SESSIONS.put(id, session);
                return session;
            }
        }
        throw new IllegalStateException("Too many open spvc contexts");
    }

    // ---- context ------------------------------------------------------------------------

    public static int spvc_context_create(PointerBuffer context) {
        return nspvc_context_create(MemoryUtil.memAddress(context));
    }

    public static int nspvc_context_create(long context) {
        BrowserSpvcToolchain backend = toolchain();
        int generation = backend.generation();
        if (generation < 0) {
            return SPVC_ERROR_OUT_OF_MEMORY;
        }
        long[] out = new long[1];
        int result = backend.contextCreate(generation, out);
        if (result == SPVC_SUCCESS) {
            Session session = openSession(generation);
            MemoryUtil.memPutAddress(context, session.add(TAG_CONTEXT, out[0], 0));
        }
        return result;
    }

    public static void spvc_context_destroy(long context) {
        Session session = sessionOf(context, TAG_CONTEXT);
        if (session == null) {
            return;
        }
        SESSIONS.remove(session.id);
        try {
            toolchain().contextDestroy(session.generation, session.backend[indexOf(context)]);
        } finally {
            session.free();
        }
    }

    public static String spvc_context_get_last_error_string(long context) {
        Session session = sessionOf(context, TAG_CONTEXT);
        return session == null ? null
                : toolchain().contextLastError(session.generation, session.backend[indexOf(context)]);
    }

    public static int spvc_context_parse_spirv(long context, IntBuffer spirv, long wordCount, PointerBuffer parsedIr) {
        // Minecraft passes SpvModule.spv().asIntBuffer(): the browser's BrowserMemory
        // has no address for such a view, so read the words through the buffer.
        if (wordCount < 0L || wordCount > spirv.remaining()) {
            return SPVC_ERROR_INVALID_ARGUMENT;
        }
        int[] words = new int[(int) wordCount];
        int position = spirv.position();
        for (int i = 0; i < words.length; i++) {
            words[i] = spirv.get(position + i);
        }
        return parseSpirv(context, words, MemoryUtil.memAddress(parsedIr));
    }

    public static int nspvc_context_parse_spirv(long context, long spirv, long wordCount, long parsedIr) {
        if (spirv == 0L || wordCount < 0L || wordCount > Integer.MAX_VALUE / 4) {
            return SPVC_ERROR_INVALID_ARGUMENT;
        }
        int[] words = new int[(int) wordCount];
        for (int i = 0; i < words.length; i++) {
            words[i] = MemoryUtil.memGetInt(spirv + 4L * i);
        }
        return parseSpirv(context, words, parsedIr);
    }

    private static int parseSpirv(long context, int[] words, long parsedIr) {
        Session session = sessionOf(context, TAG_CONTEXT);
        if (session == null) {
            return SPVC_ERROR_INVALID_ARGUMENT;
        }
        long[] out = new long[1];
        int result = toolchain().parseSpirv(session.generation, session.backend[indexOf(context)], words, out);
        if (result == SPVC_SUCCESS) {
            MemoryUtil.memPutAddress(parsedIr, session.add(TAG_IR, out[0], 0));
        }
        return result;
    }

    public static int spvc_context_create_compiler(
            long context, int backend, long parsedIr, int mode, PointerBuffer compiler) {
        return nspvc_context_create_compiler(context, backend, parsedIr, mode, MemoryUtil.memAddress(compiler));
    }

    public static int nspvc_context_create_compiler(long context, int backend, long parsedIr, int mode, long compiler) {
        Session session = sessionOf(context, TAG_CONTEXT);
        if (session == null || sessionOf(parsedIr, TAG_IR) != session) {
            return SPVC_ERROR_INVALID_ARGUMENT;
        }
        long[] out = new long[1];
        int result = toolchain().createCompiler(session.generation, session.backend[indexOf(context)], backend,
                session.backend[indexOf(parsedIr)], mode, out);
        if (result == SPVC_SUCCESS) {
            MemoryUtil.memPutAddress(compiler, session.add(TAG_COMPILER, out[0], backend));
        }
        return result;
    }

    // ---- compiler options ---------------------------------------------------------------------

    public static int spvc_compiler_create_compiler_options(long compiler, PointerBuffer options) {
        return nspvc_compiler_create_compiler_options(compiler, MemoryUtil.memAddress(options));
    }

    public static int nspvc_compiler_create_compiler_options(long compiler, long options) {
        Session session = sessionOf(compiler, TAG_COMPILER);
        if (session == null) {
            return SPVC_ERROR_INVALID_ARGUMENT;
        }
        long[] out = new long[1];
        int result = toolchain().createCompilerOptions(session.generation, session.backend[indexOf(compiler)], out);
        if (result == SPVC_SUCCESS) {
            MemoryUtil.memPutAddress(options, session.add(TAG_OPTIONS, out[0], 0));
        }
        return result;
    }

    public static int spvc_compiler_options_set_bool(long options, int option, boolean value) {
        Session session = sessionOf(options, TAG_OPTIONS);
        return session == null ? SPVC_ERROR_INVALID_ARGUMENT
                : toolchain().optionsSetBool(session.generation, session.backend[indexOf(options)], option, value);
    }

    public static int spvc_compiler_options_set_uint(long options, int option, int value) {
        Session session = sessionOf(options, TAG_OPTIONS);
        return session == null ? SPVC_ERROR_INVALID_ARGUMENT
                : toolchain().optionsSetUint(session.generation, session.backend[indexOf(options)], option, value);
    }

    public static int spvc_compiler_install_compiler_options(long compiler, long options) {
        Session session = sessionOf(compiler, TAG_COMPILER);
        if (session == null || sessionOf(options, TAG_OPTIONS) != session) {
            return SPVC_ERROR_INVALID_ARGUMENT;
        }
        return toolchain().installCompilerOptions(session.generation, session.backend[indexOf(compiler)],
                session.backend[indexOf(options)]);
    }

    // ---- reflection -------------------------------------------------------------------------------

    public static int spvc_compiler_create_shader_resources(long compiler, PointerBuffer resources) {
        return nspvc_compiler_create_shader_resources(compiler, MemoryUtil.memAddress(resources));
    }

    public static int nspvc_compiler_create_shader_resources(long compiler, long resources) {
        Session session = sessionOf(compiler, TAG_COMPILER);
        if (session == null) {
            return SPVC_ERROR_INVALID_ARGUMENT;
        }
        long[] out = new long[1];
        int result = toolchain().createShaderResources(session.generation, session.backend[indexOf(compiler)], out);
        if (result == SPVC_SUCCESS) {
            MemoryUtil.memPutAddress(resources, session.add(TAG_RESOURCES, out[0], 0));
        }
        return result;
    }

    public static int spvc_resources_get_resource_list_for_type(
            long resources, int type, PointerBuffer resourceList, PointerBuffer resourceSize) {
        return nspvc_resources_get_resource_list_for_type(resources, type, MemoryUtil.memAddress(resourceList),
                MemoryUtil.memAddress(resourceSize));
    }

    public static int nspvc_resources_get_resource_list_for_type(
            long resources, int type, long resourceList, long resourceSize) {
        Session session = sessionOf(resources, TAG_RESOURCES);
        if (session == null) {
            return SPVC_ERROR_INVALID_ARGUMENT;
        }
        BrowserSpvcToolchain.ResourceList list = new BrowserSpvcToolchain.ResourceList();
        int result = toolchain().resourceList(session.generation, session.backend[indexOf(resources)], type, list);
        if (result != SPVC_SUCCESS) {
            return result;
        }
        int stride = SpvcReflectedResource.SIZEOF;
        long base = session.keep(MemoryUtil.memCalloc(Math.max(1, list.count) * stride));
        for (int i = 0; i < list.count; i++) {
            long entry = base + (long) i * stride;
            MemoryUtil.memPutInt(entry + SpvcReflectedResource.ID, list.ids[i]);
            MemoryUtil.memPutInt(entry + SpvcReflectedResource.BASE_TYPE_ID, list.baseTypeIds[i]);
            MemoryUtil.memPutInt(entry + SpvcReflectedResource.TYPE_ID, list.typeIds[i]);
            String name = list.names[i] == null ? "" : list.names[i];
            MemoryUtil.memPutAddress(entry + SpvcReflectedResource.NAME,
                    session.keep(MemoryUtil.memUTF8(name, true)));
        }
        MemoryUtil.memPutAddress(resourceList, base);
        MemoryUtil.memPutAddress(resourceSize, list.count);
        return SPVC_SUCCESS;
    }

    public static int spvc_compiler_get_decoration(long compiler, int id, int decoration) {
        Session session = sessionOf(compiler, TAG_COMPILER);
        return session == null ? 0
                : toolchain().getDecoration(session.generation, session.backend[indexOf(compiler)], id, decoration);
    }

    public static boolean spvc_compiler_get_binary_offset_for_decoration(
            long compiler, int id, int decoration, IntBuffer wordOffset) {
        // Written through the buffer: a view buffer may have no BrowserMemory address.
        Session session = sessionOf(compiler, TAG_COMPILER);
        if (session == null) {
            return false;
        }
        int[] offset = new int[1];
        boolean found = toolchain().getBinaryOffsetForDecoration(session.generation,
                session.backend[indexOf(compiler)], id, decoration, offset);
        if (found) {
            wordOffset.put(wordOffset.position(), offset[0]);
        }
        return found;
    }

    public static boolean nspvc_compiler_get_binary_offset_for_decoration(
            long compiler, int id, int decoration, long wordOffset) {
        Session session = sessionOf(compiler, TAG_COMPILER);
        if (session == null) {
            return false;
        }
        int[] offset = new int[1];
        boolean found = toolchain().getBinaryOffsetForDecoration(session.generation,
                session.backend[indexOf(compiler)], id, decoration, offset);
        if (found) {
            MemoryUtil.memPutInt(wordOffset, offset[0]);
        }
        return found;
    }

    public static void spvc_compiler_set_name(long compiler, int id, CharSequence argument) {
        setName(compiler, id, argument.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static void spvc_compiler_set_name(long compiler, int id, ByteBuffer argument) {
        setName(compiler, id, terminated(MemoryUtil.memAddress(argument)));
    }

    public static void nspvc_compiler_set_name(long compiler, int id, long argument) {
        setName(compiler, id, terminated(argument));
    }

    private static void setName(long compiler, int id, byte[] name) {
        Session session = sessionOf(compiler, TAG_COMPILER);
        if (session != null) {
            toolchain().setName(session.generation, session.backend[indexOf(compiler)], id, name);
        }
    }

    public static String spvc_compiler_get_name(long compiler, int id) {
        Session session = sessionOf(compiler, TAG_COMPILER);
        return session == null ? null
                : toolchain().getName(session.generation, session.backend[indexOf(compiler)], id);
    }

    public static long nspvc_compiler_get_name(long compiler, int id) {
        Session session = sessionOf(compiler, TAG_COMPILER);
        String name = session == null ? null
                : toolchain().getName(session.generation, session.backend[indexOf(compiler)], id);
        return name == null ? 0L : session.keep(MemoryUtil.memUTF8(name, true));
    }

    public static int spvc_compiler_set_entry_point(long compiler, CharSequence name, int model) {
        return setEntryPoint(compiler, name.toString().getBytes(StandardCharsets.UTF_8), model);
    }

    public static int spvc_compiler_set_entry_point(long compiler, ByteBuffer name, int model) {
        return setEntryPoint(compiler, terminated(MemoryUtil.memAddress(name)), model);
    }

    public static int nspvc_compiler_set_entry_point(long compiler, long name, int model) {
        return setEntryPoint(compiler, terminated(name), model);
    }

    private static int setEntryPoint(long compiler, byte[] name, int model) {
        Session session = sessionOf(compiler, TAG_COMPILER);
        if (session == null) {
            return SPVC_ERROR_INVALID_ARGUMENT;
        }
        int index = indexOf(compiler);
        int result = toolchain().setEntryPoint(session.generation, session.backend[index], name, model);
        if (result == SPVC_SUCCESS) {
            session.models[index] = model;
        }
        return result;
    }

    // ---- compilation ---------------------------------------------------------------------------------

    public static int spvc_compiler_compile(long compiler, PointerBuffer source) {
        return nspvc_compiler_compile(compiler, MemoryUtil.memAddress(source));
    }

    public static int nspvc_compiler_compile(long compiler, long source) {
        Session session = sessionOf(compiler, TAG_COMPILER);
        if (session == null) {
            return SPVC_ERROR_INVALID_ARGUMENT;
        }
        int index = indexOf(compiler);
        BrowserSpvcToolchain backend = toolchain();
        String[] out = new String[1];
        int result = backend.compile(session.generation, session.backend[index], out);
        String text;
        if (result == SPVC_SUCCESS && out[0] != null) {
            text = out[0];
            if (esslPostProcessing && session.kinds[index] == BACKEND_GLSL) {
                text = BrowserEsslPostProcessor.process(text, session.models[index] == EXECUTION_MODEL_FRAGMENT);
            }
        } else {
            String error = backend.contextLastError(session.generation, session.backend[0]);
            text = "#error Gaius shader toolchain: spvc_compiler_compile failed (" + result + "): "
                    + (error == null ? "no error text" : error.replace('\n', ' ').replace('\r', ' ')) + "\n";
        }
        MemoryUtil.memPutAddress(source, session.keep(MemoryUtil.memUTF8(text, true)));
        return result;
    }

    // ---- types -------------------------------------------------------------------------------------------

    public static int spvc_compiler_get_declared_struct_size(long compiler, long structType, PointerBuffer size) {
        return nspvc_compiler_get_declared_struct_size(compiler, structType, MemoryUtil.memAddress(size));
    }

    public static int nspvc_compiler_get_declared_struct_size(long compiler, long structType, long size) {
        Session session = sessionOf(compiler, TAG_COMPILER);
        if (session == null || sessionOf(structType, TAG_TYPE) != session) {
            return SPVC_ERROR_INVALID_ARGUMENT;
        }
        long[] out = new long[1];
        int result = toolchain().getDeclaredStructSize(session.generation, session.backend[indexOf(compiler)],
                session.backend[indexOf(structType)], out);
        if (result == SPVC_SUCCESS) {
            MemoryUtil.memPutAddress(size, out[0]);
        }
        return result;
    }

    public static long spvc_compiler_get_type_handle(long compiler, int id) {
        Session session = sessionOf(compiler, TAG_COMPILER);
        if (session == null) {
            return 0L;
        }
        long type = toolchain().getTypeHandle(session.generation, session.backend[indexOf(compiler)], id);
        if (type == 0L) {
            return 0L;
        }
        Integer known = session.types.get(type);
        if (known != null) {
            return ((long) TAG_TYPE << 56) | ((long) session.id << 32) | known;
        }
        long handle = session.add(TAG_TYPE, type, 0);
        session.types.put(type, indexOf(handle));
        return handle;
    }

    public static int spvc_type_get_basetype(long type) {
        Session session = sessionOf(type, TAG_TYPE);
        return session == null ? 0 : toolchain().typeGetBasetype(session.generation, session.backend[indexOf(type)]);
    }

    public static int spvc_type_get_image_dimension(long type) {
        Session session = sessionOf(type, TAG_TYPE);
        return session == null ? 0
                : toolchain().typeGetImageDimension(session.generation, session.backend[indexOf(type)]);
    }

    public static int spvc_type_get_vector_size(long type) {
        Session session = sessionOf(type, TAG_TYPE);
        return session == null ? 0 : toolchain().typeGetVectorSize(session.generation, session.backend[indexOf(type)]);
    }

    public static int spvc_type_get_num_array_dimensions(long type) {
        Session session = sessionOf(type, TAG_TYPE);
        return session == null ? 0
                : toolchain().typeGetNumArrayDimensions(session.generation, session.backend[indexOf(type)]);
    }

    public static int spvc_type_get_array_dimension(long type, int dimension) {
        Session session = sessionOf(type, TAG_TYPE);
        return session == null ? 0
                : toolchain().typeGetArrayDimension(session.generation, session.backend[indexOf(type)], dimension);
    }

    // ---- bytes -------------------------------------------------------------------------------------------

    private static byte[] terminated(long address) {
        if (address == 0L) {
            return new byte[0];
        }
        int length = 0;
        while (MemoryUtil.memGetByte(address + length) != 0) {
            length++;
        }
        byte[] bytes = new byte[length];
        if (length > 0) {
            MemoryUtil.memByteBuffer(address, length).get(bytes);
        }
        return bytes;
    }
}
