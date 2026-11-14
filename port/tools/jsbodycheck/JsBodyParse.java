import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.teavm.backend.javascript.rendering.JSParser;
import org.teavm.rhino.javascript.CompilerEnvirons;
import org.teavm.rhino.javascript.ErrorReporter;
import org.teavm.rhino.javascript.EvaluatorException;
import org.teavm.rhino.javascript.ast.FunctionNode;
import org.teavm.rhino.javascript.ast.Name;
import org.teavm.rhino.javascript.ast.ObjectProperty;
import org.teavm.rhino.javascript.ast.Scope;
import org.teavm.rhino.javascript.ast.Symbol;
import org.teavm.rhino.javascript.ast.VariableDeclaration;
import org.teavm.rhino.javascript.ast.VariableInitializer;

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
                    var ast = new JSParser(env).parse(Files.readString(file), null, 0);
                    boolean[] rootFunction = {true};
                    ast.visit(node -> {
                        if (!(node instanceof FunctionNode function)) return true;
                        Set<String> params = new HashSet<>();
                        for (var param : function.getParams()) {
                            if (param instanceof Name name) params.add(name.getIdentifier());
                        }
                        int parameterCount = params.size();
                        boolean hasParameters = parameterCount != 0;
                        Set<String> locals = new HashSet<>();
                        function.getBody().visit(child -> {
                            if (child != function.getBody() && child instanceof FunctionNode) return false;
                            if (child instanceof VariableDeclaration declaration) {
                                for (VariableInitializer variable : declaration.getVariables()) {
                                    variable.getTarget().visit(target -> {
                                        if (target instanceof Name name) locals.add(name.getIdentifier());
                                        return true;
                                    });
                                }
                            }
                            return true;
                        });
                        params.retainAll(locals);
                        if (!params.isEmpty()) errors.add("line " + function.getLineno()
                                + ": local declaration collides with JSBody parameter(s): "
                                + String.join(",", params));
                        // The emitted JSBody wrapper only has a stable one-letter alias
                        // window for the single-parameter form. Multi-parameter bodies
                        // are covered by the generated collision scan after minification.
                        boolean checkMinifiedLocals = rootFunction[0] && parameterCount == 1;
                        rootFunction[0] = false;
                        if (hasParameters && checkMinifiedLocals) {
                            Set<String> oneLetterLocals = new HashSet<>();
                            for (String local : locals) {
                                // TeaVM's minifier assigns short parameter names from the
                                // leading alphabet. Keep a two-name margin beyond the
                                // parameter count; unrelated later letters are valid.
                                if (local.length() == 1 && local.charAt(0) >= 'a'
                                        && local.charAt(0) < 'a' + Math.min(26, parameterCount + 2)) {
                                    oneLetterLocals.add(local);
                                }
                            }
                            if (!oneLetterLocals.isEmpty()) {
                                errors.add("line " + function.getLineno()
                                        + ": one-letter local declaration is unsafe in a parameterized JSBody: "
                                        + String.join(",", oneLetterLocals));
                            }
                        }
                        return true;
                    });
                    Set<String> prefixedLocals = new HashSet<>();
                    ast.visit(node -> {
                        if (node instanceof Name name && !isPropertyKey(name)) {
                            Scope scope = name.getDefiningScope();
                            Symbol symbol = scope == null ? null : scope.getSymbol(name.getIdentifier());
                            if (symbol != null && isLocal(symbol) && symbol.getName().startsWith("__g_")) {
                                prefixedLocals.add(symbol.getName().substring(4));
                            }
                        }
                        return true;
                    });
                    ast.visit(node -> {
                        if (node instanceof Name name && !isPropertyKey(name)
                                && name.getDefiningScope() == null && prefixedLocals.contains(name.getIdentifier())) {
                            errors.add("line " + name.getLineno() + ": unresolved local reference '"
                                    + name.getIdentifier() + "' shadows renamed binding '__g_"
                                    + name.getIdentifier() + "'");
                        }
                        return true;
                    });
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

    private static boolean isLocal(Symbol symbol) {
        return switch (symbol.getDeclTypeName()) {
            case "VAR", "LET", "CONST" -> true;
            default -> false;
        };
    }

    private static boolean isPropertyKey(Name name) {
        return (name.getParent() instanceof org.teavm.rhino.javascript.ast.PropertyGet member
                && member.getProperty() == name)
                || (name.getParent() instanceof ObjectProperty property && property.getLeft() == name);
    }
}
