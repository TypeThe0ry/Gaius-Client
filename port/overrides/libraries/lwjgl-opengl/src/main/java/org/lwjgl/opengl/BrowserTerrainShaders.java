package org.lwjgl.opengl;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites the terrain shaders so one draw call can render many chunk sections.
 *
 * <p>Vanilla binds a ChunkSection uniform block (section origin as {@code ivec3}, fade-in
 * visibility as {@code float}) once per section draw. The rewritten shader keeps that block
 * and adds a GaiusChunkSections block holding 256 {@code ivec4} records (origin in xyz,
 * visibility float bits in w). While the program's {@code gaius_BatchMode} uniform is 1 the
 * section data comes from the record selected by {@code gl_DrawID} (WEBGL_multi_draw, mode 1)
 * or by the {@code gaius_DrawId} uniform (mode 2, one draw per section); while it is 0, the
 * default, the shader reads the vanilla block exactly as before, so every non-batched draw is
 * unchanged.</p>
 *
 * <p>The block is found by member names (ChunkPosition and ChunkVisibility, as in 26.2 GLSL and
 * in SPIRV-Cross output that keeps member names) or, failing that, as the only uniform block
 * whose members are exactly one {@code ivec3} and one {@code float} (26.3 renames blocks to
 * {@code _uniform_SS_BB}). Vertex shaders also export the selected values as flat varyings for
 * fragment shaders that read the visibility directly (26.2 terrain.fsh). Sources without such
 * a block are returned unchanged. BrowserOpenGL never lets a rewrite cost a pipeline: a
 * rewritten shader that does not compile is recompiled from the mode 2 rewrite (in mode 1) or
 * from its original source, and a program whose rewritten shaders do not link, or that mixes
 * them with a reverted shader, is relinked from the original sources.</p>
 */
final class BrowserTerrainShaders {
    static final String BLOCK_NAME = "GaiusChunkSections";
    static final int SECTIONS_PER_DRAW_BLOCK = 256;
    private static final String POSITION_NAME = "ChunkPosition";
    private static final String VISIBILITY_NAME = "ChunkVisibility";

    private BrowserTerrainShaders() {
    }

    /**
     * Returns the rewritten source, or {@code source} itself when it is not a terrain shader
     * or batching is off ({@code mode} 0).
     */
    static String rewrite(String source, int mode) {
        if (source == null || (mode != 1 && mode != 2)) {
            return source;
        }
        if (source.indexOf(BLOCK_NAME) >= 0 || source.indexOf("gaius_BatchMode") >= 0) {
            return source;
        }
        Block block = findChunkSectionBlock(source);
        if (block == null) {
            return source;
        }
        String positionReference = block.reference(block.positionMember);
        String visibilityReference = block.reference(block.visibilityMember);
        String head = source.substring(0, block.end);
        String tail = source.substring(block.end);
        boolean usesPosition = countTokens(tail, positionReference) > 0;
        boolean usesVisibility = countTokens(tail, visibilityReference) > 0;
        if (!usesPosition && !usesVisibility) {
            return source;
        }
        if (source.indexOf("gl_Position") >= 0) {
            return rewriteVertex(head, tail, mode, positionReference, visibilityReference);
        }
        return rewriteFragment(
                head, tail, positionReference, visibilityReference, usesPosition, usesVisibility);
    }

    private static String rewriteVertex(
            String head, String tail, int mode, String positionReference,
            String visibilityReference) {
        String drawIndex = mode == 1 ? "gl_DrawID" : "gaius_DrawId";
        StringBuilder helpers = new StringBuilder(1024);
        helpers.append("\nlayout(std140) uniform ").append(BLOCK_NAME).append(" {\n")
                .append("    highp ivec4 gaius_ChunkSections[")
                .append(SECTIONS_PER_DRAW_BLOCK).append("];\n};\n")
                .append("uniform highp int gaius_BatchMode;\n");
        if (mode == 2) {
            helpers.append("uniform highp int gaius_DrawId;\n");
        }
        helpers.append("flat out highp ivec3 gaius_vChunkPosition;\n")
                .append("flat out highp float gaius_vChunkVisibility;\n")
                .append("highp ivec3 gaius_chunkPosition() {\n")
                .append("    return gaius_BatchMode != 0 ? gaius_ChunkSections[").append(drawIndex)
                .append("].xyz : ivec3(").append(positionReference).append(");\n}\n")
                .append("highp float gaius_chunkVisibility() {\n")
                .append("    return gaius_BatchMode != 0 ? intBitsToFloat(gaius_ChunkSections[")
                .append(drawIndex).append("].w) : float(").append(visibilityReference)
                .append(");\n}\n");
        String body = replaceTokens(tail, positionReference, "gaius_chunkPosition()");
        body = replaceTokens(body, visibilityReference, "gaius_chunkVisibility()");
        int mainBody = findMainBody(body);
        if (mainBody < 0) {
            return head + tail;
        }
        body = body.substring(0, mainBody)
                + "\n    gaius_vChunkPosition = gaius_chunkPosition();"
                + "\n    gaius_vChunkVisibility = gaius_chunkVisibility();\n"
                + body.substring(mainBody);
        String rewritten = head + helpers + body;
        if (mode == 1) {
            rewritten = insertAfterVersion(rewritten, "#extension GL_ANGLE_multi_draw : require\n");
            if (rewritten == null) {
                return head + tail;
            }
        }
        return rewritten;
    }

    private static String rewriteFragment(
            String head, String tail, String positionReference, String visibilityReference,
            boolean usesPosition, boolean usesVisibility) {
        StringBuilder helpers = new StringBuilder(256);
        helpers.append("\nuniform highp int gaius_BatchMode;\n");
        String body = tail;
        if (usesPosition) {
            helpers.append("flat in highp ivec3 gaius_vChunkPosition;\n");
            body = replaceTokens(body, positionReference,
                    "(gaius_BatchMode != 0 ? gaius_vChunkPosition : ivec3("
                            + positionReference + "))");
        }
        if (usesVisibility) {
            helpers.append("flat in highp float gaius_vChunkVisibility;\n");
            body = replaceTokens(body, visibilityReference,
                    "(gaius_BatchMode != 0 ? gaius_vChunkVisibility : float("
                            + visibilityReference + "))");
        }
        return head + helpers + body;
    }

    /** Inserts {@code line} after the leading #version directive, or returns null. */
    private static String insertAfterVersion(String source, String line) {
        int start = 0;
        while (start < source.length() && Character.isWhitespace(source.charAt(start))) {
            start++;
        }
        if (!source.startsWith("#version", start)) {
            return null;
        }
        int lineEnd = source.indexOf('\n', start);
        if (lineEnd < 0) {
            return null;
        }
        return source.substring(0, lineEnd + 1) + line + source.substring(lineEnd + 1);
    }

    /** Index just after the opening brace of {@code void main()}, or -1. */
    private static int findMainBody(String source) {
        int from = 0;
        while (true) {
            int at = indexOfToken(source, "main", from);
            if (at < 0) {
                return -1;
            }
            from = at + 4;
            int before = at - 1;
            while (before >= 0 && Character.isWhitespace(source.charAt(before))) {
                before--;
            }
            if (before < 3 || !source.startsWith("void", before - 3)) {
                continue;
            }
            int open = skipSpace(source, at + 4);
            if (open >= source.length() || source.charAt(open) != '(') {
                continue;
            }
            int close = source.indexOf(')', open);
            if (close < 0) {
                return -1;
            }
            int brace = skipSpace(source, close + 1);
            if (brace < source.length() && source.charAt(brace) == '{') {
                return brace + 1;
            }
        }
    }

    static Block findChunkSectionBlock(String source) {
        Block typed = null;
        int typedMatches = 0;
        int from = 0;
        while (true) {
            int at = indexOfToken(source, "uniform", from);
            if (at < 0) {
                break;
            }
            from = at + 7;
            int nameStart = skipSpace(source, at + 7);
            int nameEnd = identifierEnd(source, nameStart);
            if (nameEnd == nameStart) {
                continue;
            }
            int open = skipSpace(source, nameEnd);
            if (open >= source.length() || source.charAt(open) != '{') {
                continue;
            }
            int close = source.indexOf('}', open);
            if (close < 0) {
                break;
            }
            int instanceStart = skipSpace(source, close + 1);
            int instanceEnd = identifierEnd(source, instanceStart);
            int semicolon = skipSpace(source, instanceEnd);
            if (semicolon >= source.length() || source.charAt(semicolon) != ';') {
                continue;
            }
            from = semicolon + 1;
            List<String[]> members = parseMembers(source.substring(open + 1, close));
            if (members == null) {
                continue;
            }
            String instance = source.substring(instanceStart, instanceEnd);
            String position = null;
            String visibility = null;
            for (String[] member : members) {
                if (member[0].equals("ivec3") && member[1].equals(POSITION_NAME)) {
                    position = member[1];
                } else if (member[0].equals("float") && member[1].equals(VISIBILITY_NAME)) {
                    visibility = member[1];
                }
            }
            if (position != null && visibility != null) {
                return new Block(instance, position, visibility, semicolon + 1);
            }
            if (members.size() == 2
                    && members.get(0)[0].equals("ivec3")
                    && members.get(1)[0].equals("float")) {
                typed = new Block(instance, members.get(0)[1], members.get(1)[1], semicolon + 1);
                typedMatches++;
            }
        }
        return typedMatches == 1 ? typed : null;
    }

    /** {type, name} per member, or null when a member is an array or otherwise unusual. */
    private static List<String[]> parseMembers(String body) {
        List<String[]> members = new ArrayList<>();
        for (String declaration : body.split(";")) {
            String trimmed = declaration.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.indexOf('[') >= 0 || trimmed.indexOf(',') >= 0) {
                return null;
            }
            String[] words = trimmed.split("\\s+");
            List<String> kept = new ArrayList<>();
            for (String word : words) {
                if (word.equals("highp") || word.equals("mediump") || word.equals("lowp")
                        || word.startsWith("layout")) {
                    continue;
                }
                kept.add(word);
            }
            if (kept.size() != 2) {
                return null;
            }
            members.add(new String[] {kept.get(0), kept.get(1)});
        }
        return members;
    }

    /** Replaces whole-token occurrences in one pass; inserted text is never rescanned. */
    static String replaceTokens(String text, String token, String replacement) {
        StringBuilder out = null;
        int copied = 0;
        int from = 0;
        while (true) {
            int at = indexOfToken(text, token, from);
            if (at < 0) {
                break;
            }
            if (out == null) {
                out = new StringBuilder(text.length() + 64);
            }
            out.append(text, copied, at).append(replacement);
            copied = at + token.length();
            from = copied;
        }
        if (out == null) {
            return text;
        }
        out.append(text, copied, text.length());
        return out.toString();
    }

    static int countTokens(String text, String token) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = indexOfToken(text, token, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + token.length();
        }
    }

    /** Next occurrence of {@code token} not preceded by an identifier character or a dot. */
    private static int indexOfToken(String text, String token, int from) {
        int at = text.indexOf(token, from);
        while (at >= 0) {
            boolean startOk = at == 0
                    || (!isIdentifierPart(text.charAt(at - 1)) && text.charAt(at - 1) != '.');
            int end = at + token.length();
            boolean endOk = end >= text.length() || !isIdentifierPart(text.charAt(end));
            if (startOk && endOk) {
                return at;
            }
            at = text.indexOf(token, at + 1);
        }
        return -1;
    }

    private static int skipSpace(String text, int index) {
        int at = index;
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
        return at;
    }

    private static int identifierEnd(String text, int start) {
        int at = start;
        while (at < text.length() && isIdentifierPart(text.charAt(at))) {
            at++;
        }
        return at;
    }

    private static boolean isIdentifierPart(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')
                || character == '_';
    }

    static final class Block {
        final String instance;
        final String positionMember;
        final String visibilityMember;
        final int end;

        Block(String instance, String positionMember, String visibilityMember, int end) {
            this.instance = instance;
            this.positionMember = positionMember;
            this.visibilityMember = visibilityMember;
            this.end = end;
        }

        String reference(String member) {
            return instance.isEmpty() ? member : instance + "." + member;
        }
    }
}
