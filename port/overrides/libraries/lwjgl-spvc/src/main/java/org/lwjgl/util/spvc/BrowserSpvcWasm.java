package org.lwjgl.util.spvc;

import dev.gaius.browser.JsArrays;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int32Array;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * The browser {@link BrowserSpvcToolchain}: SPIRV-Cross compiled to
 * WebAssembly, driven through window.__gaiusShaderToolchain
 * (port/wasm/shader-toolchain/loader).  Backend handles are WebAssembly heap
 * pointers (32-bit); out values travel through spvcOut(0).
 */
final class BrowserSpvcWasm implements BrowserSpvcToolchain {
    @Override
    public int generation() {
        return available() ? generationJs() : -1;
    }

    @Override
    public int contextCreate(int generation, long[] context) {
        int result = contextCreateJs(generation);
        context[0] = out();
        return result;
    }

    @Override
    public void contextDestroy(int generation, long context) {
        contextDestroyJs(generation, (int) context);
    }

    @Override
    public String contextLastError(int generation, long context) {
        return contextLastErrorJs(generation, (int) context);
    }

    @Override
    public int parseSpirv(int generation, long context, int[] words, long[] parsedIr) {
        int result = parseSpirvJs(generation, (int) context, JsArrays.int32(words), words.length);
        parsedIr[0] = out();
        return result;
    }

    @Override
    public int createCompiler(int generation, long context, int backend, long parsedIr, int captureMode,
            long[] compiler) {
        int result = createCompilerJs(generation, (int) context, backend, (int) parsedIr, captureMode);
        compiler[0] = out();
        return result;
    }

    @Override
    public int createCompilerOptions(int generation, long compiler, long[] options) {
        int result = createCompilerOptionsJs(generation, (int) compiler);
        options[0] = out();
        return result;
    }

    @Override
    public int optionsSetBool(int generation, long options, int option, boolean value) {
        return optionsSetBoolJs(generation, (int) options, option, value);
    }

    @Override
    public int optionsSetUint(int generation, long options, int option, int value) {
        return optionsSetUintJs(generation, (int) options, option, value);
    }

    @Override
    public int installCompilerOptions(int generation, long compiler, long options) {
        return installCompilerOptionsJs(generation, (int) compiler, (int) options);
    }

    @Override
    public int createShaderResources(int generation, long compiler, long[] resources) {
        int result = createShaderResourcesJs(generation, (int) compiler);
        resources[0] = out();
        return result;
    }

    @Override
    public int resourceList(int generation, long resources, int type, ResourceList list) {
        int result = resourceListJs(generation, (int) resources, type);
        if (result != 0) {
            return result;
        }
        int count = out();
        list.allocate(count);
        if (count > 0) {
            int[] triples = new int[count * 3];
            Int32Array view = JsArrays.int32(triples);
            resourceFillJs(generation, view);
            JsArrays.readBack(view, triples);
            for (int i = 0; i < count; i++) {
                list.ids[i] = triples[i * 3];
                list.baseTypeIds[i] = triples[i * 3 + 1];
                list.typeIds[i] = triples[i * 3 + 2];
                list.names[i] = resourceNameJs(generation, i);
            }
        }
        return result;
    }

    @Override
    public int getDecoration(int generation, long compiler, int id, int decoration) {
        return getDecorationJs(generation, (int) compiler, id, decoration);
    }

    @Override
    public boolean getBinaryOffsetForDecoration(int generation, long compiler, int id, int decoration, int[] offset) {
        boolean found = getBinaryOffsetForDecorationJs(generation, (int) compiler, id, decoration) != 0;
        offset[0] = out();
        return found;
    }

    @Override
    public void setName(int generation, long compiler, int id, byte[] name) {
        setNameJs(generation, (int) compiler, id, JsArrays.int8(name));
    }

    @Override
    public String getName(int generation, long compiler, int id) {
        return getNameJs(generation, (int) compiler, id);
    }

    @Override
    public int setEntryPoint(int generation, long compiler, byte[] name, int executionModel) {
        return setEntryPointJs(generation, (int) compiler, JsArrays.int8(name), executionModel);
    }

    @Override
    public int compile(int generation, long compiler, String[] source) {
        int result = compileJs(generation, (int) compiler);
        source[0] = result == 0 ? compiledSourceJs(generation) : null;
        return result;
    }

    @Override
    public int getDeclaredStructSize(int generation, long compiler, long type, long[] size) {
        int result = getDeclaredStructSizeJs(generation, (int) compiler, (int) type);
        size[0] = out() & 0xffffffffL;
        return result;
    }

    @Override
    public long getTypeHandle(int generation, long compiler, int typeId) {
        return getTypeHandleJs(generation, (int) compiler, typeId) & 0xffffffffL;
    }

    @Override
    public int typeGetBasetype(int generation, long type) {
        return typeGetBasetypeJs(generation, (int) type);
    }

    @Override
    public int typeGetImageDimension(int generation, long type) {
        return typeGetImageDimensionJs(generation, (int) type);
    }

    @Override
    public int typeGetVectorSize(int generation, long type) {
        return typeGetVectorSizeJs(generation, (int) type);
    }

    @Override
    public int typeGetNumArrayDimensions(int generation, long type) {
        return typeGetNumArrayDimensionsJs(generation, (int) type);
    }

    @Override
    public int typeGetArrayDimension(int generation, long type, int dimension) {
        return typeGetArrayDimensionJs(generation, (int) type, dimension);
    }

    @JSBody(script = "return !!(window.__gaiusShaderToolchain && window.__gaiusShaderToolchain.spvcGeneration);")
    private static native boolean available();

    @JSBody(script = "return window.__gaiusShaderToolchain.spvcGeneration();")
    private static native int generationJs();

    @JSBody(script = "return window.__gaiusShaderToolchain.spvcOut(0);")
    private static native int out();

    @JSBody(params = "gen", script = "return window.__gaiusShaderToolchain.spvcContextCreate(gen);")
    private static native int contextCreateJs(int gen);

    @JSBody(params = {"gen", "context"},
            script = "window.__gaiusShaderToolchain.spvcContextDestroy(gen, context);")
    private static native void contextDestroyJs(int gen, int context);

    @JSBody(params = {"gen", "context"},
            script = "return window.__gaiusShaderToolchain.spvcContextLastError(gen, context);")
    private static native String contextLastErrorJs(int gen, int context);

    @JSBody(params = {"gen", "context", "words", "wordCount"},
            script = "return window.__gaiusShaderToolchain.spvcParseSpirv(gen, context, words, wordCount);")
    private static native int parseSpirvJs(int gen, int context, Int32Array words, int wordCount);

    @JSBody(params = {"gen", "context", "backend", "ir", "mode"},
            script = "return window.__gaiusShaderToolchain.spvcCreateCompiler(gen, context, backend, ir, mode);")
    private static native int createCompilerJs(int gen, int context, int backend, int ir, int mode);

    @JSBody(params = {"gen", "compiler"},
            script = "return window.__gaiusShaderToolchain.spvcCreateCompilerOptions(gen, compiler);")
    private static native int createCompilerOptionsJs(int gen, int compiler);

    @JSBody(params = {"gen", "options", "option", "value"},
            script = "return window.__gaiusShaderToolchain.spvcOptionsSetBool(gen, options, option, value);")
    private static native int optionsSetBoolJs(int gen, int options, int option, boolean value);

    @JSBody(params = {"gen", "options", "option", "value"},
            script = "return window.__gaiusShaderToolchain.spvcOptionsSetUint(gen, options, option, value);")
    private static native int optionsSetUintJs(int gen, int options, int option, int value);

    @JSBody(params = {"gen", "compiler", "options"},
            script = "return window.__gaiusShaderToolchain.spvcInstallCompilerOptions(gen, compiler, options);")
    private static native int installCompilerOptionsJs(int gen, int compiler, int options);

    @JSBody(params = {"gen", "compiler"},
            script = "return window.__gaiusShaderToolchain.spvcCreateShaderResources(gen, compiler);")
    private static native int createShaderResourcesJs(int gen, int compiler);

    @JSBody(params = {"gen", "resources", "type"},
            script = "return window.__gaiusShaderToolchain.spvcResourceList(gen, resources, type);")
    private static native int resourceListJs(int gen, int resources, int type);

    @JSBody(params = {"gen", "target"},
            script = "window.__gaiusShaderToolchain.spvcResourceFill(gen, target);")
    private static native void resourceFillJs(int gen, Int32Array target);

    @JSBody(params = {"gen", "index"},
            script = "return window.__gaiusShaderToolchain.spvcResourceName(gen, index);")
    private static native String resourceNameJs(int gen, int index);

    @JSBody(params = {"gen", "compiler", "id", "decoration"},
            script = "return window.__gaiusShaderToolchain.spvcGetDecoration(gen, compiler, id, decoration);")
    private static native int getDecorationJs(int gen, int compiler, int id, int decoration);

    @JSBody(params = {"gen", "compiler", "id", "decoration"},
            script = "return window.__gaiusShaderToolchain.spvcGetBinaryOffsetForDecoration(gen, compiler,"
                    + " id, decoration);")
    private static native int getBinaryOffsetForDecorationJs(int gen, int compiler, int id, int decoration);

    @JSBody(params = {"gen", "compiler", "id", "name"},
            script = "window.__gaiusShaderToolchain.spvcSetName(gen, compiler, id, name);")
    private static native void setNameJs(int gen, int compiler, int id, Int8Array name);

    @JSBody(params = {"gen", "compiler", "id"},
            script = "return window.__gaiusShaderToolchain.spvcGetName(gen, compiler, id);")
    private static native String getNameJs(int gen, int compiler, int id);

    @JSBody(params = {"gen", "compiler", "name", "model"},
            script = "return window.__gaiusShaderToolchain.spvcSetEntryPoint(gen, compiler, name, model);")
    private static native int setEntryPointJs(int gen, int compiler, Int8Array name, int model);

    @JSBody(params = {"gen", "compiler"},
            script = "return window.__gaiusShaderToolchain.spvcCompile(gen, compiler);")
    private static native int compileJs(int gen, int compiler);

    @JSBody(params = "gen", script = "return window.__gaiusShaderToolchain.spvcCompiledSource(gen);")
    private static native String compiledSourceJs(int gen);

    @JSBody(params = {"gen", "compiler", "type"},
            script = "return window.__gaiusShaderToolchain.spvcGetDeclaredStructSize(gen, compiler, type);")
    private static native int getDeclaredStructSizeJs(int gen, int compiler, int type);

    @JSBody(params = {"gen", "compiler", "typeId"},
            script = "return window.__gaiusShaderToolchain.spvcGetTypeHandle(gen, compiler, typeId);")
    private static native int getTypeHandleJs(int gen, int compiler, int typeId);

    @JSBody(params = {"gen", "type"},
            script = "return window.__gaiusShaderToolchain.spvcTypeGetBasetype(gen, type);")
    private static native int typeGetBasetypeJs(int gen, int type);

    @JSBody(params = {"gen", "type"},
            script = "return window.__gaiusShaderToolchain.spvcTypeGetImageDimension(gen, type);")
    private static native int typeGetImageDimensionJs(int gen, int type);

    @JSBody(params = {"gen", "type"},
            script = "return window.__gaiusShaderToolchain.spvcTypeGetVectorSize(gen, type);")
    private static native int typeGetVectorSizeJs(int gen, int type);

    @JSBody(params = {"gen", "type"},
            script = "return window.__gaiusShaderToolchain.spvcTypeGetNumArrayDimensions(gen, type);")
    private static native int typeGetNumArrayDimensionsJs(int gen, int type);

    @JSBody(params = {"gen", "type", "dimension"},
            script = "return window.__gaiusShaderToolchain.spvcTypeGetArrayDimension(gen, type, dimension);")
    private static native int typeGetArrayDimensionJs(int gen, int type, int dimension);
}
