import {CANONICAL_PROFILE} from "./verification-profiles.mjs";
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdir, mkdtemp, readFile, readdir, rm, writeFile} from 'node:fs/promises';
import {homedir, tmpdir} from 'node:os';
import {delimiter, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

// Minecraft 26.2 only. BrowserNoiseGraphMapper (port/mc/shared/canonical/client) memoizes the
// per-chunk NoiseChunk.wrap graph mapping of the 26.2 density runtime. 26.3 compiles each
// density graph once per RandomState, has no NoiseRouter/DensityFunction.mapAll and no
// DensityFunction$Visitor, and registers Minecraft262BrowserPatcher.patchNoiseChunkGraphMapper
// as dropped (migration plan D6), so there is no 26.3 variant of this smoke. Passing
// --profile with another id fails instead of reporting coverage that does not exist.
const root = fileURLToPath(new URL('../../', import.meta.url));
const profileIndex = process.argv.indexOf('--profile');
if (profileIndex >= 0 && process.argv[profileIndex + 1] !== CANONICAL_PROFILE.id) {
  console.error(`noise-graph-mapper-smoke: ${CANONICAL_PROFILE.id} only; profile ${process.argv[profileIndex + 1]} `
    + 'drops Minecraft262BrowserPatcher.patchNoiseChunkGraphMapper (compiled density runtime)');
  process.exit(2);
}
const positional = process.argv.slice(2).filter((value, index, all) => value !== '--real'
  && value !== '--teavm' && value !== '--profile' && all[index - 1] !== '--profile');
const helper = resolve(positional[0] || join(root,
  'port/mc/shared/canonical/client/java/net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper.java'));
const fixture = resolve(positional[1] || join(root, 'port/scripts/fixtures/NoiseGraphMapperFixture.java'));
const realFixture = resolve(join(root, 'port/scripts/fixtures/RealNoiseGraphMapperFixture.java'));
assert.ok(await readFile(helper, 'utf8'), '' + CANONICAL_PROFILE.id + ' BrowserNoiseGraphMapper source is required');
if (process.argv.includes('--real')) {
  const classpathFile = resolve(positional[2] || join(root, 'port/work/' + CANONICAL_PROFILE.id + '/classpath.txt'));
  const rawClasspath = (await readFile(classpathFile, 'utf8')).trim();
  const separator = process.platform === 'win32' && rawClasspath.includes(';') ? ';' : /:(?![\\/])/;
  // fetch-version.sh writes a POSIX list; accept native Windows lists as well.
  const paths = rawClasspath.startsWith('/') ? rawClasspath.split(':') : rawClasspath.split(separator);
  const libraries = paths.filter(Boolean).map(value =>
    process.platform === 'win32' && /^\/[a-zA-Z]\//.test(value)
      ? value[1].toUpperCase() + ':' + value.slice(2).replaceAll('/', '\\') : value);
  const namedJar = join(root, 'port/work/' + CANONICAL_PROFILE.id + '/client-named.jar');
  const realTemp = await mkdtemp(join(tmpdir(), 'gaius-real-noise-graph-'));
  try {
    const files = [helper, realFixture];
    const cp = [namedJar, ...libraries].join(delimiter);
    const javaSuffix = process.platform === 'win32' ? '.exe' : '';
    execFileSync(process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', `javac${javaSuffix}`) : 'javac',
      ['--release', '21', '-cp', cp, '-d', realTemp, ...files], {stdio: 'pipe'});
    const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', `java${javaSuffix}`) : 'java';
    const output = execFileSync(java, ['-cp', [realTemp, namedJar, ...libraries].join(delimiter),
      'net.minecraft.world.level.levelgen.RealNoiseGraphMapperFixture'],
      {encoding: 'utf8', timeout: 30000});
    const resultLine = output.split(/\r?\n/).find(line => line.includes('REAL_NOISE_GRAPH_MAPPER_OK'));
    assert.ok(resultLine, `real fixture output missing: ${output}`);
    console.log(resultLine.slice(resultLine.indexOf('REAL_NOISE_GRAPH_MAPPER_OK')));
  } finally {
    await rm(realTemp, {recursive: true, force: true});
  }
  process.exit(0);
}
const temp = await mkdtemp(join(tmpdir(), 'gaius-noise-graph-mapper-'));
try {
  const sources = {
    'net/minecraft/world/level/levelgen/DensityFunction.java': `package net.minecraft.world.level.levelgen;
public interface DensityFunction {
    interface Visitor {
        DensityFunction apply(DensityFunction input);
        NoiseHolder visitNoise(NoiseHolder noise);
    }
    final class NoiseHolder {
        final String name;
        NoiseHolder(String name) { this.name = name; }
    }
    DensityFunction mapChildren(Visitor visitor);
    double compute(double x, double y, double z);
    double minValue();
    double maxValue();
    default DensityFunction mapAll(Visitor visitor) {
        class Recursive implements Visitor {
            public DensityFunction apply(DensityFunction input) { return visitor.apply(input.mapChildren(this)); }
            public NoiseHolder visitNoise(NoiseHolder noise) { return visitor.visitNoise(noise); }
        }
        return new Recursive().apply(this);
    }
}
`,
    'net/minecraft/world/level/levelgen/NoiseRouter.java': `package net.minecraft.world.level.levelgen;
public final class NoiseRouter {
    private final DensityFunction[] fields;
    public NoiseRouter(DensityFunction... fields) {
        if (fields.length != 15) throw new AssertionError("NoiseRouter field count");
        this.fields = fields;
    }
    DensityFunction barrierNoise() { return fields[0]; }
    DensityFunction fluidLevelFloodednessNoise() { return fields[1]; }
    DensityFunction fluidLevelSpreadNoise() { return fields[2]; }
    DensityFunction lavaNoise() { return fields[3]; }
    DensityFunction temperature() { return fields[4]; }
    DensityFunction vegetation() { return fields[5]; }
    DensityFunction continents() { return fields[6]; }
    DensityFunction erosion() { return fields[7]; }
    DensityFunction depth() { return fields[8]; }
    DensityFunction ridges() { return fields[9]; }
    DensityFunction preliminarySurfaceLevel() { return fields[10]; }
    DensityFunction finalDensity() { return fields[11]; }
    DensityFunction veinToggle() { return fields[12]; }
    DensityFunction veinRidged() { return fields[13]; }
    DensityFunction veinGap() { return fields[14]; }
}
`,
    'net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper.java': await readFile(helper, 'utf8'),
    'net/minecraft/world/level/levelgen/NoiseGraphMapperFixture.java': await readFile(fixture, 'utf8'),
  };
  const files = [];
  for (const [name, content] of Object.entries(sources)) {
    const path = join(temp, name);
    await mkdir(resolve(path, '..'), {recursive: true});
    await writeFile(path, content);
    files.push(path);
  }
  if (process.argv.includes('--teavm')) {
    const repository = process.env.M2_REPO || join(homedir(), '.m2', 'repository');
    const jars = [];
    for (const artifact of await readdir(join(repository, 'org', 'teavm'))) {
      const directory = join(repository, 'org', 'teavm', artifact, '0.15.0');
      try {
        for (const name of await readdir(directory)) {
          if (name.endsWith('.jar') && !name.includes('-sources') && !name.includes('-javadoc')) jars.push(join(directory, name));
        }
      } catch { /* only installed TeaVM 0.15 artifacts */ }
    }
    assert.ok(jars.some(name => name.includes('teavm-tooling')), 'TeaVM tooling is required for --teavm');
    const compiler = join(temp, 'CompileNoiseFixture.java');
    await writeFile(compiler, `import java.io.File; import org.teavm.backend.javascript.JSModuleType; import org.teavm.tooling.TeaVMTool;
public class CompileNoiseFixture { public static void main(String[] args) throws Exception {
TeaVMTool tool = new TeaVMTool(); tool.setMainClass("net.minecraft.world.level.levelgen.NoiseGraphMapperFixture");
tool.setTargetDirectory(new File(args[0])); tool.setTargetFileName("fixture.cjs"); tool.setJsModuleType(JSModuleType.COMMON_JS);
tool.setObfuscated(false); tool.setClassLoader(ClassLoader.getSystemClassLoader()); tool.generate();
for (var problem : tool.getProblemProvider().getSevereProblems()) System.err.println(problem.getText());
if (!tool.getProblemProvider().getSevereProblems().isEmpty()) throw new AssertionError("TeaVM compilation failed"); }}
`);
    const javaSuffix = process.platform === 'win32' ? '.exe' : '';
    const cp = [temp, ...jars].join(delimiter);
    execFileSync(process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', `javac${javaSuffix}`) : 'javac',
      ['--release', '21', '-cp', cp, '-d', temp, ...files, compiler], {stdio: 'pipe'});
    const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', `java${javaSuffix}`) : 'java';
    execFileSync(java, ['-cp', cp, 'CompileNoiseFixture', temp], {encoding: 'utf8', timeout: 120000});
    const runner = join(temp, 'run.cjs');
    await writeFile(runner, `require('./fixture.cjs').main([], function(error) { if (error) { console.error(error); process.exit(1); }
console.log('TEAVM_NOISE_GRAPH_MAPPER_OK'); process.exit(0); });
`);
    const output = execFileSync(process.execPath, [runner], {encoding: 'utf8', timeout: 15000});
    assert.match(output, /^NOISE_GRAPH_MAPPER_OK compute=26\.0 min=26\.0 max=26\.0 apply=4 noise=1\s*$/m);
    assert.match(output, /TEAVM_NOISE_GRAPH_MAPPER_OK/);
    console.log('TEAVM_NOISE_GRAPH_MAPPER_OK');
  } else {
  const javaSuffix = process.platform === 'win32' ? '.exe' : '';
  execFileSync(process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', `javac${javaSuffix}`) : 'javac',
    ['--release', '21', '-d', temp, ...files], {stdio: 'pipe'});
  const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', `java${javaSuffix}`) : 'java';
  const output = execFileSync(java, ['-cp', temp, 'net.minecraft.world.level.levelgen.NoiseGraphMapperFixture'],
    {encoding: 'utf8', timeout: 15000});
  assert.match(output.trim(), /^NOISE_GRAPH_MAPPER_OK compute=/);
  console.log(output.trim());
  }
} finally {
  await rm(temp, {recursive: true, force: true});
}
