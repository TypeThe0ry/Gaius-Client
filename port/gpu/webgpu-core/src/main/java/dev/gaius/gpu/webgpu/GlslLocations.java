package dev.gaius.gpu.webgpu;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit {@code layout(location = N)} qualifiers for the stage inputs and outputs of a blaze3d
 * GLSL stage: what shaderc's {@code auto_map_locations} option assigns, written into the source,
 * for shaderc builds without that option (the browser's {@code gaius-shaderc.wasm} predates it).
 *
 * <p>glslang's auto-mapping ({@code TDefaultIoResolverBase.resolveInOutLocation}, default I/O
 * mapper) numbers the stage inputs and the stage outputs separately, each from 0, in the order
 * the variables were declared (symbol id), every variable taking as many locations as its type
 * occupies; variables with a location of their own, built-in variables and blocks of built-ins
 * are left alone and do not advance the count. This class does the same on the source text: it
 * evaluates the preprocessor conditionals (so a declaration in an inactive {@code #ifdef} branch
 * takes no location, as after glslang's preprocessor), finds the global {@code in} and
 * {@code out} declarations and inserts the qualifier in front of the storage qualifier.</p>
 *
 * <p>GLSL before 4.10 allows {@code location} on vertex inputs and fragment outputs only, so when
 * a varying (vertex output or fragment input) gets one, {@code #extension
 * GL_ARB_separate_shader_objects : enable} follows the {@code #version} line, with a
 * {@code #line} directive that keeps every original line at its number.</p>
 *
 * <p>shaderc's output for the rewritten source then differs from the auto-mapped compilation of
 * the original only in debug instructions: the source text ({@code OpSource}), the extension's
 * {@code OpSourceExtension} and the missing {@code OpModuleProcessed "auto-map-locations"}; the
 * location literals are those auto-mapping assigns. {@link Result#restore(byte[])} puts those
 * three back, so the module is byte for byte what shaderc with {@code auto_map_locations} gives
 * (all 113 pipelines of 26.2 and of 1.21.11, before and after the front-end link).</p>
 *
 * <p>Declarations with several declarators ({@code in vec2 a, b;}) are split into one
 * declaration per variable on the same line. Struct-typed variables and interface blocks take
 * the locations of their members. A type whose size cannot be determined fails with
 * {@link IllegalArgumentException} (the pipeline is then unavailable, D11).</p>
 */
public final class GlslLocations {
    private static final String EXTENSION = "GL_ARB_separate_shader_objects";
    private static final int MAX_MACRO_DEPTH = 32;

    private GlslLocations() {
    }

    /**
     * The result of {@link #inject}: the rewritten source, the original, how many input and
     * output locations were assigned and whether the extension directive was added.
     */
    public record Result(String source, String original, int inputs, int outputs, boolean extension) {
        /**
         * shaderc's output for {@link #source()} (compiled without {@code auto_map_locations})
         * turned into the output for {@link #original()} with {@code auto_map_locations}: the
         * debug source text, the extension and the processing record of the option restored.
         */
        public byte[] restore(byte[] spirv) {
            return GlslLocations.restore(spirv, this);
        }
    }

    /**
     * Writes an explicit location on every global stage input and output of {@code source} that
     * has none, numbered like shaderc's {@code auto_map_locations}.
     *
     * @param stage the stage the source is compiled for (which declarations are varyings)
     */
    public static Result inject(String source, CoreShaderStage stage) {
        return new Injector(source, stage).run();
    }

    // ---------------------------------------------------------------------------------------
    // Debug information of the shaderc output

    private static final int OP_SOURCE_CONTINUED = 2;
    private static final int OP_SOURCE = 3;
    private static final int OP_SOURCE_EXTENSION = 4;
    private static final int OP_MODULE_PROCESSED = 330;
    /** glslang's {@code Builder::dumpSourceInstructions}: bytes of source text per instruction. */
    private static final int SOURCE_BYTES_PER_INSTRUCTION = 4 * (0xFFFF - 4) - 1;
    private static final String AUTO_MAP_LOCATIONS = "auto-map-locations";
    private static final String AUTO_MAP_BINDINGS = "auto-map-bindings";

    static byte[] restore(byte[] spirv, Result result) {
        if (spirv.length < 20 || (spirv.length & 3) != 0) {
            return spirv;
        }
        int[] words = new int[spirv.length / 4];
        for (int i = 0; i < words.length; i++) {
            words[i] = (spirv[4 * i] & 0xFF) | (spirv[4 * i + 1] & 0xFF) << 8 | (spirv[4 * i + 2] & 0xFF) << 16
                    | (spirv[4 * i + 3] & 0xFF) << 24;
        }
        if (words[0] != 0x07230203) {
            return spirv;
        }
        List<int[]> instructions = new ArrayList<>();
        for (int i = 5; i < words.length;) {
            int count = words[i] >>> 16;
            if (count == 0 || i + count > words.length) {
                return spirv;
            }
            int[] instruction = new int[count];
            System.arraycopy(words, i, instruction, 0, count);
            instructions.add(instruction);
            i += count;
        }
        List<int[]> out = new ArrayList<>(instructions.size() + 2);
        boolean sourceChanged = !result.source().equals(result.original());
        boolean dropExtension = result.extension() && !result.original().contains(EXTENSION);
        int lastProcessed = -1;
        int bindingsProcessed = -1;
        int firstClientProcessed = -1;
        boolean hasLocationsProcessed = false;
        for (int i = 0; i < instructions.size(); i++) {
            int[] instruction = instructions.get(i);
            int op = instruction[0] & 0xFFFF;
            if (op == OP_SOURCE && sourceChanged && instruction.length > 4) {
                // The text, continued by any OpSourceContinued that follow.
                StringBuilder text = new StringBuilder(decodeString(instruction, 4));
                int next = i + 1;
                while (next < instructions.size() && (instructions.get(next)[0] & 0xFFFF) == OP_SOURCE_CONTINUED) {
                    text.append(decodeString(instructions.get(next), 1));
                    next++;
                }
                if (text.toString().equals(result.source())) {
                    byte[] original = result.original().getBytes(StandardCharsets.UTF_8);
                    int at = 0;
                    do {
                        int length = Math.min(SOURCE_BYTES_PER_INSTRUCTION, original.length - at);
                        if (at == 0) {
                            out.add(stringInstruction(OP_SOURCE,
                                    new int[] { instruction[1], instruction[2], instruction[3] }, original, at, length));
                        } else {
                            out.add(stringInstruction(OP_SOURCE_CONTINUED, new int[0], original, at, length));
                        }
                        at += length;
                    } while (at < original.length);
                    sourceChanged = false;
                    i = next - 1;
                    continue;
                }
            }
            if (op == OP_SOURCE_EXTENSION && dropExtension && decodeString(instruction, 1).equals(EXTENSION)) {
                continue;
            }
            if (op == OP_MODULE_PROCESSED) {
                String process = decodeString(instruction, 1);
                hasLocationsProcessed |= process.equals(AUTO_MAP_LOCATIONS);
                if (process.equals(AUTO_MAP_BINDINGS)) {
                    bindingsProcessed = out.size();
                }
                if (firstClientProcessed < 0 && process.startsWith("client ")) {
                    firstClientProcessed = out.size();
                }
                lastProcessed = out.size();
            }
            out.add(instruction);
        }
        if (lastProcessed >= 0 && !hasLocationsProcessed) {
            // shaderc records the option right after auto-map-bindings, ahead of the client.
            int at = bindingsProcessed >= 0 ? bindingsProcessed + 1
                    : firstClientProcessed >= 0 ? firstClientProcessed : lastProcessed + 1;
            byte[] name = AUTO_MAP_LOCATIONS.getBytes(StandardCharsets.UTF_8);
            out.add(at, stringInstruction(OP_MODULE_PROCESSED, new int[0], name, 0, name.length));
        }
        int total = 5;
        for (int[] instruction : out) {
            total += instruction.length;
        }
        byte[] bytes = new byte[total * 4];
        int at = 0;
        for (int i = 0; i < 5; i++) {
            at = putWord(bytes, at, words[i]);
        }
        for (int[] instruction : out) {
            for (int word : instruction) {
                at = putWord(bytes, at, word);
            }
        }
        return bytes;
    }

    private static int putWord(byte[] bytes, int at, int word) {
        bytes[at] = (byte) word;
        bytes[at + 1] = (byte) (word >>> 8);
        bytes[at + 2] = (byte) (word >>> 16);
        bytes[at + 3] = (byte) (word >>> 24);
        return at + 4;
    }

    /** The NUL-terminated UTF-8 literal string starting at word {@code from}. */
    private static String decodeString(int[] instruction, int from) {
        byte[] bytes = new byte[Math.max(0, (instruction.length - from) * 4)];
        int length = 0;
        for (int i = from; i < instruction.length; i++) {
            int word = instruction[i];
            for (int b = 0; b < 4; b++) {
                byte value = (byte) (word >>> (8 * b));
                if (value == 0) {
                    return new String(bytes, 0, length, StandardCharsets.UTF_8);
                }
                bytes[length++] = value;
            }
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    /** An instruction with leading operand words and a string literal (NUL-terminated, padded). */
    private static int[] stringInstruction(int op, int[] operands, byte[] text, int offset, int length) {
        int count = 1 + operands.length + length / 4 + 1;
        int[] instruction = new int[count];
        instruction[0] = count << 16 | op;
        System.arraycopy(operands, 0, instruction, 1, operands.length);
        for (int i = 0; i < length; i++) {
            instruction[1 + operands.length + i / 4] |= (text[offset + i] & 0xFF) << (8 * (i % 4));
        }
        return instruction;
    }

    // ---------------------------------------------------------------------------------------
    // Source scanning

    /** One token of the active, comment-free code: its text and its offset in the source. */
    private record Token(String text, int start, int end) {
        boolean is(String value) {
            return this.text.equals(value);
        }
    }

    private static final class Injector {
        private final String source;
        private final CoreShaderStage stage;
        /** The source with comments, preprocessor lines and inactive code blanked out. */
        private final char[] code;
        private final Map<String, String> macros = new HashMap<>();
        private final Map<String, Integer> structSizes = new HashMap<>();
        /** Insertions, sorted by offset (offset, text). */
        private final List<int[]> insertAt = new ArrayList<>();
        private final List<String> insertText = new ArrayList<>();
        private int versionLineEnd = -1;
        private int versionLineNumber = 0;
        private int version = 110;
        private boolean es;
        private int nextInput;
        private int nextOutput;
        private boolean varyingLocated;

        Injector(String source, CoreShaderStage stage) {
            this.source = source;
            this.stage = stage;
            this.code = new char[source.length()];
        }

        Result run() {
            this.mask();
            List<Token> tokens = tokenize(this.code);
            this.declarations(tokens);
            // Locations on varyings need GLSL 4.10, on vertex inputs and fragment outputs 3.30; the
            // extension provides both.
            boolean located = this.nextInput > 0 || this.nextOutput > 0;
            boolean extension = !this.es && this.versionLineEnd >= 0
                    && (this.varyingLocated && this.version < 410 || located && this.version < 330);
            if (extension) {
                // The line after #version keeps its number: GLSL 3.30+ and cpp-style #line set
                // the number of the line that follows the directive.
                this.insert(this.versionLineEnd, "#extension " + EXTENSION + " : enable\n#line "
                        + (this.versionLineNumber + 1) + "\n");
            }
            if (this.insertAt.isEmpty()) {
                return new Result(this.source, this.source, 0, 0, false);
            }
            StringBuilder out = new StringBuilder(this.source.length() + 32 * this.insertAt.size());
            int[] order = new int[this.insertAt.size()];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            // Stable sort by offset (insertion sort: a handful of entries).
            for (int i = 1; i < order.length; i++) {
                int value = order[i];
                int j = i - 1;
                while (j >= 0 && this.insertAt.get(order[j])[0] > this.insertAt.get(value)[0]) {
                    order[j + 1] = order[j];
                    j--;
                }
                order[j + 1] = value;
            }
            int copied = 0;
            for (int index : order) {
                int offset = this.insertAt.get(index)[0];
                int replaced = this.insertAt.get(index)[1];
                out.append(this.source, copied, offset).append(this.insertText.get(index));
                copied = offset + replaced;
            }
            out.append(this.source, copied, this.source.length());
            return new Result(out.toString(), this.source, this.nextInput, this.nextOutput, extension);
        }

        private void insert(int offset, String text) {
            this.replace(offset, 0, text);
        }

        private void replace(int offset, int length, String text) {
            this.insertAt.add(new int[] { offset, length });
            this.insertText.add(text);
        }

        // ---- comments, preprocessor directives, conditionals ----------------------------------

        /**
         * Fills {@link #code}: active code as is, everything else (comments, directives, inactive
         * branches) as blanks, so offsets stay those of the source. Tracks #define/#undef for the
         * conditionals and array sizes.
         */
        private void mask() {
            String s = this.source;
            int length = s.length();
            // First pass: blank comments (keeping newlines), remember which offsets are comments.
            char[] plain = s.toCharArray();
            for (int i = 0; i < length; i++) {
                char c = plain[i];
                if (c == '/' && i + 1 < length && plain[i + 1] == '/') {
                    while (i < length && plain[i] != '\n') {
                        if (plain[i] == '\\' && i + 1 < length && plain[i + 1] == '\n') {
                            plain[i] = ' ';
                            i += 2;
                            continue;
                        }
                        plain[i] = ' ';
                        i++;
                    }
                } else if (c == '/' && i + 1 < length && plain[i + 1] == '*') {
                    plain[i] = ' ';
                    plain[i + 1] = ' ';
                    i += 2;
                    while (i < length && !(plain[i] == '*' && i + 1 < length && plain[i + 1] == '/')) {
                        if (plain[i] != '\n') {
                            plain[i] = ' ';
                        }
                        i++;
                    }
                    if (i < length) {
                        plain[i] = ' ';
                        plain[i + 1] = ' ';
                        i++;
                    }
                }
            }
            // Second pass, line by line: directives and conditionals.
            List<boolean[]> stack = new ArrayList<>(); // {active, taken}
            boolean active = true;
            int lineNumber = 1;
            int lineStart = 0;
            while (lineStart <= length) {
                int lineEnd = lineStart;
                // A directive continues over backslash-newline.
                while (lineEnd < length && plain[lineEnd] != '\n') {
                    lineEnd++;
                }
                int first = lineStart;
                while (first < lineEnd && (plain[first] == ' ' || plain[first] == '\t' || plain[first] == '\r')) {
                    first++;
                }
                boolean directive = first < lineEnd && plain[first] == '#';
                int logicalEnd = lineEnd;
                int extraLines = 0;
                if (directive) {
                    while (logicalEnd < length && logicalEnd > lineStart && trailingBackslash(plain, lineStart, logicalEnd)) {
                        int next = logicalEnd + 1;
                        while (next < length && plain[next] != '\n') {
                            next++;
                        }
                        logicalEnd = next;
                        extraLines++;
                    }
                    String text = new String(plain, first + 1, logicalEnd - first - 1).replace("\r", "")
                            .replace("\\\n", " ").trim();
                    active = this.directive(text, active, stack, lineStart, logicalEnd, lineNumber);
                    for (int i = lineStart; i < logicalEnd; i++) {
                        this.code[i] = plain[i] == '\n' ? '\n' : ' ';
                    }
                } else {
                    for (int i = lineStart; i < lineEnd; i++) {
                        this.code[i] = active ? plain[i] : ' ';
                    }
                }
                if (logicalEnd < length) {
                    this.code[logicalEnd] = '\n';
                }
                lineNumber += 1 + extraLines;
                lineStart = logicalEnd + 1;
            }
            if (!stack.isEmpty()) {
                throw new IllegalArgumentException("unterminated #if");
            }
        }

        private static boolean trailingBackslash(char[] text, int start, int end) {
            int i = end - 1;
            while (i >= start && (text[i] == '\r' || text[i] == ' ' || text[i] == '\t')) {
                i--;
            }
            return i >= start && text[i] == '\\';
        }

        /** Handles one directive; returns whether the following lines are active. */
        private boolean directive(String text, boolean active, List<boolean[]> stack, int start, int end, int line) {
            int space = 0;
            while (space < text.length() && isIdentifierPart(text.charAt(space))) {
                space++;
            }
            String name = text.substring(0, space);
            String rest = text.substring(space).trim();
            switch (name) {
                case "if", "ifdef", "ifndef" -> {
                    boolean value = false;
                    if (active) {
                        value = switch (name) {
                            case "ifdef" -> this.macros.containsKey(firstIdentifier(rest));
                            case "ifndef" -> !this.macros.containsKey(firstIdentifier(rest));
                            default -> this.evaluate(rest) != 0;
                        };
                    }
                    stack.add(new boolean[] { active, value });
                    return active && value;
                }
                case "elif" -> {
                    boolean[] frame = top(stack, name);
                    boolean value = frame[0] && !frame[1] && this.evaluate(rest) != 0;
                    frame[1] |= value;
                    return value;
                }
                case "else" -> {
                    boolean[] frame = top(stack, name);
                    boolean value = frame[0] && !frame[1];
                    frame[1] = true;
                    return value;
                }
                case "endif" -> {
                    boolean[] frame = top(stack, name);
                    stack.remove(stack.size() - 1);
                    return frame[0];
                }
                default -> {
                }
            }
            if (!active) {
                return false;
            }
            switch (name) {
                case "version" -> {
                    if (this.versionLineEnd < 0) {
                        String[] parts = rest.split("\\s+");
                        try {
                            this.version = Integer.parseInt(parts[0]);
                        } catch (NumberFormatException e) {
                            this.version = 110;
                        }
                        this.es = parts.length > 1 && parts[1].equals("es");
                        this.versionLineEnd = Math.min(end + 1, this.source.length());
                        this.versionLineNumber = line;
                    }
                }
                case "define" -> {
                    String macro = firstIdentifier(rest);
                    if (!macro.isEmpty()) {
                        String body = rest.substring(macro.length());
                        if (body.startsWith("(")) {
                            // A function-like macro: defined, but no value in expressions.
                            this.macros.put(macro, "");
                        } else {
                            this.macros.put(macro, body.trim());
                        }
                    }
                }
                case "undef" -> this.macros.remove(firstIdentifier(rest));
                default -> {
                }
            }
            return true;
        }

        private static boolean[] top(List<boolean[]> stack, String name) {
            if (stack.isEmpty()) {
                throw new IllegalArgumentException("#" + name + " without #if");
            }
            return stack.get(stack.size() - 1);
        }

        private static String firstIdentifier(String text) {
            int i = 0;
            while (i < text.length() && isIdentifierPart(text.charAt(i))) {
                i++;
            }
            return text.substring(0, i);
        }

        // ---- #if expressions -----------------------------------------------------------------

        private long evaluate(String expression) {
            List<String> tokens = expressionTokens(expression, 0);
            ExpressionParser parser = new ExpressionParser(tokens);
            long value = parser.conditional();
            if (parser.position != tokens.size()) {
                throw new IllegalArgumentException("cannot evaluate #if " + expression);
            }
            return value;
        }

        /** Tokens of an expression with macros expanded and {@code defined} resolved. */
        private List<String> expressionTokens(String expression, int depth) {
            if (depth > MAX_MACRO_DEPTH) {
                throw new IllegalArgumentException("macro expansion too deep in " + expression);
            }
            List<String> raw = new ArrayList<>();
            for (Token token : tokenize(expression.toCharArray())) {
                raw.add(token.text());
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < raw.size(); i++) {
                String token = raw.get(i);
                if (token.equals("defined")) {
                    String name;
                    if (i + 1 < raw.size() && raw.get(i + 1).equals("(")) {
                        name = i + 2 < raw.size() ? raw.get(i + 2) : "";
                        i += 3;
                    } else {
                        name = i + 1 < raw.size() ? raw.get(i + 1) : "";
                        i += 1;
                    }
                    out.add(this.macros.containsKey(name) ? "1" : "0");
                } else if (isIdentifierStart(token.charAt(0))) {
                    String body = this.macros.get(token);
                    if (body == null || body.isEmpty()) {
                        out.add("0");
                    } else {
                        out.add("(");
                        out.addAll(this.expressionTokens(body, depth + 1));
                        out.add(")");
                    }
                } else {
                    out.add(token);
                }
            }
            return out;
        }

        // ---- declarations ---------------------------------------------------------------------

        /**
         * Walks the global statements of the active code: struct definitions are recorded,
         * {@code in}/{@code out} declarations get their locations.
         */
        private void declarations(List<Token> tokens) {
            int depth = 0;
            int statementStart = 0;
            for (int i = 0; i < tokens.size(); i++) {
                Token token = tokens.get(i);
                if (token.is("{") || token.is("(") || token.is("[")) {
                    if (token.is("{") && depth == 0 && i > 0 && tokens.get(i - 1).is(")")) {
                        // A function body: skip to its closing brace.
                        int close = matching(tokens, i);
                        i = close;
                        statementStart = close + 1;
                        continue;
                    }
                    depth++;
                } else if (token.is("}") || token.is(")") || token.is("]")) {
                    depth--;
                } else if (token.is(";") && depth == 0) {
                    this.statement(tokens.subList(statementStart, i), tokens.get(i));
                    statementStart = i + 1;
                }
            }
        }

        private static int matching(List<Token> tokens, int open) {
            int depth = 0;
            for (int i = open; i < tokens.size(); i++) {
                String text = tokens.get(i).text();
                if (text.equals("{")) {
                    depth++;
                } else if (text.equals("}")) {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
            throw new IllegalArgumentException("unbalanced braces");
        }

        private void statement(List<Token> statement, Token semicolon) {
            if (statement.isEmpty()) {
                return;
            }
            // Qualifiers: layout(...), interpolation, auxiliary, precision, invariant, in/out.
            int i = 0;
            Token storage = null;
            int layoutOpen = -1;
            int layoutClose = -1;
            boolean located = false;
            while (i < statement.size()) {
                Token token = statement.get(i);
                String text = token.text();
                if (text.equals("layout") && i + 1 < statement.size() && statement.get(i + 1).is("(")) {
                    int close = i + 1;
                    int depth = 0;
                    for (; close < statement.size(); close++) {
                        if (statement.get(close).is("(")) {
                            depth++;
                        } else if (statement.get(close).is(")")) {
                            depth--;
                            if (depth == 0) {
                                break;
                            }
                        }
                    }
                    for (int k = i + 2; k < close; k++) {
                        if (statement.get(k).is("location")) {
                            located = true;
                        }
                    }
                    layoutOpen = i + 1;
                    layoutClose = close;
                    i = close + 1;
                    continue;
                }
                if (text.equals("in") || text.equals("out")) {
                    storage = token;
                    i++;
                    continue;
                }
                if (QUALIFIERS.contains(text)) {
                    i++;
                    continue;
                }
                break;
            }
            if (i >= statement.size()) {
                return;
            }
            if (statement.get(i).is("struct")) {
                this.structDefinition(statement, i);
                return;
            }
            if (storage == null) {
                return;
            }
            boolean input = storage.is("in");
            boolean varying = (this.stage == CoreShaderStage.VERTEX) != input;
            Token typeToken = statement.get(i);
            int size;
            List<int[]> declarators = new ArrayList<>(); // {nameIndex, endIndex (exclusive)}
            if (i + 1 < statement.size() && statement.get(i + 1).is("{")) {
                // An interface block: name { members } [instance [array]].
                int close = closing(statement, i + 1, "{", "}");
                List<Token> members = statement.subList(i + 2, close);
                if (members.size() > 0 && members.get(0).text().startsWith("gl_")
                        || typeToken.text().startsWith("gl_") || containsBuiltin(members)) {
                    return;
                }
                size = this.membersSize(members);
                int count = 1;
                if (close + 1 < statement.size()) {
                    count = this.arrayCount(statement, close + 2, statement.size());
                }
                this.assign(statement, storage, layoutOpen, layoutClose, located, input, varying, size * count);
                return;
            }
            int typeSize = this.typeSize(typeToken.text());
            int typeArray = 1;
            int at = i + 1;
            if (at < statement.size() && statement.get(at).is("[")) {
                int close = closing(statement, at, "[", "]");
                typeArray = this.arrayCount(statement, at, close + 1);
                at = close + 1;
            }
            // Declarators: name [array] (, name [array])*
            while (at < statement.size()) {
                int nameIndex = at;
                Token name = statement.get(at);
                if (!isIdentifierStart(name.text().charAt(0))) {
                    throw new IllegalArgumentException("unexpected '" + name.text() + "' in a declaration");
                }
                at++;
                int end = at;
                while (end < statement.size() && !statement.get(end).is(",")) {
                    if (statement.get(end).is("[")) {
                        end = closing(statement, end, "[", "]");
                    }
                    end++;
                }
                declarators.add(new int[] { nameIndex, end });
                at = end + 1;
            }
            if (declarators.isEmpty()) {
                return;
            }
            String firstName = statement.get(declarators.get(0)[0]).text();
            if (firstName.startsWith("gl_")) {
                return;
            }
            if (declarators.size() == 1) {
                int[] d = declarators.get(0);
                int count = d[1] > d[0] + 1 ? this.arrayCount(statement, d[0] + 1, d[1]) : 1;
                this.assign(statement, storage, layoutOpen, layoutClose, located, input, varying,
                        typeSize * typeArray * count);
                return;
            }
            if (located) {
                throw new IllegalArgumentException("a location qualifier on several declarators of " + firstName);
            }
            // Several declarators: one declaration per variable, on the same line.
            String qualifiers = this.source.substring(statement.get(0).start(), storage.start());
            String type = this.source.substring(storage.end(), statement.get(declarators.get(0)[0]).start()).trim();
            for (int k = 0; k < declarators.size(); k++) {
                int[] d = declarators.get(k);
                int count = d[1] > d[0] + 1 ? this.arrayCount(statement, d[0] + 1, d[1]) : 1;
                int location = this.next(input, varying, typeSize * typeArray * count);
                if (k == 0) {
                    this.locate(statement, storage, layoutOpen, layoutClose, location);
                } else {
                    // Replace the comma before this declarator with "; <qualifiers> layout(...) in <type>".
                    Token comma = statement.get(d[0] - 1);
                    String layout = layoutOpen >= 0 ? "" : "layout(location = " + location + ") ";
                    String text = qualifiers;
                    if (layoutOpen >= 0) {
                        int open = statement.get(layoutOpen).end();
                        text = this.source.substring(statement.get(0).start(), open) + "location = " + location
                                + ", " + this.source.substring(open, storage.start());
                    }
                    this.replace(comma.start(), comma.end() - comma.start(),
                            "; " + text + layout + storage.text() + " " + type);
                }
            }
        }

        private static boolean containsBuiltin(List<Token> members) {
            for (Token token : members) {
                if (token.text().startsWith("gl_")) {
                    return true;
                }
            }
            return false;
        }

        private void assign(List<Token> statement, Token storage, int layoutOpen, int layoutClose, boolean located,
                boolean input, boolean varying, int size) {
            if (located) {
                return; // auto-mapping leaves it alone and does not advance
            }
            this.locate(statement, storage, layoutOpen, layoutClose, this.next(input, varying, size));
        }

        private int next(boolean input, boolean varying, int size) {
            int location;
            if (input) {
                location = this.nextInput;
                this.nextInput += size;
            } else {
                location = this.nextOutput;
                this.nextOutput += size;
            }
            if (varying) {
                this.varyingLocated = true;
            }
            return location;
        }

        private void locate(List<Token> statement, Token storage, int layoutOpen, int layoutClose, int location) {
            if (layoutOpen >= 0) {
                // Merge into the declaration's own layout(...).
                Token open = statement.get(layoutOpen);
                boolean empty = layoutClose == layoutOpen + 1;
                this.insert(open.end(), "location = " + location + (empty ? "" : ", "));
            } else {
                this.insert(storage.start(), "layout(location = " + location + ") ");
            }
        }

        private void structDefinition(List<Token> statement, int structIndex) {
            if (structIndex + 2 >= statement.size() || !statement.get(structIndex + 2).is("{")) {
                return;
            }
            String name = statement.get(structIndex + 1).text();
            int close = closing(statement, structIndex + 2, "{", "}");
            try {
                this.structSizes.put(name, this.membersSize(statement.subList(structIndex + 3, close)));
            } catch (IllegalArgumentException e) {
                // A struct that can never be a stage interface type (samplers, unsized members).
                this.structSizes.put(name, -1);
            }
        }

        /** Locations of a struct's or block's members ({@code type name [array], ...;} each). */
        private int membersSize(List<Token> members) {
            int total = 0;
            int start = 0;
            for (int i = 0; i <= members.size(); i++) {
                if (i < members.size() && !members.get(i).is(";")) {
                    continue;
                }
                List<Token> member = members.subList(start, i);
                start = i + 1;
                int t = 0;
                while (t < member.size() && (QUALIFIERS.contains(member.get(t).text()) || member.get(t).is("layout"))) {
                    if (member.get(t).is("layout")) {
                        t = closing(member, t + 1, "(", ")");
                    }
                    t++;
                }
                if (t >= member.size()) {
                    continue;
                }
                int size = this.typeSize(member.get(t).text());
                int at = t + 1;
                if (at < member.size() && member.get(at).is("[")) {
                    int close = closing(member, at, "[", "]");
                    size *= this.arrayCount(member, at, close + 1);
                    at = close + 1;
                }
                while (at < member.size()) {
                    int end = at + 1;
                    while (end < member.size() && !member.get(end).is(",")) {
                        if (member.get(end).is("[")) {
                            end = closing(member, end, "[", "]");
                        }
                        end++;
                    }
                    int count = end > at + 1 ? this.arrayCount(member, at + 1, end) : 1;
                    total += size * count;
                    at = end + 1;
                }
            }
            return total;
        }

        /** Product of the array dimensions {@code [a][b]...} in {@code tokens[from, to)}. */
        private int arrayCount(List<Token> tokens, int from, int to) {
            int count = 1;
            int i = from;
            while (i < to) {
                if (!tokens.get(i).is("[")) {
                    throw new IllegalArgumentException("unexpected '" + tokens.get(i).text() + "' after a declarator");
                }
                int close = closing(tokens, i, "[", "]");
                StringBuilder expression = new StringBuilder();
                for (int k = i + 1; k < close; k++) {
                    expression.append(tokens.get(k).text()).append(' ');
                }
                if (close == i + 1) {
                    // An unsized array (arrayed stage interfaces only): the element.
                    i = close + 1;
                    continue;
                }
                long value = this.evaluate(expression.toString());
                if (value <= 0 || value > 4096) {
                    throw new IllegalArgumentException("array size " + value + " of a stage interface variable");
                }
                count *= (int) value;
                i = close + 1;
            }
            return count;
        }

        private static int closing(List<Token> tokens, int open, String opening, String closingText) {
            int depth = 0;
            for (int i = open; i < tokens.size(); i++) {
                if (tokens.get(i).is(opening)) {
                    depth++;
                } else if (tokens.get(i).is(closingText)) {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
            throw new IllegalArgumentException("unbalanced '" + opening + "'");
        }

        /** Locations a variable of the type occupies (glslang's computeTypeLocationSize). */
        private int typeSize(String name) {
            // A type spelled through an object-like macro (#define VEC vec4).
            String type = name;
            for (int depth = 0; depth < MAX_MACRO_DEPTH; depth++) {
                String alias = this.macros.get(type);
                if (alias == null || alias.isEmpty() || !isIdentifierStart(alias.charAt(0))
                        || !firstIdentifier(alias).equals(alias)) {
                    break;
                }
                type = alias;
            }
            Integer struct = this.structSizes.get(type);
            if (struct != null) {
                if (struct < 0) {
                    throw new IllegalArgumentException("struct " + type + " cannot be a stage interface type");
                }
                return struct;
            }
            switch (type) {
                case "float", "int", "uint", "bool", "double", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4",
                        "uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4", "dvec2" -> {
                    return 1;
                }
                case "dvec3", "dvec4" -> {
                    return 2;
                }
                default -> {
                }
            }
            boolean dmat = type.startsWith("dmat");
            if (type.startsWith("mat") || dmat) {
                String dims = type.substring(dmat ? 4 : 3);
                int columns;
                int rows;
                if (dims.length() == 1) {
                    columns = dims.charAt(0) - '0';
                    rows = columns;
                } else if (dims.length() == 3 && dims.charAt(1) == 'x') {
                    columns = dims.charAt(0) - '0';
                    rows = dims.charAt(2) - '0';
                } else {
                    throw new IllegalArgumentException("unknown type " + type);
                }
                if (columns < 2 || columns > 4 || rows < 2 || rows > 4) {
                    throw new IllegalArgumentException("unknown type " + type);
                }
                return columns * (dmat && rows > 2 ? 2 : 1);
            }
            throw new IllegalArgumentException("cannot size the stage interface type " + type);
        }
    }

    private static final java.util.Set<String> QUALIFIERS = java.util.Set.of("flat", "smooth", "noperspective",
            "centroid", "sample", "patch", "invariant", "precise", "highp", "mediump", "lowp", "const");

    // ---------------------------------------------------------------------------------------
    // Tokens

    private static boolean isIdentifierStart(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || (c >= '0' && c <= '9');
    }

    private static final String[] PUNCTUATORS = { "<<", ">>", "<=", ">=", "==", "!=", "&&", "||", "##" };

    /** Identifiers, numbers and punctuators of {@code text} (blanks separate). */
    private static List<Token> tokenize(char[] text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int length = text.length;
        while (i < length) {
            char c = text[i];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                i++;
                continue;
            }
            int start = i;
            if (isIdentifierStart(c)) {
                while (i < length && isIdentifierPart(text[i])) {
                    i++;
                }
            } else if (c >= '0' && c <= '9' || (c == '.' && i + 1 < length && text[i + 1] >= '0' && text[i + 1] <= '9')) {
                while (i < length && (isIdentifierPart(text[i]) || text[i] == '.')) {
                    i++;
                }
            } else {
                i++;
                for (String punctuator : PUNCTUATORS) {
                    if (c == punctuator.charAt(0) && i < length && text[i] == punctuator.charAt(1)) {
                        i++;
                        break;
                    }
                }
            }
            tokens.add(new Token(new String(text, start, i - start), start, i));
        }
        return tokens;
    }

    /** C preprocessor integer expressions (precedence climbing over the expanded tokens). */
    private static final class ExpressionParser {
        private final List<String> tokens;
        int position;

        ExpressionParser(List<String> tokens) {
            this.tokens = tokens;
        }

        private String peek() {
            return this.position < this.tokens.size() ? this.tokens.get(this.position) : "";
        }

        private boolean accept(String token) {
            if (this.peek().equals(token)) {
                this.position++;
                return true;
            }
            return false;
        }

        long conditional() {
            long condition = this.binary(0);
            if (this.accept("?")) {
                long a = this.conditional();
                if (!this.accept(":")) {
                    throw new IllegalArgumentException("expected ':' in #if");
                }
                long b = this.conditional();
                return condition != 0 ? a : b;
            }
            return condition;
        }

        private static final String[][] LEVELS = {
            { "||" }, { "&&" }, { "|" }, { "^" }, { "&" }, { "==", "!=" }, { "<", ">", "<=", ">=" },
            { "<<", ">>" }, { "+", "-" }, { "*", "/", "%" }
        };

        private long binary(int level) {
            if (level == LEVELS.length) {
                return this.unary();
            }
            long left = this.binary(level + 1);
            while (true) {
                String op = null;
                for (String candidate : LEVELS[level]) {
                    if (this.peek().equals(candidate)) {
                        op = candidate;
                    }
                }
                if (op == null) {
                    return left;
                }
                this.position++;
                long right = this.binary(level + 1);
                left = switch (op) {
                    case "||" -> (left != 0 || right != 0) ? 1 : 0;
                    case "&&" -> (left != 0 && right != 0) ? 1 : 0;
                    case "|" -> left | right;
                    case "^" -> left ^ right;
                    case "&" -> left & right;
                    case "==" -> left == right ? 1 : 0;
                    case "!=" -> left != right ? 1 : 0;
                    case "<" -> left < right ? 1 : 0;
                    case ">" -> left > right ? 1 : 0;
                    case "<=" -> left <= right ? 1 : 0;
                    case ">=" -> left >= right ? 1 : 0;
                    case "<<" -> left << right;
                    case ">>" -> left >> right;
                    case "+" -> left + right;
                    case "-" -> left - right;
                    case "*" -> left * right;
                    case "/" -> {
                        if (right == 0) {
                            throw new IllegalArgumentException("division by zero in #if");
                        }
                        yield left / right;
                    }
                    default -> {
                        if (right == 0) {
                            throw new IllegalArgumentException("division by zero in #if");
                        }
                        yield left % right;
                    }
                };
            }
        }

        private long unary() {
            if (this.accept("!")) {
                return this.unary() == 0 ? 1 : 0;
            }
            if (this.accept("-")) {
                return -this.unary();
            }
            if (this.accept("+")) {
                return this.unary();
            }
            if (this.accept("~")) {
                return ~this.unary();
            }
            if (this.accept("(")) {
                long value = this.conditional();
                if (!this.accept(")")) {
                    throw new IllegalArgumentException("expected ')' in #if");
                }
                return value;
            }
            String token = this.peek();
            if (token.isEmpty()) {
                throw new IllegalArgumentException("unexpected end of #if expression");
            }
            this.position++;
            return parseInteger(token);
        }

        private static long parseInteger(String token) {
            String digits = token;
            while (!digits.isEmpty() && "uUlL".indexOf(digits.charAt(digits.length() - 1)) >= 0) {
                digits = digits.substring(0, digits.length() - 1);
            }
            try {
                if (digits.startsWith("0x") || digits.startsWith("0X")) {
                    return Long.parseLong(digits.substring(2), 16);
                }
                if (digits.length() > 1 && digits.charAt(0) == '0') {
                    return Long.parseLong(digits.substring(1), 8);
                }
                return Long.parseLong(digits);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not an integer in #if: " + token);
            }
        }
    }
}
