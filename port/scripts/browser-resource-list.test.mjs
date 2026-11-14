// Old-vs-new test of dev.gaius.browser.BrowserResourceList, the reader of the browser resource list
// (dev/gaius/browser/minecraft-resources.txt) in the vanilla pack overrides:
//
// - every profile's pack override (FixedPathPackResources or VanillaPackResources, as the profile's
//   source sets resolve it) reads the list through BrowserResourceList.parse / sortedCopy and no
//   longer splits it with a regular expression;
// - port/scripts/fixtures/ResourceListDifferential.java compares parse and sortedCopy with the
//   replaced code (split("\\r?\\n") + trim, Arrays.sort of a copy) on each list: the lists given
//   with --list (resource lists of real builds) and, for every profile with a client jar, the list
//   build-teavm.sh writes before the sounds and fonts are added (the jar's assets/, data/ and
//   pack.png entries in jar order), each also with CRLF, padding, shuffled and duplicated
//   variants, plus random texts.
//
//   node port/scripts/browser-resource-list.test.mjs [--list <minecraft-resources.txt>]...
import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {fileURLToPath} from "node:url";
import {sourceFile} from "../tools/sourcesets/sourcesets.mjs";

const root = fileURLToPath(new URL("../..", import.meta.url));
const lists = process.argv.flatMap((value, index, all) => all[index - 1] === "--list" ? [value] : []);
const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
const exe = name => home ? join(home, "bin", process.platform === "win32" ? `${name}.exe` : name) : name;

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {encoding: "utf8", maxBuffer: 256 << 20, ...options});
  assert.equal(result.status, 0, `${command} failed\n${(result.stdout || "").slice(-4000)}${(result.stderr || "").slice(-4000)}`);
  return result.stdout;
}

const profiles = readdirSync(join(root, "port/versions")).filter(name => name.endsWith(".json"))
  .map(name => name.slice(0, -".json".length)).sort();
const helperRel = "dev/gaius/browser/BrowserResourceList.java";
const out = mkdtempSync(join(tmpdir(), "gaius-resource-list-"));
try {
  let helperPath;
  for (const profile of profiles) {
    for (const target of ["js", "wasmgc"]) {
      const overrides = ["net/minecraft/server/packs/FixedPathPackResources.java",
        "net/minecraft/server/packs/VanillaPackResources.java"]
        .map(rel => sourceFile(profile, "client", rel, {target, root}))
        .filter(file => file && file.text.includes("RESOURCE_LIST"));
      assert.equal(overrides.length, 1, `${profile} ${target}: one pack override reads the resource list`);
      const text = overrides[0].text;
      assert.match(text, /BrowserResourceList\.parse\(/, `${overrides[0].path} does not parse through BrowserResourceList`);
      assert.match(text, /BrowserResourceList\.sortedCopy\(/, `${overrides[0].path} does not sort through BrowserResourceList`);
      assert.doesNotMatch(text, /\.split\(/, `${overrides[0].path} still splits with a regular expression`);
      const helper = sourceFile(profile, "client", helperRel, {target, root});
      assert.ok(helper, `${profile} ${target} compiles no BrowserResourceList`);
      assert.ok(!helperPath || helperPath === helper.path, "one BrowserResourceList source for every profile");
      helperPath = helper.path;
      console.log(`OVERRIDE ${profile} ${target} ${overrides[0].path.slice(root.length)}`);
    }
  }

  const inputs = [...lists];
  for (const profile of profiles) {
    const jar = join(root, "port/work", profile, "client-named.jar");
    if (!existsSync(jar)) {
      console.log(`SKIP ${profile}: no ${jar}`);
      continue;
    }
    const entries = run(exe("jar"), ["tf", jar]).split("\n").map(line => line.replace(/\r$/, ""))
      .filter(name => (name.startsWith("assets/") || name.startsWith("data/") || name === "pack.png") && !name.endsWith("/"));
    const file = join(out, `jar-list-${profile}.txt`);
    writeFileSync(file, entries.map(line => `${line}\n`).join(""));
    inputs.push(file);
  }
  assert.ok(inputs.length > 0, "no resource list to compare");

  const classes = join(out, "classes");
  mkdirSync(classes);
  run(exe("javac"), ["-nowarn", "-encoding", "UTF-8", "-d", classes, helperPath,
    join(root, "port/scripts/fixtures/ResourceListDifferential.java")]);
  const result = run(exe("java"), ["-cp", classes, "ResourceListDifferential", ...inputs]);
  for (const line of result.trim().split("\n")) console.log(line.replace(out, "<tmp>"));
  assert.match(result, /RESOURCE_LIST_DIFFERENTIAL_OK checks=\d+/);
  console.log(`BROWSER_RESOURCE_LIST_TEST PASS profiles=${profiles.join(",")} lists=${inputs.length}`);
} finally {
  rmSync(out, {recursive: true, force: true});
}
