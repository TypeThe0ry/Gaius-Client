// The source text of a patcher step together with the domain classes that hold its patch code
// (port/mc/patches/<domain>; port/tools/patchkit/README.md "Domains"), for smokes that
// check a step's patch code by its text. A domain class names its step in its class comment:
// "The <domain> domain of the <Patcher> step"; so does the support class of the step's shared
// helpers (dev.gaius.tools.patches.support: "... more than one domain of the <Patcher> step
// uses"), which is read with it.
//
//   import {patcherSource} from "./patcher-sources.mjs";
//   const clientPatcher = patcherSource("MinecraftClientPatcher");

import {existsSync, readdirSync, readFileSync} from "node:fs";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

export const toolsPackage = resolve(dirname(fileURLToPath(import.meta.url)),
  "../tools/src/main/java/dev/gaius/tools");

/** The domain class files of a patcher step, sorted by path. */
export function domainClassFiles(patcher, root = toolsPackage) {
  const base = join(root, "patches");
  if (!existsSync(base)) return [];
  const marker = new RegExp(`domain of the ${patcher} step\\b`);
  const files = [];
  for (const domain of readdirSync(base, {withFileTypes: true}).filter((entry) => entry.isDirectory())) {
    for (const name of readdirSync(join(base, domain.name)).filter((file) => file.endsWith(".java"))) {
      const file = join(base, domain.name, name);
      if (marker.test(readFileSync(file, "utf8").replace(/\s+\*\s+/g, " "))) files.push(file);
    }
  }
  return files.sort();
}

/** The patcher's source followed by its domain classes' sources. */
export function patcherSource(patcher, root = toolsPackage) {
  return [join(root, `${patcher}.java`), ...domainClassFiles(patcher, root)]
    .map((file) => readFileSync(file, "utf8")).join("\n");
}

// A member declaration of a top-level class (four-space indentation), or its Javadoc.
const MEMBER_START = /\n {4}(?=\S)(?:\/\*\*|@|(?:(?:public|protected|private|static|final|synchronized)\s+)*[\w$<>[\],.? ]+\()/g;

/**
 * The text of the method `name` in a source (for example a patcherSource): from its declaration,
 * whatever its access modifier (the code moved into a domain class is public), to the start of
 * the next member of its class. Empty when the source declares no such method.
 */
export function memberText(source, name) {
  const declaration = new RegExp(
    `\\n {4}(?=\\S)(?:(?:public|protected|private|static|final|synchronized)\\s+)*[\\w$<>[\\],.? ]+\\s${name}\\(`);
  const match = declaration.exec(source);
  if (!match) return "";
  const start = match.index + 1;
  MEMBER_START.lastIndex = start + match[0].length - 1;
  const next = MEMBER_START.exec(source);
  const classEnd = source.indexOf("\n}", start);
  const end = [next ? next.index : -1, classEnd].filter((value) => value > start);
  return source.slice(start, end.length ? Math.min(...end) : source.length);
}
