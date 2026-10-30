import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.teavm.backend.javascript.rendering.JSParser;
import org.teavm.rhino.javascript.CompilerEnvirons;
import org.teavm.rhino.javascript.ErrorReporter;
import org.teavm.rhino.javascript.EvaluatorException;

/** Parses one extracted @JSBody body with TeaVM's JavaScript backend parser. */
public final class JsBodyParse {
    public static void main(String[] args) throws Exception {
        int failed = 0;
        try (Stream<Path> stream = Files.list(Path.of(args[0]))) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".js")).sorted().toList()) {
                List<String> errors = new ArrayList<>();
                CompilerEnvirons env = new CompilerEnvirons();
                env.setRecoverFromErrors(true);
                env.setLanguageVersion(180);
                env.setIdeMode(true);
                env.setErrorReporter(new ErrorReporter() {
                    @Override public void warning(String message, String name, int line, String src, int offset) { }
                    @Override public void error(String message, String name, int line, String src, int offset) {
                        errors.add("line " + line + ": " + message);
                    }
                    @Override public EvaluatorException runtimeError(String message, String name, int line, String src, int offset) {
                        errors.add("line " + line + ": " + message);
                        return new EvaluatorException(message);
                    }
                });
                try {
                    new JSParser(env).parse(Files.readString(file), null, 0);
                } catch (RuntimeException e) {
                    errors.add("exception: " + String.valueOf(e.getMessage()));
                }
                if (!errors.isEmpty()) {
                    failed++;
                    System.out.println(file.getFileName() + "\t" + String.join(" | ", errors.subList(0, Math.min(3, errors.size()))));
                }
            }
        }
        System.out.println("SUMMARY\t" + failed);
        if (failed != 0) System.exit(1);
    }
}
