import {spawnSync} from "node:child_process";
import {existsSync, mkdirSync, readFileSync, writeFileSync} from "node:fs";
import {homedir} from "node:os";
import {delimiter, dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "../../..");
const option = name => {
  const index = process.argv.indexOf(`--${name}`);
  if (index < 0 || !process.argv[index + 1]) throw new Error(`--${name} is required`);
  return resolve(process.argv[index + 1]);
};
const out = option("out");
const scratch = option("patchkit-scratch");
const profile = option("profile-file");
const repository = process.env.MAVEN_REPOSITORY ?? join(homedir(), ".m2/repository");
const version = JSON.parse(readFileSync(join(root, "port/config.json"), "utf8")).teaVMVersion;
const teavm = name => join(repository, `org/teavm/${name}/${version}/${name}-${version}.jar`);
const dependencies = ["teavm-core", "teavm-classlib", "teavm-interop", "teavm-jso", "teavm-jso-apis",
  "teavm-jso-impl", "teavm-platform", "teavm-metaprogramming-api", "teavm-metaprogramming-impl",
  "teavm-relocated-libs-asm", "teavm-relocated-libs-asm-tree", "teavm-relocated-libs-asm-analysis",
  "teavm-relocated-libs-asm-commons", "teavm-relocated-libs-asm-util", "teavm-relocated-libs-hppc",
  "teavm-relocated-libs-rhino", "teavm-extension-apis", "teavm-extension-spi", "teavm-extension-spi-util",
  "teavm-tooling"].map(teavm);
dependencies.push(join(repository, "com/jcraft/jzlib/1.1.3/jzlib-1.1.3.jar"),
  join(repository, "joda-time/joda-time/2.12.2/joda-time-2.12.2.jar"));
const asm = ["asm", "asm-tree"].map(name => join(repository, `org/ow2/asm/${name}/9.8/${name}-9.8.jar`));
for (const file of [...dependencies, ...asm]) if (!existsSync(file)) throw new Error(`offline dependency missing: ${file}`);
const home = process.env.GAIUS_JAVA_HOME ?? process.env.JAVA_HOME;
const tool = name => home ? join(home.replace(/^\/([a-zA-Z])\//, "$1:/"), "bin", name) : name;
mkdirSync(out, {recursive: true});
function run(command, args, label, cwd = out) {
  const result = spawnSync(command, args, {cwd, encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 60000});
  writeFileSync(join(out, `${label}.log`), (result.stdout ?? "") + (result.stderr ?? ""));
  if (result.status !== 0) throw new Error(`${label} failed: ${(result.stderr ?? result.error).slice(-1500)}`);
  console.log(`${label} PASS`);
}
const classes = join(out, "classes");
mkdirSync(classes, {recursive: true});
run(tool("java"), ["-Xmx3g", "-cp", [join(scratch, "classes"), ...asm].join(delimiter),
  "dev.gaius.tools.TeaVMClasslibPatcher", teavm("teavm-classlib"), classes], "classlib-patch");
const roots = ["port/overrides/classlib/src/main/java", "port/src/main/java",
  "port/overrides/jdk/21/src/main/java"].map(name => join(root, name));
run(tool("javac"), ["-J-Xmx3g", "--release", "21", "-proc:none", "-cp", [classes, ...dependencies].join(delimiter),
  "-sourcepath", roots.join(delimiter), "-d", classes, join(here, "BrowserSaveRegression.java"),
  join(here, "fixtures/org/lwjgl/system/MemoryUtil.java"),
  join(root, "port/tests/classlib/driver/ClasslibBuild.java"),
  join(root, "port/src/main/java/dev/gaius/browser/BrowserPngWriter.java"),
  join(root, "port/src/main/java/org/teavm/classlib/java/nio/channels/TFileChannel.java"),
  join(root, "port/overrides/classlib/src/main/java/dev/gaius/browser/BrowserFilePersistence.java"),
  ...["TFileInputStream", "TFileOutputStream"].map(name => join(root,
    `port/overrides/classlib/src/main/java/org/teavm/classlib/java/io/${name}.java`)),
  ...["nio/file/TFilesModernSupport", "io/TFileModernSupport", "util/TCollectionsModernSupport"]
    .map(name => join(root, `port/overrides/jdk/21/src/main/java/org/teavm/classlib/java/${name}.java`))], "compile");
for (const target of ["js", "wasm"]) {
  if (target === "wasm") {
    run(tool("javac"), ["-J-Xmx3g", "--release", "21", "-proc:none", "-cp",
      [classes, ...dependencies].join(delimiter), "-d", classes, join(root,
        "port/overrides/classlib/src/target-wasmgc/java/dev/gaius/browser/JsArrays.java")], "wasm-arrays");
  }
  const destination = join(out, target);
  mkdirSync(destination, {recursive: true});
  run(tool("java"), ["-Xmx3g", "-cp", [classes, ...dependencies].join(delimiter),
    "dev.gaius.classlib.driver.ClasslibBuild", target, destination, "dev.gaius.browser.test.BrowserSaveRegression",
    "SIMPLE", "strict=true"], `${target}-build`);
  if (target === "wasm") {
    const extraction = join(out, "runtime");
    mkdirSync(extraction, {recursive: true});
    run(tool("jar"), ["xf", teavm("teavm-core"), "org/teavm/backend/wasm/wasm-gc-runtime.js"],
      "wasm-runtime-extract", extraction);
    run(process.execPath, [join(root, "port/web/runtime/wasmgc/patch-wasm-gc-runtime.mjs"),
      join(extraction, "org/teavm/backend/wasm/wasm-gc-runtime.js"), join(destination,
        "gaius-wasm-gc-runtime.js")], "wasm-runtime-patch");
  }
  for (const mode of ["save", "restore", "large", "restore-large", "quota"]) {
    run(process.execPath, [join(here, "run-browser.cjs"), target, destination, profile, mode], `${target}-${mode}`);
  }
}
