import java.io.File;
import java.util.Arrays;
import org.teavm.diagnostics.Problem;
import org.teavm.tooling.ConsoleTeaVMToolLog;
import org.teavm.tooling.TeaVMTargetType;
import org.teavm.tooling.TeaVMTool;
import org.teavm.vm.TeaVMOptimizationLevel;

/**
 * Compiles the JSPI semantic suite with TeaVM's WEBASSEMBLY_GC target and the Gaius TeaVM
 * options of the wasmgc client role (gaius.teavm.jspi=true):
 * {@code JspiSuiteBuild <outDir> <mainClass> [key=value...]}. The class path carries the
 * patched teavm-core/teavm-classlib jars of a wasmgc overlay directory.
 */
public final class JspiSuiteBuild {
    private JspiSuiteBuild() {
    }

    public static void main(String[] args) throws Exception {
        TeaVMTool tool = new TeaVMTool();
        tool.setTargetType(TeaVMTargetType.WEBASSEMBLY_GC);
        tool.setTargetDirectory(new File(args[0]));
        tool.setTargetFileName("classes.wasm");
        tool.setMainClass(args[1]);
        tool.setOptimizationLevel(TeaVMOptimizationLevel.ADVANCED);
        tool.setObfuscated(false);
        tool.setStrict(false);
        tool.setDebugInformationGenerated(false);
        tool.setSourceMapsFileGenerated(false);
        tool.setIncremental(false);
        tool.setClassLoader(JspiSuiteBuild.class.getClassLoader());
        tool.setLog(new ConsoleTeaVMToolLog(false));
        for (int i = 2; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            tool.getProperties().setProperty(args[i].substring(0, eq), args[i].substring(eq + 1));
        }
        long start = System.currentTimeMillis();
        tool.generate();
        int errors = 0;
        for (Problem problem : tool.getProblemProvider().getSevereProblems()) {
            errors++;
            System.out.println("ERROR " + problem.getText() + " " + Arrays.toString(problem.getParams())
                    + " at " + problem.getLocation());
        }
        System.out.println("generated " + tool.getGeneratedFiles() + " errors=" + errors + " in "
                + (System.currentTimeMillis() - start) + " ms");
        System.exit(errors == 0 ? 0 : 1);
    }
}
