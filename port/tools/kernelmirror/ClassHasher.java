import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

public final class ClassHasher {
    private record Entry(int tag, Object value) {}
    private record Instruction(int offset, int opcode, int[] operands) {}
    private record Method(int access, String name, String descriptor, byte[] code) {}

    private final Entry[] constants;
    private final List<int[]> bootstraps = new ArrayList<>();
    private final List<Method> methods = new ArrayList<>();

    private ClassHasher(byte[] bytes) throws IOException {
        DataInputStream input = stream(bytes);
        if (input.readInt() != 0xcafebabe) throw new IOException("Invalid class header");
        input.readUnsignedShort();
        input.readUnsignedShort();
        constants = new Entry[input.readUnsignedShort()];
        for (int index = 1; index < constants.length; index++) {
            int tag = input.readUnsignedByte();
            Object value = switch (tag) {
                case 1 -> input.readUTF();
                case 3, 4 -> Integer.toHexString(input.readInt());
                case 5, 6 -> Long.toHexString(input.readLong());
                case 7, 8, 16, 19, 20 -> new int[]{input.readUnsignedShort()};
                case 9, 10, 11, 12, 17, 18 -> new int[]{input.readUnsignedShort(), input.readUnsignedShort()};
                case 15 -> new int[]{input.readUnsignedByte(), input.readUnsignedShort()};
                default -> throw new IOException("Unsupported constant tag " + tag);
            };
            constants[index] = new Entry(tag, value);
            if (tag == 5 || tag == 6) index++;
        }
        input.readUnsignedShort();
        input.readUnsignedShort();
        input.readUnsignedShort();
        int interfaces = input.readUnsignedShort();
        for (int index = 0; index < interfaces; index++) input.readUnsignedShort();
        int fields = input.readUnsignedShort();
        for (int index = 0; index < fields; index++) {
            input.readUnsignedShort();
            input.readUnsignedShort();
            input.readUnsignedShort();
            attributes(input);
        }
        int methodCount = input.readUnsignedShort();
        for (int index = 0; index < methodCount; index++) {
            int access = input.readUnsignedShort();
            String name = utf(input.readUnsignedShort());
            String descriptor = utf(input.readUnsignedShort());
            methods.add(new Method(access, name, descriptor, attributes(input).get("Code")));
        }
        byte[] bootstrapBytes = attributes(input).get("BootstrapMethods");
        if (bootstrapBytes != null) {
            DataInputStream bootstrap = stream(bootstrapBytes);
            int count = bootstrap.readUnsignedShort();
            for (int index = 0; index < count; index++) {
                int reference = bootstrap.readUnsignedShort();
                int[] arguments = new int[bootstrap.readUnsignedShort() + 1];
                arguments[0] = reference;
                for (int argument = 1; argument < arguments.length; argument++) {
                    arguments[argument] = bootstrap.readUnsignedShort();
                }
                bootstraps.add(arguments);
            }
        }
    }

    private static DataInputStream stream(byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }

    private String utf(int index) throws IOException {
        if (index <= 0 || index >= constants.length || constants[index] == null
                || constants[index].tag != 1) throw new IOException("Invalid UTF constant " + index);
        return (String) constants[index].value;
    }

    private Map<String, byte[]> attributes(DataInputStream input) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        int count = input.readUnsignedShort();
        for (int index = 0; index < count; index++) {
            String name = utf(input.readUnsignedShort());
            int length = input.readInt();
            if (length < 0 || length > input.available()) throw new IOException("Invalid attribute length");
            result.put(name, input.readNBytes(length));
        }
        return result;
    }

    private void constant(DataOutputStream output, int index, List<Integer> visiting) throws IOException {
        if (index <= 0 || index >= constants.length || constants[index] == null) {
            throw new IOException("Invalid constant " + index);
        }
        if (visiting.contains(index)) throw new IOException("Cyclic constant/bootstrap reference");
        visiting.add(index);
        Entry entry = constants[index];
        output.writeInt(entry.tag);
        if (entry.value instanceof String text) {
            text(output, text);
        } else {
            int[] references = (int[]) entry.value;
            if (entry.tag == 15) {
                output.writeInt(references[0]);
                constant(output, references[1], visiting);
            } else if (entry.tag == 17 || entry.tag == 18) {
                constant(output, references[1], visiting);
                if (references[0] >= bootstraps.size()) throw new IOException("Missing bootstrap method");
                int[] bootstrap = bootstraps.get(references[0]);
                output.writeInt(bootstrap.length);
                for (int reference : bootstrap) constant(output, reference, visiting);
            } else {
                output.writeInt(references.length);
                for (int reference : references) constant(output, reference, visiting);
            }
        }
        visiting.remove(visiting.size() - 1);
    }

    private static void text(DataOutputStream output, String text) throws IOException {
        output.writeInt(text.length());
        for (int index = 0; index < text.length(); index++) output.writeChar(text.charAt(index));
    }

    private static boolean branch(int opcode) {
        return opcode >= 153 && opcode <= 168 || opcode == 198 || opcode == 199
                || opcode == 200 || opcode == 201;
    }

    private static boolean poolOperand(int opcode) {
        return opcode == 18 || opcode == 19 || opcode == 20 || opcode >= 178 && opcode <= 187
                || opcode == 189 || opcode == 192 || opcode == 193 || opcode == 197;
    }

    private static List<Instruction> decode(byte[] code) throws IOException {
        List<Instruction> result = new ArrayList<>();
        DataInputStream input = stream(code);
        while (input.available() > 0) {
            int offset = code.length - input.available();
            int opcode = input.readUnsignedByte();
            int[] operands;
            if (opcode == 170 || opcode == 171) {
                while ((code.length - input.available()) % 4 != 0) input.readUnsignedByte();
                int fallback = offset + input.readInt();
                if (opcode == 170) {
                    int low = input.readInt();
                    int high = input.readInt();
                    long count = (long) high - low + 1;
                    if (count < 0 || count > input.available() / 4) throw new IOException("Invalid tableswitch");
                    operands = new int[(int) count + 2];
                    operands[0] = fallback;
                    operands[1] = low;
                    for (int index = 2; index < operands.length; index++) operands[index] = offset + input.readInt();
                } else {
                    int count = input.readInt();
                    if (count < 0 || count > input.available() / 8) throw new IOException("Invalid lookupswitch");
                    operands = new int[count * 2 + 1];
                    operands[0] = fallback;
                    for (int index = 1; index < operands.length; index += 2) {
                        operands[index] = input.readInt();
                        operands[index + 1] = offset + input.readInt();
                    }
                }
            } else if (opcode == 196) {
                opcode = input.readUnsignedByte();
                if (!(opcode >= 21 && opcode <= 25 || opcode >= 54 && opcode <= 58
                        || opcode == 132 || opcode == 169)) throw new IOException("Invalid wide opcode");
                int local = input.readUnsignedShort();
                operands = opcode == 132 ? new int[]{local, input.readShort()} : new int[]{local};
            } else if (branch(opcode)) {
                int delta = opcode == 200 || opcode == 201 ? input.readInt() : input.readShort();
                operands = new int[]{offset + delta};
                if (opcode == 200) opcode = 167;
                if (opcode == 201) opcode = 168;
            } else if (opcode == 18) {
                operands = new int[]{input.readUnsignedByte()};
            } else if (opcode == 19 || opcode == 20 || opcode >= 178 && opcode <= 184
                    || opcode == 187 || opcode == 189 || opcode == 192 || opcode == 193) {
                operands = new int[]{input.readUnsignedShort()};
                if (opcode == 19) opcode = 18;
            } else if (opcode == 185 || opcode == 186) {
                operands = new int[]{input.readUnsignedShort(), input.readUnsignedByte(), input.readUnsignedByte()};
            } else if (opcode == 197) {
                operands = new int[]{input.readUnsignedShort(), input.readUnsignedByte()};
            } else if (opcode == 132) {
                operands = new int[]{input.readUnsignedByte(), input.readByte()};
            } else if (opcode == 16) {
                operands = new int[]{input.readByte()};
            } else if (opcode == 17) {
                operands = new int[]{input.readShort()};
            } else if (opcode >= 21 && opcode <= 25 || opcode >= 54 && opcode <= 58
                    || opcode == 169 || opcode == 188) {
                operands = new int[]{input.readUnsignedByte()};
            } else {
                if (opcode > 201) throw new IOException("Unsupported opcode " + opcode);
                operands = new int[0];
            }
            result.add(new Instruction(offset, opcode, operands));
        }
        return result;
    }

    private static int label(Map<Integer, Integer> labels, int offset) throws IOException {
        Integer ordinal = labels.get(offset);
        if (ordinal == null) throw new IOException("Invalid bytecode target " + offset);
        return ordinal;
    }

    private String hash(Method method) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        text(output, "gaius-kernel-mirror-bytecode-v1");
        output.writeInt(method.access);
        if (method.code != null) {
            DataInputStream input = stream(method.code);
            input.readUnsignedShort();
            input.readUnsignedShort();
            int length = input.readInt();
            if (length < 0 || length > 65535) throw new IOException("Invalid Code length");
            byte[] code = input.readNBytes(length);
            if (code.length != length) throw new IOException("Truncated Code");
            List<Instruction> instructions = decode(code);
            Map<Integer, Integer> labels = new HashMap<>();
            for (int index = 0; index < instructions.size(); index++) labels.put(instructions.get(index).offset, index);
            labels.put(code.length, instructions.size());
            output.writeInt(instructions.size());
            for (Instruction instruction : instructions) {
                int opcode = instruction.opcode;
                int[] operands = instruction.operands;
                output.writeInt(opcode);
                output.writeInt(operands.length);
                for (int index = 0; index < operands.length; index++) {
                    if (poolOperand(opcode) && index == 0) {
                        constant(output, operands[index], new ArrayList<>());
                    } else if (branch(opcode) || opcode == 170 && index != 1
                            || opcode == 171 && index % 2 == 0) {
                        output.writeInt(label(labels, operands[index]));
                    } else {
                        output.writeInt(operands[index]);
                    }
                }
            }
            int exceptions = input.readUnsignedShort();
            output.writeInt(exceptions);
            for (int index = 0; index < exceptions; index++) {
                output.writeInt(label(labels, input.readUnsignedShort()));
                output.writeInt(label(labels, input.readUnsignedShort()));
                output.writeInt(label(labels, input.readUnsignedShort()));
                int catchType = input.readUnsignedShort();
                output.writeBoolean(catchType != 0);
                if (catchType != 0) constant(output, catchType, new ArrayList<>());
            }
            attributes(input);
            if (input.available() != 0) throw new IOException("Trailing Code bytes");
        } else {
            output.writeInt(-1);
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }

    private static String quote(String text) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '"' || character == '\\') result.append('\\').append(character);
            else if (character < 32 || Character.isSurrogate(character)) result.append(String.format("\\u%04x", (int) character));
            else result.append(character);
        }
        return result.append('"').toString();
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Expected one named client jar");
        Map<String, ClassHasher> classes = new HashMap<>();
        String requests = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        List<String> records = new ArrayList<>();
        try (JarFile jar = new JarFile(arguments[0], false)) {
            for (String request : requests.split("\\R")) {
                if (request.isEmpty()) continue;
                String[] fields = request.split("\\t", -1);
                if (fields.length != 3) throw new IllegalArgumentException("Expected owner, name, descriptor");
                String owner = fields[0];
                if (!classes.containsKey(owner)) {
                    var entry = jar.getJarEntry(owner.replace('.', '/') + ".class");
                    if (entry == null) classes.put(owner, null);
                    else try (var input = jar.getInputStream(entry)) { classes.put(owner, new ClassHasher(input.readAllBytes())); }
                }
                ClassHasher target = classes.get(owner);
                String targetHash = "null";
                List<String> alternatives = new ArrayList<>();
                if (target != null) {
                    for (Method method : target.methods) {
                        if (!method.name.equals(fields[1])) continue;
                        alternatives.add(quote(method.descriptor));
                        if (method.descriptor.equals(fields[2])) targetHash = quote(target.hash(method));
                    }
                }
                records.add("{\"owner\":" + quote(owner) + ",\"name\":" + quote(fields[1])
                        + ",\"descriptor\":" + quote(fields[2]) + ",\"targetHash\":" + targetHash
                        + ",\"alternatives\":[" + String.join(",", alternatives) + "]}");
            }
        }
        System.out.println("[" + String.join(",", records) + "]");
    }
}
