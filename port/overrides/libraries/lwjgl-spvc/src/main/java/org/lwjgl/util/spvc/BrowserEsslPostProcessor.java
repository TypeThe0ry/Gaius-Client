package org.lwjgl.util.spvc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the GLSL 330 that SPIRV-Cross generates for Minecraft's GL backend into
 * WebGL2 ESSL 3.00 (PLAN D5).  Only spvc output passes through here, and every
 * rewrite is keyed by GLSL types and declarations, never by Minecraft uniform
 * names (spvc renames them to _uniform_NN_NN):
 *
 * <ol>
 *   <li>texel buffers: [iu]samplerBuffer uniforms become highp [iu]sampler2D
 *       and texelFetch on them goes through a helper that addresses the
 *       4096-wide 2D texture BrowserOpenGL uses to emulate glTexBuffer;</li>
 *   <li>fragment output arrays (ESSL 3.00 allows only constant indices) are
 *       split into one output per location, copied from a global array after
 *       the original main;</li>
 *   <li>the header becomes "#version 300 es" with highp defaults for float,
 *       int and every sampler type used (lowp is the ESSL default for sampler2D
 *       and samplerCube; the others have no default at all);</li>
 *   <li>the text is made a fixed point of BrowserOpenGL.translateShaderSource:
 *       that pass (unchanged, it also serves 26.2) replaces Minecraft 26.2
 *       source snippets and strips desktop "f" suffixes, which can corrupt
 *       spvc output (for example "float(gl_VertexID >> 3) / 1000.0" became
 *       "floatfloat(...)").  Each snippet occurrence gets an empty comment
 *       after its first token, and identifiers the suffix stripper would cut
 *       are renamed.</li>
 * </ol>
 */
public final class BrowserEsslPostProcessor {
    /** The texture width BrowserOpenGL uses for glTexBuffer (TEXTURE_BUFFER) storage. */
    static final int TEXEL_BUFFER_WIDTH = 4096;

    /** The literal patterns of BrowserOpenGL.translateShaderSource, in its order. */
    static final String[] LEGACY_TRANSLATE_PATTERNS = {
        "uv / 256.0",
        "texCoord2 = UV2;",
        "floor(texCoord.x * 16) / 15",
        "floor(texCoord.y * 16) / 15",
        "Position + (ChunkPosition - CameraBlockPos) + CameraOffset",
        "1.0f / TextureSize",
        "1.0 / TextureSize",
        "vec3(cellX, 0, cellZ)",
        "linear_fog_value(vertexDistance, 0, FogCloudsEnd)",
        "uniform isamplerBuffer CloudFaces;",
        "texelFetch(CloudFaces, index).r",
        "texelFetch(CloudFaces, index + 1).r",
        "texelFetch(CloudFaces, index + 2).r",
        "textureLod(Sprite, texCoord0, MipMapLevel)",
        "textureLod(CurrentSprite, texCoord0, MipMapLevel)",
        "textureLod(NextSprite, texCoord0, MipMapLevel)",
        "(gl_VertexID >> 3) / 1000.0",
        "UV0 * SKINRES",
        "SPACING * (partId + 1)",
        "(1 - fade)",
    };

    /** The substrings that together enable BrowserOpenGL's BetterHUD rewrite. */
    static final String[] LEGACY_BETTER_HUD_GUARDS = {
        "#define HEIGHT_BIT ",
        "#define MAX_BIT ",
        "bool checkElement(float z)",
        "out float applyColor;",
    };

    private static final String[] SAMPLER_TYPES = {
        "sampler2D", "samplerCube", "sampler3D", "samplerCubeShadow", "sampler2DShadow", "sampler2DArray",
        "sampler2DArrayShadow", "isampler2D", "isampler3D", "isamplerCube", "isampler2DArray", "usampler2D",
        "usampler3D", "usamplerCube", "usampler2DArray",
    };

    private BrowserEsslPostProcessor() {
    }

    /**
     * Post-processes one spvc GLSL output.  Text that is not "#version 330"
     * (another spvc configuration) is returned unchanged.
     */
    public static String process(String glsl, boolean fragmentShader) {
        if (!glsl.startsWith("#version 330")) {
            return glsl;
        }
        String text = rewriteTexelBuffers(glsl);
        if (fragmentShader) {
            text = splitFragmentOutputArrays(text);
        }
        text = esslHeader(text);
        text = renameSuffixStrippedIdentifiers(text);
        return shieldLegacyPatterns(text);
    }

    // ---- 1. texel buffers ------------------------------------------------------------------------------

    static String rewriteTexelBuffers(String text) {
        List<String> names = new ArrayList<>();
        StringBuilder out = new StringBuilder(text.length() + 256);
        Set<String> helpers = new HashSet<>();
        int lineStart = 0;
        while (lineStart < text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            int next = lineEnd < 0 ? text.length() : lineEnd + 1;
            String line = text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd);
            String[] declaration = samplerBufferDeclaration(line);
            if (declaration == null) {
                out.append(text, lineStart, next);
            } else {
                String kind = declaration[0];
                String name = declaration[1];
                names.add(name);
                out.append("uniform highp ").append(kind).append("sampler2D ").append(name).append(";\n");
                if (helpers.add(kind)) {
                    String vector = kind.isEmpty() ? "vec4" : kind + "vec4";
                    out.append("highp ").append(vector).append(" _gaius_texelFetchBuffer(highp ").append(kind)
                            .append("sampler2D s, int i)\n{\n    return texelFetch(s, ivec2(i % ")
                            .append(TEXEL_BUFFER_WIDTH).append(", i / ").append(TEXEL_BUFFER_WIDTH)
                            .append("), 0);\n}\n");
                }
            }
            lineStart = next;
        }
        if (names.isEmpty()) {
            return text;
        }
        String result = out.toString();
        for (String name : names) {
            result = renameTexelFetch(result, name);
        }
        return result;
    }

    /** {kind ("", "i" or "u"), name} of "uniform [precision] [iu]samplerBuffer name;", or null. */
    private static String[] samplerBufferDeclaration(String line) {
        String[] tokens = words(line).toArray(new String[0]);
        int index = 0;
        if (tokens.length < 3 || !tokens[index++].equals("uniform")) {
            return null;
        }
        if (tokens[index].equals("highp") || tokens[index].equals("mediump") || tokens[index].equals("lowp")) {
            index++;
        }
        if (tokens.length != index + 2 || !tokens[index + 1].endsWith(";")) {
            return null;
        }
        String type = tokens[index];
        String kind;
        if (type.equals("samplerBuffer")) {
            kind = "";
        } else if (type.equals("isamplerBuffer")) {
            kind = "i";
        } else if (type.equals("usamplerBuffer")) {
            kind = "u";
        } else {
            return null;
        }
        String name = tokens[index + 1].substring(0, tokens[index + 1].length() - 1);
        return isIdentifier(name) ? new String[] {kind, name} : null;
    }

    /** texelFetch(name, ...) becomes _gaius_texelFetchBuffer(name, ...). */
    private static String renameTexelFetch(String text, String name) {
        StringBuilder out = new StringBuilder(text.length() + 64);
        int from = 0;
        while (true) {
            int call = text.indexOf("texelFetch", from);
            if (call < 0) {
                break;
            }
            int end = call + "texelFetch".length();
            boolean token = (call == 0 || !isIdentifierPart(text.charAt(call - 1)))
                    && (end >= text.length() || !isIdentifierPart(text.charAt(end)));
            int i = skipSpaces(text, end);
            boolean matches = token && i < text.length() && text.charAt(i) == '(';
            if (matches) {
                i = skipSpaces(text, i + 1);
                int nameEnd = i + name.length();
                matches = text.startsWith(name, i)
                        && (nameEnd >= text.length() || !isIdentifierPart(text.charAt(nameEnd)));
                if (matches) {
                    int comma = skipSpaces(text, nameEnd);
                    matches = comma < text.length() && text.charAt(comma) == ',';
                }
            }
            out.append(text, from, call).append(matches ? "_gaius_texelFetchBuffer" : "texelFetch");
            from = end;
        }
        return out.append(text, from, text.length()).toString();
    }

    // ---- 2. fragment output arrays -------------------------------------------------------------------------

    static String splitFragmentOutputArrays(String text) {
        StringBuilder out = new StringBuilder(text.length() + 512);
        StringBuilder copies = new StringBuilder();
        int lineStart = 0;
        while (lineStart < text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            int next = lineEnd < 0 ? text.length() : lineEnd + 1;
            String line = text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd);
            String[] array = fragmentOutputArray(line);
            if (array == null) {
                out.append(text, lineStart, next);
            } else {
                int location = Integer.parseInt(array[0]);
                String type = array[1];
                String name = array[2];
                int count = Integer.parseInt(array[3]);
                for (int k = 0; k < count; k++) {
                    out.append("layout(location = ").append(location + k).append(") out ").append(type).append(' ')
                            .append(name).append("_essl").append(k).append(";\n");
                    copies.append("    ").append(name).append("_essl").append(k).append(" = ").append(name)
                            .append('[').append(k).append("];\n");
                }
                out.append(type).append(' ').append(name).append('[').append(count).append("];\n");
            }
            lineStart = next;
        }
        if (copies.length() == 0) {
            return text;
        }
        String result = out.toString();
        String main = "\nvoid main()\n";
        int mainAt = result.indexOf(main);
        if (mainAt < 0 || result.indexOf(main, mainAt + 1) >= 0) {
            return text;
        }
        result = result.substring(0, mainAt) + "\nvoid _gaius_essl_main()\n" + result.substring(mainAt + main.length());
        if (!result.endsWith("\n")) {
            result += "\n";
        }
        return result + "\nvoid main()\n{\n    _gaius_essl_main();\n" + copies + "}\n";
    }

    /** {location, type, name, count} of "layout(location = L) out TYPE NAME[N];", or null. */
    private static String[] fragmentOutputArray(String line) {
        String prefix = "layout(location = ";
        if (!line.startsWith(prefix)) {
            return null;
        }
        int close = line.indexOf(") out ", prefix.length());
        if (close < 0 || !isDigits(line.substring(prefix.length(), close))) {
            return null;
        }
        String rest = line.substring(close + ") out ".length());
        int space = rest.indexOf(' ');
        int open = rest.indexOf('[');
        if (space < 0 || open < space || !rest.endsWith("];")) {
            return null;
        }
        String type = rest.substring(0, space);
        String name = rest.substring(space + 1, open);
        String count = rest.substring(open + 1, rest.length() - 2);
        if (!isIdentifier(type) || !isIdentifier(name) || !isDigits(count) || Integer.parseInt(count) < 1) {
            return null;
        }
        return new String[] {line.substring(prefix.length(), close), type, name, count};
    }

    // ---- 3. ESSL header --------------------------------------------------------------------------------

    static String esslHeader(String text) {
        int lineEnd = text.indexOf('\n');
        String body = lineEnd < 0 ? "" : text.substring(lineEnd + 1);
        // #extension directives must stay ahead of the first precision statement.
        StringBuilder directives = new StringBuilder();
        while (body.startsWith("#extension")) {
            int end = body.indexOf('\n');
            directives.append(end < 0 ? body + "\n" : body.substring(0, end + 1));
            body = end < 0 ? "" : body.substring(end + 1);
        }
        StringBuilder header = new StringBuilder("#version 300 es\n").append(directives)
                .append("precision highp float;\nprecision highp int;\n");
        for (String sampler : SAMPLER_TYPES) {
            if (containsToken(body, sampler)) {
                header.append("precision highp ").append(sampler).append(";\n");
            }
        }
        return header.append(body).toString();
    }

    // ---- 4. fixed point of BrowserOpenGL.translateShaderSource ---------------------------------------------

    /**
     * translateShaderSource ends with stripDesktopFloatSuffixes, which deletes
     * an 'f'/'F' preceded by a digit or '.' and not followed by an identifier
     * character.  In spvc output that only matches identifiers such as
     * "color2f" or a member "f" accessed as ".f" (and hexadecimal literals such
     * as 0x1F): rename every such identifier consistently and write such
     * literals in decimal.
     */
    static String renameSuffixStrippedIdentifiers(String text) {
        Set<String> renamed = new HashSet<>();
        boolean hexLiterals = false;
        int length = text.length();
        for (int i = 0; i < length; ) {
            char c = text.charAt(i);
            if (isIdentifierStart(c)) {
                int end = i + 1;
                while (end < length && isIdentifierPart(text.charAt(end))) {
                    end++;
                }
                if (strippedAt(text, end - 1)) {
                    renamed.add(text.substring(i, end));
                }
                i = end;
            } else if (c >= '0' && c <= '9') {
                int end = i + 1;
                while (end < length && (isIdentifierPart(text.charAt(end)) || text.charAt(end) == '.')) {
                    end++;
                }
                if (strippedAt(text, end - 1)) {
                    hexLiterals = true;
                }
                i = end;
            } else {
                i++;
            }
        }
        if (renamed.isEmpty() && !hexLiterals) {
            return text;
        }
        StringBuilder out = new StringBuilder(length + 16 * renamed.size());
        for (int i = 0; i < length; ) {
            char c = text.charAt(i);
            if (isIdentifierStart(c)) {
                int end = i + 1;
                while (end < length && isIdentifierPart(text.charAt(end))) {
                    end++;
                }
                String identifier = text.substring(i, end);
                out.append(identifier);
                if (renamed.contains(identifier)) {
                    out.append("_gaius");
                }
                i = end;
            } else if (c >= '0' && c <= '9') {
                int end = i + 1;
                while (end < length && (isIdentifierPart(text.charAt(end)) || text.charAt(end) == '.')) {
                    end++;
                }
                String literal = text.substring(i, end);
                out.append(strippedAt(text, end - 1) ? decimalLiteral(literal) : literal);
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean strippedAt(String text, int index) {
        char c = text.charAt(index);
        if ((c != 'f' && c != 'F') || index == 0) {
            return false;
        }
        char previous = text.charAt(index - 1);
        return (previous >= '0' && previous <= '9') || previous == '.';
    }

    /** A literal the suffix stripper would cut, written without the trailing f. */
    private static String decimalLiteral(String literal) {
        if (literal.startsWith("0x") || literal.startsWith("0X")) {
            long value = Long.parseLong(literal.substring(2), 16);
            return value <= Integer.MAX_VALUE ? Long.toString(value) : "int(" + value + "u)";
        }
        // A float literal with an 'f' suffix: the suffix is optional.
        return literal.substring(0, literal.length() - 1);
    }

    /**
     * Breaks every occurrence of a translateShaderSource pattern with an empty
     * comment (which the GLSL preprocessor turns into a space) after the
     * pattern's first space, so none of the literal replacements applies.
     */
    static String shieldLegacyPatterns(String text) {
        String result = text;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String pattern : LEGACY_TRANSLATE_PATTERNS) {
                int at = result.indexOf(pattern);
                if (at >= 0) {
                    int split = at + pattern.indexOf(' ');
                    result = result.substring(0, split) + "/**/" + result.substring(split);
                    changed = true;
                }
            }
        }
        boolean betterHud = true;
        for (String guard : LEGACY_BETTER_HUD_GUARDS) {
            betterHud &= result.contains(guard);
        }
        if (betterHud) {
            int at = result.indexOf(LEGACY_BETTER_HUD_GUARDS[0]);
            result = result.substring(0, at) + "#define/**/" + result.substring(at + "#define".length());
        }
        return result;
    }

    // ---- characters -------------------------------------------------------------------------------------

    private static List<String> words(String line) {
        List<String> words = new ArrayList<>();
        int i = 0;
        while (i < line.length()) {
            while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < line.length() && !Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            if (i > start) {
                words.add(line.substring(start, i));
            }
        }
        return words;
    }

    private static boolean containsToken(String text, String token) {
        int from = 0;
        while (true) {
            int at = text.indexOf(token, from);
            if (at < 0) {
                return false;
            }
            int end = at + token.length();
            if ((at == 0 || !isIdentifierPart(text.charAt(at - 1)))
                    && (end >= text.length() || !isIdentifierPart(text.charAt(end)))) {
                return true;
            }
            from = at + 1;
        }
    }

    private static int skipSpaces(String text, int index) {
        while (index < text.length() && (text.charAt(index) == ' ' || text.charAt(index) == '\t')) {
            index++;
        }
        return index;
    }

    private static boolean isDigits(String text) {
        if (text.isEmpty() || text.length() > 6) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isIdentifier(String text) {
        if (text.isEmpty() || !isIdentifierStart(text.charAt(0))) {
            return false;
        }
        for (int i = 1; i < text.length(); i++) {
            if (!isIdentifierPart(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIdentifierStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || (c >= '0' && c <= '9');
    }
}
