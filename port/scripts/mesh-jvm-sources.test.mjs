import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {mkdtempSync, readFileSync, rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {test} from "node:test";
import {fileURLToPath} from "node:url";
import {compiledText, loadProfiles, resolveSources} from "../tools/sourcesets/sourcesets.mjs";
import {stageMeshJvmSources} from "./mesh-jvm-sources.mjs";

const root = fileURLToPath(new URL("../..", import.meta.url));
const profiles = loadProfiles(root).filter((profile) => profile.kernelMirror?.enabled?.mesher
  && profile.kernelSemantics.code !== 0);
assert(profiles.length > 0, "no modern mesher profiles");
for (const profile of profiles) {
  test(`${profile.id}: JVM mesh sources use bound canonical staging and selected ops`, () => {
    const out = mkdtempSync(join(tmpdir(), "gaius-mesh-sources-"));
    try {
      const staged = stageMeshJvmSources(profile, out, root);
      const resolved = resolveSources(profile, "src", "js", root);
      assert.equal(staged.javaFiles.length, 7);
      for (const [path, digest] of Object.entries(staged.compiledSources)) {
        const text = readFileSync(join(out, path), "utf8");
        assert.equal(text, compiledText(resolved.files.get(path)));
        assert.equal(createHash("sha256").update(text).digest("hex"), digest);
        assert.doesNotMatch(text, /\b__GAIUS_\w+__\b/);
      }
      const mesh = "dev/gaius/browser/kernel/mesh/";
      assert.match(readFileSync(join(out, `${mesh}MeshProfile.java`), "utf8"),
        new RegExp(`static final int PROFILE = ${profile.kernelSemantics.code};`));
      const ops = readFileSync(join(out, `${mesh}MeshVersionOps.java`), "utf8")
        .match(/\bINSTANCE\s*=\s*new\s+([\w$.]+)\s*\(/)[1].replaceAll(".", "/") + ".java";
      assert(staged.compiledSources[ops]);
      assert(staged.sourceFiles.includes(resolved.files.get(ops).path));
    } finally {
      rmSync(out, {recursive: true, force: true});
    }
  });
}
