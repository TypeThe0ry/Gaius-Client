#!/usr/bin/env node
// JVM-only differential execution of vanilla and patched swept-block traversal.
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {existsSync} from 'node:fs';
import {mkdir, mkdtemp, readFile, readdir, rm} from 'node:fs/promises';
import {homedir} from 'node:os';
import {delimiter, join} from 'node:path';
import {fileURLToPath} from 'node:url';
const root = fileURLToPath(new URL('../..', import.meta.url));
const scratch = process.env.GAIUS_BLOCK_TEST_SCRATCH || '/data/scratch/gaius-block-intersection';
const asmRoot = join(homedir(), '.m2/repository/org/ow2/asm');
const asm = [join(asmRoot, 'asm/9.8/asm-9.8.jar'), join(asmRoot, 'asm-tree/9.8/asm-tree-9.8.jar')];
function run(cmd, args) {
  try { return execFileSync(cmd, args, {encoding: 'utf8', timeout: 300_000, maxBuffer: 8 * 1024 * 1024}); }
  catch (error) { process.stderr.write(error.stdout || ''); process.stderr.write(error.stderr || ''); throw error; }
}
await mkdir(scratch, {recursive: true});
const dir = await mkdtemp(join(scratch, 'run-'));
try {
  const versions = join(root, 'port/versions');
  const profiles = (await Promise.all((await readdir(versions)).filter(n => n.endsWith('.json')).map(async n =>
    JSON.parse(await readFile(join(versions, n), 'utf8'))))).filter(p => p.pages?.enabled && p.pages.runtimes?.includes('wasm'));
  assert.ok(profiles.length);
  for (const profile of profiles) {
    const work = join(root, 'port/work', profile.id);
    const jar = join(work, 'client-named.jar');
    assert.ok(existsSync(jar), `missing ${jar}`);
    // Work directories may be shared across worktrees; normalize their library prefixes.
    const raw = (await readFile(join(work, 'classpath.txt'), 'utf8')).trim();
    const dependencies = raw.split(delimiter).map(path => {
      const libraries = path.indexOf('/libraries/');
      return libraries >= 0 ? join(work, path.slice(libraries + 1)) : path;
    }).filter(path => !path.endsWith('client-named.jar'));
    const cp = [...asm, jar, ...dependencies].join(delimiter);
    const classes = join(dir, profile.id);
    await mkdir(classes, {recursive: true});
    run('javac', ['-proc:none', '-encoding', 'UTF-8', '-classpath', cp,
      '-sourcepath', join(root, 'port/tools/src/main/java'), '-d', classes,
      join(root, 'port/tools/src/main/java/dev/gaius/tools/patches/worldgen/BlockIntersectionPatches.java'),
      join(root, 'port/tools/src/test/java/dev/gaius/tools/BlockIntersectionTraversalTest.java')]);
    const output = run('java', ['-Xverify:all', '-XX:-DoEscapeAnalysis', '-classpath', [classes, cp].join(delimiter),
      'dev.gaius.tools.BlockIntersectionTraversalTest', jar]);
    assert.match(output, /BLOCK_INTERSECTION_TEST PASS/);
    process.stdout.write(output);
  }
} finally { await rm(dir, {recursive: true, force: true}); }
