package org.lwjgl.sdl;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.teavm.backend.javascript.rendering.JSParser;
import org.teavm.rhino.javascript.CompilerEnvirons;
import org.teavm.rhino.javascript.ErrorReporter;
import org.teavm.rhino.javascript.EvaluatorException;

/**
 * Parses every {@code @JSBody} script of the given class directories exactly as TeaVM
 * 0.15's {@code JSClassProcessor} does ({@code function(){<script>}}, Rhino ES6 language
 * level 180, error recovery on), so a script TeaVM would reject fails here without a
 * TeaVM compile. Used by {@code port/scripts/lwjgl-sdl-browser-patcher-smoke.mjs}.
 *
 * <p>Usage: {@code TeaVmJsBodyCheck CLASS_DIR...}; prints one {@code JSBODY_OK} line per
 * script and exits with status 1 on the first class with a parse error.
 */
public final class TeaVmJsBodyCheck {
    private static final String JS_BODY = "Lorg/teavm/jso/JSBody;";

    private TeaVmJsBodyCheck() {
    }

    public static void main(String[] args) throws IOException {
        int scripts = 0;
        List<String> failures = new ArrayList<>();
        for (String directory : args) {
            List<Path> classes;
            try (Stream<Path> walk = Files.walk(Path.of(directory))) {
                classes = walk.filter(path -> path.toString().endsWith(".class")).sorted().toList();
            }
            for (Path file : classes) {
                ClassNode node = new ClassNode();
                new ClassReader(Files.readAllBytes(file)).accept(node, ClassReader.SKIP_CODE);
                for (MethodNode method : node.methods) {
                    String script = script(method.invisibleAnnotations);
                    if (script == null) {
                        script = script(method.visibleAnnotations);
                    }
                    if (script == null) {
                        continue;
                    }
                    scripts++;
                    String where = node.name + "." + method.name + method.desc;
                    List<String> errors = parse(script);
                    if (errors.isEmpty()) {
                        System.out.println("JSBODY_OK " + where);
                    } else {
                        failures.add(where + ": " + String.join("; ", errors));
                    }
                }
            }
        }
        for (String failure : failures) {
            System.out.println("JSBODY_ERROR " + failure);
        }
        System.out.println("TeaVmJsBodyCheck: " + scripts + " scripts, " + failures.size() + " with errors");
        if (!failures.isEmpty() || scripts == 0) {
            System.exit(1);
        }
    }

    private static String script(List<AnnotationNode> annotations) {
        if (annotations == null) {
            return null;
        }
        for (AnnotationNode annotation : annotations) {
            if (!annotation.desc.equals(JS_BODY) || annotation.values == null) {
                continue;
            }
            for (int index = 0; index + 1 < annotation.values.size(); index += 2) {
                if ("script".equals(annotation.values.get(index))) {
                    return (String) annotation.values.get(index + 1);
                }
            }
        }
        return null;
    }

    private static List<String> parse(String script) throws IOException {
        List<String> errors = new ArrayList<>();
        ErrorReporter reporter = new ErrorReporter() {
            @Override
            public void warning(String message, String source, int line, String lineSource, int offset) {
            }

            @Override
            public void error(String message, String source, int line, String lineSource, int offset) {
                errors.add("line " + line + ": " + message + " [" + lineSource + "]");
            }

            @Override
            public EvaluatorException runtimeError(
                    String message, String source, int line, String lineSource, int offset) {
                errors.add("line " + line + ": " + message);
                return new EvaluatorException(message, source, line, lineSource, offset);
            }
        };
        CompilerEnvirons environment = new CompilerEnvirons();
        environment.setRecoverFromErrors(true);
        environment.setLanguageVersion(180);
        environment.setIdeMode(true);
        try {
            new JSParser(environment, reporter).parseAsObject(new StringReader("function(){" + script + "}"), null, 0);
        } catch (EvaluatorException failure) {
            errors.add(failure.getMessage());
        }
        return errors;
    }
}
