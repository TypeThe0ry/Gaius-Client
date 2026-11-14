#!/usr/bin/env node
// Run real jar-derived method bodies with the test's explicit JVM boundary doubles.
// Caller supplies profile JSON paths and owns all compiler/game scratch output.
import {execFileSync} from "node:child_process";
import {mkdirSync, readFileSync} from "node:fs";
import {delimiter, join, resolve} from "node:path";
import {homedir} from "node:os";
import {fileURLToPath} from "node:url";
const root = fileURLToPath(new URL("../../", import.meta.url));
const [mode, output, ...profiles] = process.argv.slice(2);
const tests = {command: "CommandErrorDiagnosticsTest", loading: "ClientLoadingCompositionTest"};
if (!tests[mode] || !output || !profiles.length) throw new Error("Pass command|loading, caller scratch and profile JSON paths");
const scratch = resolve(output), classes = join(scratch, "classes");
mkdirSync(classes, {recursive: true});
const maven = process.env.GAIUS_MAVEN_REPOSITORY ?? join(homedir(), ".m2/repository");
const asm = ["asm", "asm-tree"].map(name => join(maven, "org/ow2/asm", name, "9.8", `${name}-9.8.jar`));
const toolSources = process.env.GAIUS_PATCH_COMPOSITION_TOOLS_SOURCE ?? join(root, "port/tools/src/main/java");
const run = (tool, args) => {
    try { execFileSync(tool, args, {cwd: scratch, stdio: "inherit", timeout: 180000}); }
    catch (error) { console.error(`JVM_RUN_FAILED tool=${tool} exit=${error.status ?? "unknown"}`); process.exit(error.status || 1); }
};
run("javac", ["-J-Xmx1g", "--release", "21", "-proc:none", "-cp", asm.join(delimiter),
    "-sourcepath", toolSources, "-d", classes, join(root, "port/mc/tests/patch-composition", `${tests[mode]}.java`)]);
for (const profileFile of profiles) {
    const profile = JSON.parse(readFileSync(resolve(profileFile), "utf8"));
    const work = join(root, "port/work", profile.id);
    const classpath = [classes, ...asm, join(work, "client-named.jar"),
        ...readFileSync(join(work, "classpath.txt"), "utf8").trim().split(delimiter)];
    console.log(JSON.stringify({mode, profile: profile.id, jar: join(work, "client-named.jar"), toolSources,
        execution: "JVM method fixture; no browser or world"}));
    run("java", ["-Xmx2g", "-Xverify:all", "--sun-misc-unsafe-memory-access=allow", "-cp", classpath.join(delimiter),
        tests[mode], join(work, "client-named.jar"), join(scratch, profile.id)]);
}
