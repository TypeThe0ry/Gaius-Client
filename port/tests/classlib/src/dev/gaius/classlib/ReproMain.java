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
import dev.gaius.classlib.repro.SyncRepro;
import dev.gaius.classlib.repro.WasmBugs;
import dev.gaius.classlib.repro.WeakRefNull;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The TeaVM bug repros alone, with the section names of {@link Main}. It reaches nothing that the
 * unpatched TeaVM 0.15 class library lacks, so {@code run.mjs --main dev.gaius.classlib.ReproMain
 * --wasm-overlays stock} shows each repro failing without the overlay patches.
 */
public final class ReproMain {
    private static final Map<String, Runnable> SECTIONS = new LinkedHashMap<>();

    static {
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
        SECTIONS.put("repro.weakref", () -> WeakRefNull.main(new String[0]));
    }

    private ReproMain() {
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
