import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {homedir, tmpdir} from "node:os";
import {delimiter, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

// Node accepts syntax that TeaVM's embedded JSBody parser rejects. Exercise
// TeaVM 0.15's real parser, using JSClassProcessor's compiler environment.
const root = fileURLToPath(new URL("../../", import.meta.url));
const files = process.argv.slice(2);
if (!files.length) files.push("port/src/main/java/dev/gaius/browser/BrowserWorldgenScheduler.java");
const repo = process.env.M2_REPO || process.env.MAVEN_REPOSITORY || join(homedir(), ".m2", "repository");
const cp = ["teavm-core", "teavm-relocated-libs-rhino"].map(name =>
  join(repo, "org", "teavm", name, "0.15.0", `${name}-0.15.0.jar`)).join(delimiter);
const suffix = process.platform === "win32" ? ".exe" : "";
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", `java${suffix}`) : "java";
const dir = await mkdtemp(join(tmpdir(), "gaius-jsbody-parser-"));
try {
  const fixture = join(dir, "JsBodyParserCheck.java");
  await writeFile(fixture, `
import java.nio.file.*;
import java.io.StringReader;
import org.teavm.backend.javascript.rendering.JSParser;
import org.teavm.rhino.javascript.*;
public class JsBodyParserCheck {
  static class Reporter implements ErrorReporter {
    int errors;
    public void warning(String m,String s,int l,String line,int offset) {}
    public void error(String m,String s,int l,String line,int offset) { errors++; }
    public EvaluatorException runtimeError(String m,String s,int l,String line,int offset) {
      errors++; return new EvaluatorException(m,s,l,line,offset);
    }
  }
  static boolean accepts(String script) throws Exception {
    CompilerEnvirons env = new CompilerEnvirons();
    env.setRecoverFromErrors(true);
    env.setLanguageVersion(180);
    env.setIdeMode(true);
    Reporter reporter = new Reporter();
    try {
      new JSParser(env, reporter).parseAsObject(
        new StringReader("function probe(){\\n" + script + "\\n}"), "probe", 0);
    } catch (EvaluatorException e) { return false; }
    return reporter.errors == 0;
  }
  public static void main(String[] args) throws Exception {
    if (accepts("const capacity=16; const ring={capacity, entries: []};"))
      throw new AssertionError("Regression control unexpectedly accepted shorthand");
    if (!accepts("const capacity=16; const ring={capacity: capacity, entries: []};"))
      throw new AssertionError("Explicit key control failed");
    for (String file : args) {
      if (!accepts(Files.readString(Path.of(file))))
        throw new AssertionError("TeaVM JSBody parse rejected " + file);
    }
    System.out.println("TEAVM_JSBODY_PARSE_OK bodies=" + args.length);
  }
}
`, "utf8");
  const scripts = [];
  const labels = [];
  for (const file of files) {
    const source = await readFile(resolve(root, file), "utf8");
    // Scope each match to one annotation. These files use Java text blocks for
    // their nontrivial JSBody code; no source or browser functions are replaced.
    const pattern = /@JSBody\((?:(?!@JSBody)[\s\S])*?script\s*=\s*"""([\s\S]*?)"""\s*\)/g;
    for (const match of source.matchAll(pattern)) {
      const label = `${file}:${source.slice(0, match.index).split("\n").length}`;
      const path = join(dir, `body-${scripts.length}.js`);
      await writeFile(path, match[1], "utf8");
      scripts.push(path);
      labels.push(label);
    }
  }
  assert.ok(scripts.length, "No text-block JSBody methods found in requested files");
  try {
    process.stdout.write(execFileSync(java, ["--class-path", cp, fixture, ...scripts],
      {encoding: "utf8", timeout: 60000}));
  } catch (error) {
    process.stderr.write(labels.map((label, index) => `body-${index}.js: ${label}`).join("\n") + "\n");
    throw error;
  }
} finally {
  await rm(dir, {recursive: true, force: true});
}
