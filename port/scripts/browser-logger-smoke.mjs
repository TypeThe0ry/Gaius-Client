import assert from "node:assert/strict";
import {access, mkdir, mkdtemp, readFile, readdir, writeFile, rm} from "node:fs/promises";
import {execFileSync} from "node:child_process";
import {homedir, tmpdir} from "node:os";
import {fileURLToPath} from "node:url";
import {join, delimiter} from "node:path";

async function resolveSlf4jApi() {
  const roots = [process.env.M2_REPO, process.env.MAVEN_REPOSITORY,
    join(homedir(), ".m2", "repository")].filter(Boolean);
  for (const root of roots) {
    const artifactRoot = join(root, "org", "slf4j", "slf4j-api");
    let versions;
    try { versions = await readdir(artifactRoot, {withFileTypes: true}); } catch { continue; }
    for (const entry of versions.filter((item) => item.isDirectory()).sort().reverse()) {
      const candidate = join(artifactRoot, entry.name, `slf4j-api-${entry.name}.jar`);
      try { await access(candidate); return candidate; } catch { /* try next cache/version */ }
    }
  }
  throw new Error("slf4j-api jar not found; set M2_REPO or install it in ~/.m2/repository");
}

const source = await readFile(new URL(
  "../overrides/libraries/slf4j/src/main/java/org/slf4j/impl/BrowserLogger.java",
  import.meta.url,
), "utf8");

assert.match(source, /Throwable trailingThrowable/,
  "BrowserLogger must identify a trailing Throwable separately from format arguments");
assert.match(source, /formatThrowable\(trailingThrowable\)/,
  "BrowserLogger must retain a trailing Throwable in its String console payload");
assert.match(source, /for \(int depth = 0; current != null && depth < 4/,
  "BrowserLogger Throwable formatting must have a bounded cause depth");
assert.match(source, /sb\.length\(\) < 4096/,
  "BrowserLogger Throwable formatting must have a bounded payload");
assert.match(source, /private static native void logToConsole\(int level, String text\)/,
  "BrowserLogger JSBody adapter must remain String-only");
for (const level of ["trace", "debug"]) {
  const methods = source.split("\n").filter(line => line.includes(`public void ${level}(`));
  assert.equal(methods.length, 5, `BrowserLogger must keep the five ${level} overloads`);
  for (const method of methods) {
    assert.match(method, /\{ if \(verbose\(\)\) log\(/,
      `BrowserLogger ${level} must return before formatting when disabled: ${method.trim()}`);
  }
}

const fixtureRoot = await mkdtemp(join(tmpdir(), "gaius-browser-logger-"));
try {
  const teaVmRoot = join(fixtureRoot, "org", "teavm", "jso");
  await mkdir(teaVmRoot, {recursive: true});
  await writeFile(join(fixtureRoot, "BrowserLoggerFixture.java"), `
package org.slf4j.impl;
import java.lang.reflect.Method;
public final class BrowserLoggerFixture {
  public static void main(String[] args) throws Exception {
    Method format = BrowserLogger.class.getDeclaredMethod("format", String.class, Object[].class);
    format.setAccessible(true);
    String resource = "minecraft:trial_chambers/corridor/end_2";
    IOExceptionLike error = new IOExceptionLike("root " + "x".repeat(6000));
    for (int i = 0; i < 6; i++) error = new IOExceptionLike("cause-" + i, error);
    String rendered = (String) format.invoke(null, new Object[] {
        "Couldn't load structure {}", new Object[] {resource, error}});
    if (!rendered.startsWith("Couldn't load structure " + resource + "\\n")) throw new AssertionError(rendered);
    if (!rendered.contains("org.slf4j.impl.BrowserLoggerFixture$IOExceptionLike: cause-5") ||
        !rendered.contains("Caused by: org.slf4j.impl.BrowserLoggerFixture$IOExceptionLike: cause-4") ||
        !rendered.contains("BrowserLoggerFixture.main")) {
      throw new AssertionError(rendered);
    }
    int causes = rendered.split("Caused by:", -1).length - 1;
    if (causes != 3) throw new AssertionError("cause depth was not bounded: " + causes);
    if (rendered.length() > 4096) throw new AssertionError("unbounded Throwable output: " + rendered.length());
    String longRendered = (String) format.invoke(null, new Object[] {
        "failure", new Object[] {new IOExceptionLike("x".repeat(6000))}});
    if (longRendered.length() != "failure\\n".length() + 4096) {
      throw new AssertionError("long Throwable was not truncated: " + longRendered.length());
    }
    String ordinary = (String) format.invoke(null, new Object[] {
        "plain {} {}", new Object[] {"a", "b"}});
    if (!"plain a b".equals(ordinary)) throw new AssertionError(ordinary);
    // Debug and trace are off (no opt-in): they must not format their arguments, nor reach
    // the console adapter (its native would fail here on the JVM).
    java.lang.reflect.Field state = BrowserLogger.class.getDeclaredField("verboseState");
    state.setAccessible(true);
    state.setInt(null, -1);
    BrowserLogger logger = new BrowserLogger("fixture");
    Object bomb = new Object() {
      @Override public String toString() { throw new AssertionError("disabled level formatted"); }
    };
    logger.debug("a {}", bomb);
    logger.debug("a {} {}", bomb, bomb);
    logger.debug("a {} {} {}", bomb, bomb, bomb);
    logger.trace("a {}", bomb);
    logger.trace("a {} {}", bomb, bomb);
    logger.trace("a {} {} {}", bomb, bomb, bomb);
    if (logger.isDebugEnabled() || logger.isTraceEnabled()) throw new AssertionError("levels enabled");
    // Vanilla's unsupported-WIREFRAME pipeline notice is the only error that is downgraded
    // (to a single info line); other pipeline failures and other errors stay errors.
    Method wireframe = BrowserLogger.class.getDeclaredMethod("isWireframeNotice", int.class, String.class);
    wireframe.setAccessible(true);
    String notice = "Pipeline {} uses WIREFRAME fill mode, not supported by device";
    if (!(Boolean) wireframe.invoke(null, 40, notice)) throw new AssertionError("wireframe notice kept as error");
    if ((Boolean) wireframe.invoke(null, 30, notice)) throw new AssertionError("non-error level downgraded");
    if ((Boolean) wireframe.invoke(null, 40, "Pipeline {} failed to compile")) {
      throw new AssertionError("a real pipeline failure was downgraded");
    }
    if ((Boolean) wireframe.invoke(null, 40, null)) throw new AssertionError("null format downgraded");
    System.out.println("JVM_BROWSER_LOGGER_OK");
  }
  static final class IOExceptionLike extends Throwable {
    IOExceptionLike(String message) { super(message); }
    IOExceptionLike(String message, Throwable cause) { super(message, cause); }
  }
}
`, "utf8");
  await writeFile(join(teaVmRoot, "JSBody.java"), `package org.teavm.jso; public @interface JSBody { String[] params() default {}; String script(); }\n`, "utf8");
  const slf4j = await resolveSlf4jApi();
  const javaName = process.platform === "win32" ? "java.exe" : "java";
  const javacName = process.platform === "win32" ? "javac.exe" : "javac";
  const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", javaName) : javaName;
  const javac = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", javacName) : javacName;
  const loggerSource = fileURLToPath(new URL(
    "../overrides/libraries/slf4j/src/main/java/org/slf4j/impl/BrowserLogger.java",
    import.meta.url,
  ));
  execFileSync(javac, ["-cp", slf4j, "-d", fixtureRoot,
    join(fixtureRoot, "org", "teavm", "jso", "JSBody.java"),
    loggerSource,
    join(fixtureRoot, "BrowserLoggerFixture.java")], {stdio: "pipe"});
  const output = execFileSync(java, ["-cp", [fixtureRoot, slf4j].join(delimiter), "org.slf4j.impl.BrowserLoggerFixture"], {encoding: "utf8"});
  assert.match(output, /JVM_BROWSER_LOGGER_OK/);
} finally {
  await rm(fixtureRoot, {recursive: true, force: true});
}

console.log(JSON.stringify({
  ok: true,
  trailingThrowableRetained: true,
  placeholderPreserved: true,
  boundedCauseDepth: 4,
  boundedPayloadChars: 4096,
  jsBodyAdapter: "String",
  debugTraceSkippedWhenDisabled: true,
  wireframeNoticeOnlyDowngrade: true,
}));
