import {execFileSync} from "node:child_process";
import {mkdtemp, rm} from "node:fs/promises";
import {homedir, tmpdir} from "node:os";
import {delimiter, join} from "node:path";
import {fileURLToPath} from "node:url";
import {VERSION_PROFILES} from "./verification-profiles.mjs";

const root = fileURLToPath(new URL("../..", import.meta.url));
const scratch = await mkdtemp(join(tmpdir(), "gaius-title-menu-"));
const asmRoot = join(homedir(), ".m2/repository/org/ow2/asm");
const asm = ["asm", "asm-tree"].map(name => join(asmRoot, name, "9.8", `${name}-9.8.jar`));
const run = (command, args) => execFileSync(command, args, {
  cwd: root, encoding: "utf8", timeout: 120000, maxBuffer: 4 * 1024 * 1024,
});
try {
  run("javac", ["-J-Xmx2g", "--release", "21", "-proc:none", "-cp", asm.join(delimiter),
    "-sourcepath", join(root, "port/tools/src/main/java"), "-d", scratch,
    join(root, "port/scripts/fixtures/GaiusTitleMenuFixture.java")]);
  for (const profile of VERSION_PROFILES) {
    const output = run("java", ["-Xmx2g", "-Xverify:all", "-cp", [scratch, ...asm].join(delimiter),
      "GaiusTitleMenuFixture", join(root, "port/work", profile.id, "client-named.jar"),
      join(scratch, `${profile.id}.class`), profile.id]);
    process.stdout.write(output);
  }
} finally {
  await rm(scratch, {recursive: true, force: true});
}
