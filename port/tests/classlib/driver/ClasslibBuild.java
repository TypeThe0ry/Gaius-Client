package dev.gaius.classlib.driver;

import java.io.File;
import org.teavm.backend.javascript.JSModuleType;
import org.teavm.diagnostics.Problem;
import org.teavm.tooling.ConsoleTeaVMToolLog;
import org.teavm.tooling.TeaVMTargetType;
import org.teavm.tooling.TeaVMTool;
import org.teavm.vm.TeaVMOptimizationLevel;

/**
 * Compiles the class library conformance suite with TeaVM (port/tests/classlib/runner/run.mjs
 * starts it with -Xmx3g and the suite, the TeaVM jars and the driver on the class path):
 *
 * <pre>ClasslibBuild js|wasm OUT_DIR MAIN_CLASS OPTIMIZATION strict=true|false [key=value...]</pre>
 *
 * The key=value pairs become TeaVM properties (the gaius.teavm.* options of the product build).
 * Exits 1 when TeaVM reports a severe problem (a class library method the suite calls is missing,
 * for one), after printing every problem.
 */
public final class ClasslibBuild {
    private ClasslibBuild() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            throw new IllegalArgumentException(
                    "usage: ClasslibBuild js|wasm OUT_DIR MAIN_CLASS OPTIMIZATION strict=true|false [key=value...]");
        }
        boolean js = switch (args[0]) {
            case "js" -> true;
            case "wasm" -> false;
            default -> throw new IllegalArgumentException("target must be js or wasm: " + args[0]);
        };
        TeaVMTool tool = new TeaVMTool();
        tool.setTargetType(js ? TeaVMTargetType.JAVASCRIPT : TeaVMTargetType.WEBASSEMBLY_GC);
        tool.setTargetDirectory(new File(args[1]));
        tool.setTargetFileName(js ? "classes.js" : "classes.wasm");
        tool.setMainClass(args[2]);
        tool.setOptimizationLevel(TeaVMOptimizationLevel.valueOf(args[3]));
        if (!args[4].startsWith("strict=")) {
            throw new IllegalArgumentException("fifth argument must be strict=true|false: " + args[4]);
        }
        tool.setStrict(Boolean.parseBoolean(args[4].substring("strict=".length())));
        if (js) {
            tool.setJsModuleType(JSModuleType.UMD);
            // The release build minifies (GAIUS_MINIFYING=true); TeaVMTool calls it obfuscation.
            tool.setObfuscated(true);
        } else {
            tool.setObfuscated(false);
        }
        tool.setDebugInformationGenerated(false);
        tool.setSourceMapsFileGenerated(false);
        tool.setIncremental(false);
        tool.setClassLoader(ClasslibBuild.class.getClassLoader());
        tool.setLog(new ConsoleTeaVMToolLog(false));
        for (int i = 5; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("property must be key=value: " + args[i]);
            }
            tool.getProperties().setProperty(args[i].substring(0, eq), args[i].substring(eq + 1));
        }
        long start = System.currentTimeMillis();
        tool.generate();
        int severe = 0;
        for (Problem problem : tool.getProblemProvider().getProblems()) {
            boolean isSevere = tool.getProblemProvider().getSevereProblems().contains(problem);
            if (isSevere) {
                severe++;
            }
            System.out.println((isSevere ? "ERROR " : "WARNING ") + problem.getText() + " "
                    + java.util.Arrays.toString(problem.getParams()) + " at "
                    + (problem.getLocation() == null ? "unknown" : problem.getLocation().getMethod()));
        }
        System.out.println("CLASSLIB_BUILD target=" + args[0] + " generated=" + tool.getGeneratedFiles().size()
                + " errors=" + severe + " millis=" + (System.currentTimeMillis() - start));
        System.exit(severe == 0 ? 0 : 1);
    }
}
