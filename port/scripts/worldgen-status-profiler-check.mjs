import {mkdirSync, readFileSync, writeFileSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
import {delimiter, dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const [measurementPath, destination] = process.argv.slice(2);
if (!destination) throw new Error('Usage: node worldgen-status-profiler-check.mjs <profile-run> <output>');
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const output = resolve(destination);
if (output.toLowerCase() === root.toLowerCase()
    || output.toLowerCase().startsWith(root.toLowerCase() + '/')
    || output.toLowerCase().startsWith(root.toLowerCase() + '\\')) {
  throw new Error('Use a separate output directory');
}
const measurement = resolve(measurementPath);
const provenance = JSON.parse(readFileSync(join(measurement, 'provenance.json'), 'utf8'));
const profile = JSON.parse(readFileSync(join(root, 'port/versions', provenance.profile + '.json'), 'utf8'));
if (profile.id !== provenance.profile) throw new Error('Invalid profile');
const work = join(root, 'port/work', profile.id);
const text = readFileSync(join(work, 'classpath.txt'), 'utf8').trim();
const libraries = text.split(text.startsWith('/') ? ':' : delimiter)
  .map(path => path.replace(/^\/([a-z])\//i, (_, drive) => drive + ':/'));
const classpath = [join(measurement, 'classes'), join(work, 'client-named.jar'), ...libraries,
  ...provenance.asm.map(item => item.path)].join(delimiter);
mkdirSync(output, {recursive: true});
const tool = name => process.env.GAIUS_JAVA_HOME ? join(process.env.GAIUS_JAVA_HOME, 'bin', name) : name;
function run(name, argumentsList) {
  const result = spawnSync(tool(name), argumentsList, {encoding: 'utf8', timeout: 120000});
  writeFileSync(join(output, name + '.log'), (result.stdout ?? '') + (result.stderr ?? ''));
  if (result.status !== 0) throw new Error(name + ' failed: ' + result.stderr);
}
run('javac', ['-J-Xmx3g', '-proc:none', '-cp', classpath, '-d', output,
  join(root, 'port/scripts/worldgen-status-perf/ProfilerExceptionFixture.java'),
  join(root, 'port/scripts/worldgen-status-perf/ProfilerExceptionCheck.java')]);
run('java', ['-Xmx3g', '-cp', [output, classpath].join(delimiter), 'dev.gaius.wgperf.ProfilerExceptionCheck',
  join(output, 'report.json')]);
const report = JSON.parse(readFileSync(join(output, 'report.json'), 'utf8'));
if (Object.keys(report.statuses).length !== 5 || Object.values(report.statuses).some(status => status.calls !== 1)) {
  throw new Error('Incorrect nested exception accounting');
}
console.log('Profiler: five balanced calls; implicit, explicit and internally caught exceptions preserved');
