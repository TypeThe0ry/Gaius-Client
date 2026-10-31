import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {existsSync, readFileSync} from "node:fs";
import {join} from "node:path";
import {MANIFEST, stageSources} from "../tools/sourcesets/sourcesets.mjs";

export function stageMeshJvmSources(profile, out, root) {
  const staged = stageSources(profile, "src", "js", out, root);
  const mesh = "dev/gaius/browser/kernel/mesh/";
  const paths = ["MeshTableWriter", "ModelTableExporter", "MeshModelTable", "MeshProfile", "MeshVersionOps"]
    .map((name) => `${mesh}${name}.java`);
  paths.push("net/minecraft/world/level/block/state/GaiusMeshBlockAccess.java");
  const ops = readFileSync(join(out, `${mesh}MeshVersionOps.java`), "utf8")
    .match(/\bINSTANCE\s*=\s*new\s+([\w$.]+)\s*\(/);
  assert(ops, "staged MeshVersionOps must select an implementation");
  paths.push(`${ops[1].replaceAll(".", "/")}.java`);
  for (const path of paths) {
    assert(staged.files.has(path), `missing canonical mesh source: ${path}`);
    assert(!/\b__GAIUS_\w+__\b/.test(readFileSync(join(out, path), "utf8")),
      `unbound canonical mesh source: ${path}`);
  }
  const sourceFiles = [join(root, MANIFEST), ...paths.map((path) => staged.files.get(path).path),
    ...staged.sets.map((name) => join(root, "port/mc/shared", name, "relocations.tsv"))
      .filter(existsSync)];
  const compiledSources = Object.fromEntries(paths.map((path) => [path,
    createHash("sha256").update(readFileSync(join(out, path))).digest("hex")]));
  return {javaFiles: paths.map((path) => join(out, path)), sourceFiles, compiledSources};
}
