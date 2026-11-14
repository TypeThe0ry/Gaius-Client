import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.teavm.backend.javascript.rendering.JSParser;
import org.teavm.rhino.javascript.CompilerEnvirons;
import org.teavm.rhino.javascript.ErrorReporter;
import org.teavm.rhino.javascript.EvaluatorException;
import org.teavm.rhino.javascript.ast.AstNode;
import org.teavm.rhino.javascript.ast.FunctionNode;
import org.teavm.rhino.javascript.ast.Name;
import org.teavm.rhino.javascript.ast.NodeVisitor;
import org.teavm.rhino.javascript.ast.ScriptNode;
import org.teavm.rhino.javascript.ast.VariableDeclaration;
import org.teavm.rhino.javascript.ast.VariableInitializer;

/** Reports every generated function whose own parameter and local names overlap. */
public final class JsFunctionCollisionScan {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        CompilerEnvirons env = new CompilerEnvirons();
        env.setRecoverFromErrors(true);
        env.setIdeMode(true);
        env.setErrorReporter(new ErrorReporter() {
            @Override public void warning(String message, String source, int line, String text, int offset) { }
            @Override public void error(String message, String source, int line, String text, int offset) { }
            @Override public EvaluatorException runtimeError(String message, String source, int line, String text, int offset) {
                return new EvaluatorException(message);
            }
        });
        env.setLanguageVersion(180);
        String source = Files.readString(file);
        // Rhino 0.15 cannot visit an optional catch binding (catch {}): its
        // CatchClause has a null varName. Give the parser-only copy a harmless
        // binding so the generated file can still be scanned completely.
        source = Pattern.compile("\\bcatch\\s*\\{").matcher(source)
                .replaceAll("catch (__gaiusCollisionCatch) {");
        ScriptNode ast = new JSParser(env).parse(source, file.toString(), 0);
        List<String> findings = new ArrayList<>();
        int[] functionCount = { 0 };
        ast.visit(new NodeVisitor() {
            @Override public boolean visit(AstNode node) {
                if (!(node instanceof FunctionNode fn)) return true;
                functionCount[0]++;
                Set<String> params = new HashSet<>();
                for (AstNode param : fn.getParams()) {
                    if (param instanceof Name name) params.add(name.getIdentifier());
                }
                Set<String> names = new HashSet<>();
                fn.getBody().visit(new NodeVisitor() {
                    @Override public boolean visit(AstNode child) {
                        if (child != fn.getBody() && child instanceof FunctionNode) return false;
                        if (child instanceof VariableDeclaration declaration) {
                            for (VariableInitializer variable : declaration.getVariables()) {
                                variable.getTarget().visit(target -> {
                                    if (target instanceof Name name) names.add(name.getIdentifier());
                                    return true;
                                });
                            }
                        }
                        return true;
                    }
                });
                names.retainAll(params);
                if (!names.isEmpty()) {
                    findings.add(file + ":" + fn.getLineno() + " function="
                            + (fn.getName().isEmpty() ? "<anonymous>" : fn.getName())
                            + " collisions=" + String.join(",", names));
                }
                return true;
            }
        });
        findings.forEach(System.out::println);
        System.out.println("SUMMARY functions=" + functionCount[0] + " collisions=" + findings.size());
    }
}
