package org.lwjgl.util.spvc;

/**
 * The SPIRV-Cross C API behind {@link BrowserSpvc}, one method per C function
 * (the browser uses {@link BrowserSpvcWasm}; the JVM corpus harness installs a
 * native one).  Handles are the backend's own (WebAssembly heap pointers in the
 * browser).  Every call names the backend generation its context was created
 * in: a backend that replaced a failed instance rejects older handles with an
 * spvc error code (or 0 / null for accessors) instead of touching the new one.
 */
public interface BrowserSpvcToolchain {
    /** The generation new contexts belong to, or a negative value when no instance is available. */
    int generation();

    int contextCreate(int generation, long[] context);

    void contextDestroy(int generation, long context);

    String contextLastError(int generation, long context);

    /** spvc_context_parse_spirv over all of words. */
    int parseSpirv(int generation, long context, int[] words, long[] parsedIr);

    int createCompiler(int generation, long context, int backend, long parsedIr, int captureMode, long[] compiler);

    int createCompilerOptions(int generation, long compiler, long[] options);

    int optionsSetBool(int generation, long options, int option, boolean value);

    int optionsSetUint(int generation, long options, int option, int value);

    int installCompilerOptions(int generation, long compiler, long options);

    int createShaderResources(int generation, long compiler, long[] resources);

    /** spvc_resources_get_resource_list_for_type; fills list on success. */
    int resourceList(int generation, long resources, int type, ResourceList list);

    int getDecoration(int generation, long compiler, int id, int decoration);

    /** Returns whether the decoration exists; its word offset is stored in offset[0]. */
    boolean getBinaryOffsetForDecoration(int generation, long compiler, int id, int decoration, int[] offset);

    /** name is UTF-8 without a terminator. */
    void setName(int generation, long compiler, int id, byte[] name);

    String getName(int generation, long compiler, int id);

    /** name is UTF-8 without a terminator. */
    int setEntryPoint(int generation, long compiler, byte[] name, int executionModel);

    /** spvc_compiler_compile; source[0] receives the generated text on success. */
    int compile(int generation, long compiler, String[] source);

    int getDeclaredStructSize(int generation, long compiler, long type, long[] size);

    long getTypeHandle(int generation, long compiler, int typeId);

    int typeGetBasetype(int generation, long type);

    int typeGetImageDimension(int generation, long type);

    int typeGetVectorSize(int generation, long type);

    int typeGetNumArrayDimensions(int generation, long type);

    int typeGetArrayDimension(int generation, long type, int dimension);

    /** One spvc_reflected_resource list. */
    final class ResourceList {
        public int count;
        public int[] ids;
        public int[] baseTypeIds;
        public int[] typeIds;
        public String[] names;

        public void allocate(int size) {
            count = size;
            ids = new int[size];
            baseTypeIds = new int[size];
            typeIds = new int[size];
            names = new String[size];
        }
    }
}
