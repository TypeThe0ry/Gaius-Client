#!/usr/bin/env node
import {mkdir, readFile} from 'node:fs/promises';
import {delimiter, join, resolve} from 'node:path';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {homedir} from 'node:os';
import {loadProfiles} from '../tools/kernelmirror/kernelmirror.mjs';

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const outputArg = process.argv[2];
const profileFlag = process.argv.indexOf('--profile');
const profileId = profileFlag >= 0 ? process.argv[profileFlag + 1] : undefined;
const profiles = loadProfiles(root).filter(profile => profile.worldgenReference?.biomeKernelCheck);
const profile = profiles.find(candidate => candidate.id === profileId);
if (!outputArg || !profile) {
  throw new Error(`Usage: node port/scripts/worldgen-biome-kernel-check.mjs <output-directory> --profile ${profiles.map(candidate => candidate.id).join('|')}`);
}
const biomeCheck = profile.worldgenReference.biomeKernelCheck;
const output = resolve(outputArg);
const work = resolve(root, `port/work/${profile.id}`);
const jar = join(work, 'client-named.jar');
const rawClasspath = (await readFile(join(work, 'classpath.txt'), 'utf8')).trim();
const libraries = process.platform === 'win32'
  ? rawClasspath.replace(/\/([A-Za-z])\//g, '$1:/').replace(/:(?=[A-Za-z]:\/)/g, ';')
  : rawClasspath;
const classpath = [jar, libraries].join(delimiter);
const repository = process.env.GAIUS_MAVEN_REPOSITORY || resolve(homedir(), '.m2/repository');
const maven = join(repository, 'org/teavm');
const teavmApis = join(maven, 'teavm-jso-apis/0.15.0/teavm-jso-apis-0.15.0.jar');
const teavmJso = join(maven, 'teavm-jso/0.15.0/teavm-jso-0.15.0.jar');
const compileClasspath = [classpath, teavmJso, teavmApis].join(delimiter);
const classes = join(output, 'classes');
const source = join(root, 'port', biomeCheck.bridge);
const check = join(root, 'port', biomeCheck.check);
const sourcepath = [
  join(root, 'port/src/main/java'),
  join(root, 'port/overrides/classlib/src/main/java'),
  join(root, 'port/overrides/classlib/src/target-wasmgc/java'),
  join(root, 'port/mc/shared/renderpearl-1/src/java'),
  join(root, 'port/mc/shared/canonical/src/java'),
].join(delimiter);
await mkdir(classes, {recursive: true});

function run(command, args) {
  return new Promise((resolveRun, reject) => {
    const child = spawn(command, args, {stdio: 'inherit', windowsHide: true, cwd: output});
    child.on('error', reject);
    child.on('close', code => code === 0 ? resolveRun() : reject(new Error(`${command} exited ${code}`)));
  });
}

await run(process.env.JAVAC || 'javac', [
  '-J-Duser.language=en', '-nowarn', '-proc:none', '-encoding', 'UTF-8', '-d', classes,
  '-cp', compileClasspath, '-sourcepath', sourcepath, source, check,
]);
await run(process.env.JAVA || 'java', [
  '-cp', [classes, compileClasspath].join(delimiter),
  biomeCheck.mainClass,
]);
