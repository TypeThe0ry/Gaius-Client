import {spawnSync} from "node:child_process";
import {copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync} from "node:fs";
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
const repository = process.env.MAVEN_REPOSITORY ?? join(homedir(), ".m2/repository");
const home = process.env.GAIUS_JAVA_HOME ?? process.env.JAVA_HOME;
const tool = name => home ? join(home.replace(/^\/([a-zA-Z])\//, "$1:/"), "bin", name) : name;
const asm = ["asm", "asm-tree"].map(name => join(repository, `org/ow2/asm/${name}/9.8/${name}-9.8.jar`));
for (const file of asm) if (!existsSync(file)) throw new Error(`offline dependency missing: ${file}`);
const classes = join(out, "classes");
mkdirSync(classes, {recursive: true});
function run(name, args, label) {
  const result = spawnSync(tool(name), args, {encoding: "utf8", maxBuffer: 4 * 1024 * 1024});
  writeFileSync(join(out, `${label}.log`), (result.stdout ?? "") + (result.stderr ?? ""));
  if (result.status !== 0) throw new Error(`${label} failed: ${result.stderr ?? result.error}`);
  if (result.stdout.trim()) console.log(result.stdout.trim());
}
run("javac", ["-J-Xmx3g", "--release", "21", "-proc:none", "-cp", asm.join(delimiter), "-d", classes,
  join(root, "port/src/main/java/dev/gaius/browser/BrowserPngWriter.java"),
  join(here, "fixtures/org/lwjgl/system/MemoryUtil.java"), join(here, "fixtures/com/mojang/jtracy/MemoryPool.java"),
  join(here, "fixtures/com/mojang/jtracy/TracyClient.java"), join(here, "PngRegression.java"),
  join(here, "PatchRegression.java")], "compile");
const profiles = readdirSync(join(root, "port/versions")).filter(name => name.endsWith(".json"))
  .map(name => JSON.parse(readFileSync(join(root, "port/versions", name), "utf8")));
for (const profile of profiles) {
  const work = join(root, "port/work", profile.id);
  const original = join(work, "client-named.jar");
  if (!existsSync(original)) throw new Error(`vanilla jar missing: ${profile.id}`);
  const text = readFileSync(join(work, "classpath.txt"), "utf8").trim();
  const libraries = (text.startsWith("/") ? text.split(":") : text.split(delimiter))
    .filter(entry => entry && !/client-(named|original)\.jar$/.test(entry))
    .map(entry => {
      const marker = `/port/work/${profile.id}/libraries/`;
      const index = entry.replaceAll("\\", "/").indexOf(marker);
      return index < 0 ? entry.replace(/^\/([a-zA-Z])\//, "$1:/")
        : join(work, "libraries", entry.slice(index + marker.length));
    });
  for (const file of libraries) if (!existsSync(file)) throw new Error(`offline library missing: ${file}`);
  for (const target of ["wasmgc", "js"]) {
    const patched = join(scratch, `${profile.id}-${target}/out`);
    const runtime = join(out, `${profile.id}-${target}`);
    const image = "com/mojang/blaze3d/platform/NativeImage.class";
    mkdirSync(dirname(join(runtime, image)), {recursive: true});
    copyFileSync(join(patched, image), join(runtime, image));
    run("java", ["-Xmx3g", "-cp", [classes, ...asm].join(delimiter), "dev.gaius.browser.test.PatchRegression",
      original, patched], `${profile.id}-${target}-patch`);
    run("java", ["-Xmx3g", "-cp", [runtime, classes, original, ...libraries].join(delimiter),
      "dev.gaius.browser.test.PngRegression", runtime], `${profile.id}-${target}-png`);
  }
}
