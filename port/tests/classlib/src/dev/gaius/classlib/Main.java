package dev.gaius.classlib;

import dev.gaius.classlib.repro.BufCompare;
import dev.gaius.classlib.repro.BufTest;
import dev.gaius.classlib.repro.BufTest2;
import dev.gaius.classlib.repro.CatchRepro;
import dev.gaius.classlib.repro.DirectZero;
import dev.gaius.classlib.repro.FilesDirs;
import dev.gaius.classlib.repro.RegionHeader;
import dev.gaius.classlib.repro.ResCompare;
import dev.gaius.classlib.repro.ResTest;
import dev.gaius.classlib.repro.SumProbe;
import dev.gaius.classlib.repro.SyncRepro;
import dev.gaius.classlib.repro.WasmBugs;
import dev.gaius.classlib.repro.WeakRefNull;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Class library conformance suite: {@code Main <section>} prints the lines of one section and
 * then {@code END <section>}; {@code Main --list} prints the section names. Every section runs
 * in its own process so that a trap in a WebAssembly build without strict checks ends only that
 * section. See port/tests/classlib/README.md.
 */
public final class Main {
    private static final Map<String, Runnable> SECTIONS = new LinkedHashMap<>();

    static {
        SECTIONS.put("env", Env::run);
        SECTIONS.put("hashmap", HashOrder::run);
        SECTIONS.put("hashcode", HashCodes::run);
        SECTIONS.put("format", Format::run);
        SECTIONS.put("floatstr", FloatStrings::run);
        SECTIONS.put("math", MathFunctions::run);
        SECTIONS.put("strictmath", StrictMathHelpers::run);
        SECTIONS.put("trunc", Truncation::run);
        SECTIONS.put("ints", Ints::run);
        SECTIONS.put("random", Randoms::run);
        SECTIONS.put("sort", Sorting::run);
        SECTIONS.put("character", Characters::run);
        SECTIONS.put("strings", Strings::run);
        SECTIONS.put("date", Dates::run);
        SECTIONS.put("misc", Misc::run);
        SECTIONS.put("streams", Streams::run);
        SECTIONS.put("views", Views::run);
        SECTIONS.put("repro.wasmbugs", () -> WasmBugs.main(new String[0]));
        SECTIONS.put("repro.catch", () -> CatchRepro.main(new String[0]));
        SECTIONS.put("repro.sync", () -> SyncRepro.main(new String[0]));
        SECTIONS.put("repro.restest", () -> ResTest.main(new String[0]));
        SECTIONS.put("repro.buftest", () -> BufTest.main(new String[0]));
        SECTIONS.put("repro.buftest2", () -> BufTest2.main(new String[0]));
        SECTIONS.put("repro.bufcompare", () -> BufCompare.main(new String[0]));
        SECTIONS.put("repro.directzero", () -> DirectZero.main(new String[0]));
        SECTIONS.put("repro.regionheader", RegionHeader::run);
        SECTIONS.put("repro.filesdirs", () -> FilesDirs.main(new String[0]));
        SECTIONS.put("repro.rescompare", () -> ResCompare.main(new String[0]));
        SECTIONS.put("repro.sumprobe", () -> SumProbe.main(new String[0]));
        SECTIONS.put("repro.weakref", () -> WeakRefNull.main(new String[0]));
        for (String name : Traps.NAMES) {
            SECTIONS.put("trap." + name, () -> Traps.run(name));
        }
        SECTIONS.put("bench", Bench::run);
    }

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("--list")) {
            for (String name : SECTIONS.keySet()) {
                System.out.println(name);
            }
            return;
        }
        for (String name : args) {
            Runnable section = SECTIONS.get(name);
            if (section == null) {
                System.out.println("# unknown section " + name);
                continue;
            }
            Out.section(name);
            section.run();
            System.out.println("END " + name);
        }
    }
}
