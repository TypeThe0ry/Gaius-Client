import {execFileSync} from "node:child_process";
import {copyFileSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync} from "node:fs";
import {homedir} from "node:os";
import {basename, delimiter, dirname, join, resolve} from "node:path";

// Keep each gate's classpath limited to the requested bridges and their nested classes.
export function generateBrowserBridges({root, profile, role, scratch, out, owners,
  java = "java", javac = "javac", maven = process.env.GAIUS_MAVEN_REPOSITORY || join(homedir(), ".m2/repository")}) {
  const {patchSet, renderSeam} = typeof profile === "string"
    ? JSON.parse(readFileSync(join(root, "port/versions", `${profile}.json`), "utf8")) : profile;
  const asm = resolve(maven, "org/ow2/asm/asm/9.8/asm-9.8.jar");
  if (!existsSync(asm)) throw new Error(`missing ${asm} (offline ASM 9.8 for BrowserBridges)`);
  mkdirSync(scratch, {recursive: true});
  const temp = mkdtempSync(join(scratch, "browser-bridges-"));
  try {
    const tools = join(temp, "tools");
    const generated = join(temp, "classes");
    const sources = join(root, "port/tools/src/main/java");
    execFileSync(javac, ["--release", "21", "-proc:none", "-cp", asm, "-sourcepath", sources,
      "-d", tools, join(sources, "dev/gaius/tools/BrowserBridges.java")], {stdio: "pipe"});
    execFileSync(java, ["-cp", [tools, asm].join(delimiter), "dev.gaius.tools.BrowserBridges",
      patchSet, renderSeam, role, generated], {stdio: "pipe"});

    if (!owners) {
      cpSync(generated, out, {recursive: true});
      return;
    }
    const files = [];
    for (const owner of owners) {
      const parent = dirname(owner).replaceAll("\\", "/");
      const name = basename(owner);
      const directory = join(generated, parent);
      if (!existsSync(join(directory, `${name}.class`))) {
        throw new Error(`BrowserBridges ${patchSet}/${renderSeam}/${role} did not emit ${owner}.class`);
      }
      mkdirSync(join(out, parent), {recursive: true});
      for (const entry of readdirSync(directory)) {
        if (entry !== `${name}.class` && !(entry.startsWith(`${name}$`) && entry.endsWith(".class"))) continue;
        const relative = `${parent}/${entry}`;
        copyFileSync(join(directory, entry), join(out, relative));
        files.push(relative);
      }
    }
    return files.sort();
  } finally {
    rmSync(temp, {recursive: true, force: true});
  }
}
