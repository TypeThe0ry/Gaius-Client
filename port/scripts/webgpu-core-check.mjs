#!/usr/bin/env node
import {mkdir, readFile, readdir, writeFile} from 'node:fs/promises';
import {delimiter, join, resolve} from 'node:path';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {homedir} from 'node:os';
import {VERSION_PROFILES} from './verification-profiles.mjs';

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const outputArg = process.argv[2];
const profileFlag = process.argv.indexOf('--profile');
const testFlag = process.argv.indexOf('--test');
const profile = profileFlag >= 0 ? process.argv[profileFlag + 1] : undefined;
const test = testFlag >= 0 ? process.argv[testFlag + 1] : undefined;
if (!outputArg || !VERSION_PROFILES.some(candidate => candidate.id === profile)
    || (testFlag >= 0 && !/^(?:dev\.gaius\.gpu\.webgpu\.)?\w+$/.test(test || ''))) {
  throw new Error('Usage: node port/scripts/webgpu-core-check.mjs <output-directory> --profile <profile id> [--test <Class>]');
}
const output = resolve(outputArg);
const work = join(root, 'port/work', profile);
const rawClasspath = (await readFile(join(work, 'classpath.txt'), 'utf8')).trim();
const libraries = process.platform === 'win32'
  ? rawClasspath.replace(/\/([A-Za-z])\//g, '$1:/').replace(/:(?=[A-Za-z]:\/)/g, ';')
  : rawClasspath;
const repository = process.env.GAIUS_MAVEN_REPOSITORY || resolve(homedir(), '.m2/repository');
const teavm = name => join(repository, 'org/teavm', name, '0.15.0', `${name}-0.15.0.jar`);
const compileClasspath = [join(work, 'client-named.jar'), libraries, teavm('teavm-jso'), teavm('teavm-jso-apis')].join(delimiter);
const mainRoot = join(root, 'port/gpu/webgpu-core/src/main/java');
const testRoot = join(root, 'port/gpu/webgpu-core/src/test/java');
const packagePath = 'dev/gaius/gpu/webgpu';
const classes = join(output, 'classes');
await mkdir(classes, {recursive: true});
const sources = (await readdir(join(mainRoot, packagePath))).filter(file => file.endsWith('.java'))
  .map(file => join(mainRoot, packagePath, file));
const tests = test ? [test.split('.').at(-1)]
  : (await readdir(join(testRoot, packagePath))).filter(file => file.endsWith('Test.java')).map(file => file.slice(0, -5));
sources.push(...tests.map(name => join(testRoot, packagePath, `${name}.java`)));
const sourcepath = [mainRoot, testRoot, join(root, 'port/src/main/java'),
  join(root, 'port/overrides/classlib/src/main/java'), join(root, 'port/overrides/classlib/src/target-wasmgc/java'),
  join(root, 'port/mc/shared/renderpearl-1/src/java'), join(root, 'port/mc/shared/canonical/src/java')].join(delimiter);
// An argument file keeps the real profile library classpath below the Windows command limit.
const quote = value => `"${String(value).replaceAll('\\', '/').replaceAll('"', '\\"')}"`;
const args = ['-nowarn', '-proc:none', '-encoding', 'UTF-8', '-d', classes,
  '-cp', compileClasspath, '-sourcepath', sourcepath, ...sources];
const argfile = join(output, 'javac.args');
await writeFile(argfile, args.map(quote).join('\n'));
async function run(command, commandArgs) {
  console.log(command, ...commandArgs);
  await new Promise((resolveRun, reject) => {
    const child = spawn(command, commandArgs, {stdio: 'inherit', windowsHide: true, cwd: root});
    child.on('error', reject);
    child.on('close', code => code === 0 ? resolveRun() : reject(new Error(`${command} exited ${code}`)));
  });
}
await run(process.env.JAVAC || 'javac', ['-J-Duser.language=en', `@${argfile}`]);
for (const name of tests) {
  const javaArgs = join(output, `${name}.args`);
  await writeFile(javaArgs, ['-cp', [classes, compileClasspath].join(delimiter), `dev.gaius.gpu.webgpu.${name}`].map(quote).join('\n'));
  await run(process.env.JAVA || 'java', [`@${javaArgs}`]);
}
console.log(`WebGPU core check passed: ${profile}, ${tests.length} test(s)`);
