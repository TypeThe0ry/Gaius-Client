package org.lwjgl.util.shaderc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.lwjgl.system.MemoryUtil;

public final class BrowserShadercIncludeTest {
    public static void main(String[] args) {
        accepted("#moj_import \"a\"");
        accepted("# /* comment */ moj_import /* comment */ <a>");
        accepted("\t#\finclude\t\"a\"");
        accepted("#include/*x*/<a>");
        accepted("#moj_import \"\"");
        accepted("#include \"a\" \"b\"");
        nestedMojImportsNormalizeAtEveryLevel();
        recursiveIncludeCompilationReceivesNormalizedSources();
        rejected("# // comment\n#include \"a\"");
        rejected("#moj_imported \"a\"");
        rejected("#moj_import /* unterminated \"a\"");
        System.out.println("BROWSER_SHADERC_INCLUDE_TEST_OK");
    }

    private static void accepted(String source) {
        byte[] bytes = source.getBytes(StandardCharsets.US_ASCII);
        int end = source.indexOf('\n');
        if (end < 0) end = bytes.length;
        int[] directive = BrowserShaderc.includeDirective(bytes, 0, end);
        if (directive == null || directive[1] > directive[2]) {
            throw new AssertionError("Expected include directive: " + source);
        }
    }

    private static void rejected(String source) {
        byte[] bytes = source.getBytes(StandardCharsets.US_ASCII);
        int end = source.indexOf('\n');
        if (end < 0) end = bytes.length;
        if (BrowserShaderc.includeDirective(bytes, 0, end) != null) {
            throw new AssertionError("Unexpected include directive: " + source);
        }
    }

    private static void nestedMojImportsNormalizeAtEveryLevel() {
        byte[] root = BrowserShaderc.normalizeMojImports(
                "#moj_import \"a\"".getBytes(StandardCharsets.US_ASCII));
        byte[] middle = BrowserShaderc.normalizeMojImports(
                "#moj_import \"b\"".getBytes(StandardCharsets.US_ASCII));
        byte[] leaf = BrowserShaderc.normalizeMojImports(
                "#moj_import \"c\"".getBytes(StandardCharsets.US_ASCII));
        if (BrowserShaderc.includeDirective(root, 0, root.length)[4] == 10
                || BrowserShaderc.includeDirective(middle, 0, middle.length)[4] == 10
                || BrowserShaderc.includeDirective(leaf, 0, leaf.length)[4] == 10) {
            throw new AssertionError("Nested moj_import was not normalized before shaderc");
        }
    }
    private static void recursiveIncludeCompilationReceivesNormalizedSources() {
        List<Long> allocations = new ArrayList<>();
        List<String> normalized = new ArrayList<>();
        BrowserShaderc.setToolchain(job -> {
            normalized.add(new String(job.source, StandardCharsets.UTF_8));
            for (BrowserShadercJob.Include include : job.includes) {
                normalized.add(new String(include.content, StandardCharsets.UTF_8));
            }
            return new BrowserShadercJob.Output(0, new byte[] {1}, "", 0, 0);
        });
        long compiler = BrowserShaderc.shaderc_compiler_initialize();
        long options = BrowserShaderc.shaderc_compile_options_initialize();
        BrowserShaderc.shaderc_compile_options_set_include_callbacks(options,
                (userData, requested, type, requesting, depth) -> {
                    String name = MemoryUtil.memUTF8(requested);
                    String sourceName = name.equals("a") ? "a"
                            : name.equals("b") ? "b" : name;
                    String body = name.equals("a") ? "#moj_import \"b\"\n"
                            : name.equals("b") ? "#moj_import \"c\"\n" : "const int leaf = 1;\n";
                    byte[] sourceBytes = sourceName.getBytes(StandardCharsets.UTF_8);
                    byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
                    long record = MemoryUtil.nmemCalloc(1, ShadercIncludeResult.SIZEOF);
                    long source = MemoryUtil.nmemAlloc(sourceBytes.length + 1);
                    long content = MemoryUtil.nmemAlloc(bodyBytes.length + 1);
                    allocations.add(record);
                    allocations.add(source);
                    allocations.add(content);
                    for (int i = 0; i < sourceBytes.length; i++) MemoryUtil.memPutByte(source + i, sourceBytes[i]);
                    MemoryUtil.memPutByte(source + sourceBytes.length, (byte) 0);
                    for (int i = 0; i < bodyBytes.length; i++) MemoryUtil.memPutByte(content + i, bodyBytes[i]);
                    MemoryUtil.memPutByte(content + bodyBytes.length, (byte) 0);
                    MemoryUtil.memPutAddress(record + ShadercIncludeResult.SOURCE_NAME, source);
                    MemoryUtil.memPutLong(record + ShadercIncludeResult.SOURCE_NAME_LENGTH, sourceBytes.length);
                    MemoryUtil.memPutAddress(record + ShadercIncludeResult.CONTENT, content);
                    MemoryUtil.memPutLong(record + ShadercIncludeResult.CONTENT_LENGTH, bodyBytes.length);
                    return record;
                }, (userData, result) -> {}, 0);
        try {
            long result = BrowserShaderc.shaderc_compile_into_spv(
                    compiler, "#moj_import \"a\"\nvoid main() {}\n", 0, "root", "main", options);
            if (BrowserShaderc.shaderc_result_get_compilation_status(result) != 0) {
                throw new AssertionError("nested shader compile failed");
            }
            if (normalized.size() != 4) throw new AssertionError("expected root and three recursive sources: " + normalized.size());
            for (String text : normalized) {
                if (text.contains("#moj_import")) throw new AssertionError("compiler received raw moj_import: " + text);
            }
        } finally {
            BrowserShaderc.setToolchain(null);
            BrowserShaderc.shaderc_compile_options_release(options);
            BrowserShaderc.shaderc_compiler_release(compiler);
            for (long pointer : allocations) MemoryUtil.nmemFree(pointer);
        }
    }
}
