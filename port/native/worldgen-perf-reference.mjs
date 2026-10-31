import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { dirname, resolve, basename, delimiter } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { waitJvmHeadroom } from '../scripts/worldgen-correctness-support.mjs';

const port = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const [destination, selectedProfile, ...positions] = process.argv.slice(2);
if (!destination) throw new Error('Usage: node worldgen-perf-reference.mjs <output-directory> [profile] [x,z ...]');
const chunks = positions.length ? positions : ['0,0', '7,-3', '-20,11'];
const seed = process.env.GAIUS_WORLDGEN_REFERENCE_SEED ?? '12345';
const rounds = Number(process.env.GAIUS_WORLDGEN_REFERENCE_ROUNDS ?? '5');
if (!/^-?\d+$/.test(seed) || BigInt(seed).toString() !== seed
    || BigInt(seed) < -(1n << 63n) || BigInt(seed) >= (1n << 63n)) {
  throw new Error('Invalid Java long seed');
}
if (!Number.isInteger(rounds) || rounds < 1 || rounds > 20) throw new Error('Invalid reference rounds');
for (const chunk of chunks) {
  if (!/^-?\d+,-?\d+$/.test(chunk)
      || chunk.split(',').some(value => BigInt(value).toString() !== value
        || BigInt(value) < -(1n << 31n) || BigInt(value) >= (1n << 31n))) {
    throw new Error('Invalid chunk coordinates: ' + chunk);
  }
}
const javaHome = process.env.GAIUS_JAVA_HOME ?? process.env.JAVA_HOME;

function replaceOnce(source, previous, next) {
  if (source.split(previous).length !== 2) throw new Error('Reference adapter mismatch: ' + previous.slice(0, 80));
  return source.replace(previous, next);
}

function legacyExporter(source) {
  source = replaceOnce(source, 'private static final Class<?> INTERVAL_SELECT = DensityFunctions.intervalSelect(\n            DensityFunctions.zero(), it.unimi.dsi.fastutil.doubles.DoubleList.of(0.0),\n            List.of(DensityFunctions.zero(), DensityFunctions.zero())).getClass();', 'private static final Class<?> INTERVAL_SELECT = null;');
  source = source.replaceAll('CubicSpline<DensityFunctions.Spline.Coordinate>', 'CubicSpline<?, ?>')
    .replaceAll('CubicSpline.Constant<DensityFunctions.Spline.Coordinate>', 'CubicSpline.Constant<?, ?>')
    .replaceAll('CubicSpline.Multipoint<DensityFunctions.Spline.Coordinate>', 'CubicSpline.Multipoint<?, ?>');
  source = replaceOnce(source, 'node(m.coordinate().function())', 'node(((DensityFunctions.Spline.Coordinate) m.coordinate()).function().value())');
  for (const [colour, field] of Object.entries({ white: 'WHITE', orange: 'ORANGE', yellow: 'YELLOW', brown: 'BROWN', red: 'RED', lightGray: 'LIGHT_GRAY' })) {
    source = replaceOnce(source, 'Blocks.DYED_TERRACOTTA.' + colour + '()', 'Blocks.' + field + '_TERRACOTTA');
  }
  source = replaceOnce(source, 'boolean legacy = holder.is(Noises.TEMPERATURE_NETHER) || holder.is(Noises.VEGETATION_NETHER);', 'boolean legacy = false;');
  source = replaceOnce(source, 'long legacyOffset = holder.is(Noises.TEMPERATURE_NETHER) ? 0L : 1L;', 'long legacyOffset = 0L;');
  source = replaceOnce(source, 'DensityFunction shallow = f.mapChildren(child -> DensityFunctions.zero());', 'DensityFunction shallow = f;');
  source = replaceOnce(source, '        f.mapChildren(new DensityFunction.Visitor() {\n            @Override\n            public DensityFunction apply(DensityFunction child) {\n                children.add(child);\n                return child;\n            }\n        });', '        for (java.lang.reflect.RecordComponent component : f.getClass().getRecordComponents()) {\n            try {\n                java.lang.reflect.Method accessor = component.getAccessor();\n                if (!accessor.trySetAccessible()) throw new IllegalStateException("record accessor");\n                Object value = accessor.invoke(f);\n                if (value instanceof DensityFunction child) children.add(child);\n            } catch (ReflectiveOperationException error) {\n                throw new IllegalStateException(error);\n            }\n        }');
  source = replaceOnce(source, '                case BlendDensity -> WorldgenIr.OP_BLEND_DENSITY;\n', '');
  source = replaceOnce(source, '        if (f instanceof DensityFunctions.Marker marker) {', '        if (type.getSimpleName().equals("BlendDensity")) {\n            int input = node(children(f).get(0));\n            int id = begin(f, WorldgenIr.OP_BLEND_DENSITY, 0);\n            p.u32(input);\n            return id;\n        }\n        if (f instanceof DensityFunctions.Marker marker) {');
  source = replaceOnce(source, '        if (f instanceof DensityFunctions.Marker marker) {', '        if (type.getSimpleName().equals("WeirdScaledSampler")) {\n            int input = node(children(f).get(0));\n            String mapper = json(f).get("rarity_value_mapper").getAsString();\n            double[] thresholds;\n            double[] scales;\n            if (mapper.equals("type_1")) {\n                thresholds = new double[] {-0.5, 0.0, 0.5};\n                scales = new double[] {0.75, 1.0, 1.5, 2.0};\n            } else if (mapper.equals("type_2")) {\n                thresholds = new double[] {-0.75, -0.5, 0.5, 0.75};\n                scales = new double[] {0.5, 0.75, 1.0, 2.0, 3.0};\n            } else throw new UnsupportedOperationException("rarity mapper " + mapper);\n            int[] constants = new int[scales.length];\n            for (int index = 0; index < scales.length; index++) {\n                constants[index] = nodes.begin(WorldgenIr.OP_CONST, 0, 0, scales[index], scales[index]);\n                p.f64(scales[index]);\n            }\n            int scale = nodes.begin(WorldgenIr.OP_INTERVAL_SELECT, 0, 7, scales[0], scales[scales.length - 1]);\n            p.u32(input).u32(thresholds.length);\n            for (double threshold : thresholds) p.f64(threshold);\n            for (int constant : constants) p.u32(constant);\n            try {\n                java.lang.reflect.Method accessor = type.getMethod("noise");\n                if (!accessor.trySetAccessible()) throw new IllegalStateException("noise accessor");\n                DensityFunction.NoiseHolder holder = (DensityFunction.NoiseHolder) accessor.invoke(f);\n                int noise = noise(holder.noiseData());\n                int id = begin(f, WorldgenIr.OP_SCALED_NOISE, 0);\n                p.u32(noise).u32(scale);\n                return id;\n            } catch (ReflectiveOperationException error) {\n                throw new IllegalStateException(error);\n            }\n        }\n        if (f instanceof DensityFunctions.Marker marker) {');
  return source;
}

function legacyReference(source) {
  source = source.replaceAll('ChunkPos.pack(', 'ChunkPos.asLong(');
  source = replaceOnce(source, '                biomeManager, Blender.empty(), possible);', '                biomeManager, biomeRegistry(), Blender.empty());');
  return replaceOnce(source, '    private ProtoChunk newChunk(int cx, int cz) {', '    private net.minecraft.core.Registry<Biome> biomeRegistry() {\n        net.minecraft.core.MappedRegistry<Biome> registry = new net.minecraft.core.MappedRegistry<>(\n                Registries.BIOME, com.mojang.serialization.Lifecycle.stable());\n        lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(holder -> registry.register(\n                holder.unwrapKey().orElseThrow(), holder.value(), net.minecraft.core.RegistrationInfo.BUILT_IN));\n        return registry.freeze();\n    }\n\n    private ProtoChunk newChunk(int cx, int cz) {');
}

async function run(command, args, log) {
  if (process.platform === 'win32') await waitJvmHeadroom(log + '.admission.jsonl');
  const executable = javaHome ? resolve(javaHome, 'bin', command + (process.platform === 'win32' ? '.exe' : '')) : command;
  const child = spawn(executable, args, { windowsHide: true, cwd: port });
  let output = '';
  child.stdout.on('data', data => { output += data; });
  child.stderr.on('data', data => { output += data; });
  const code = await new Promise((resolveCode, reject) => {
    child.on('error', reject);
    child.on('close', resolveCode);
  });
  await writeFile(log, output);
  if (code !== 0) throw new Error(command + ' exited ' + code + '; inspect ' + log);
}

let matched = false;
for (const filename of (await readdir(resolve(port, 'versions'))).filter(name => name.endsWith('.json')).sort()) {
  const profile = JSON.parse(await readFile(resolve(port, 'versions', filename), 'utf8'));
  if (selectedProfile && profile.id !== selectedProfile) continue;
  matched = true;
  if (!profile.worldgenReference) throw new Error('Missing worldgenReference for ' + profile.id);
  const config = profile.worldgenReference;
  const output = resolve(destination, profile.id);
  const classes = resolve(output, 'classes');
  await mkdir(classes, { recursive: true });
  const work = resolve(port, 'work', profile.id);
  const jar = resolve(work, 'client-named.jar');
  let classpath = (await readFile(resolve(work, 'classpath.txt'), 'utf8')).trim();
  if (process.platform === 'win32') classpath = classpath.replace(/\/([a-zA-Z])\//g, '$1:/').replace(/:(?=[a-zA-Z]:\/)/g, ';');
  classpath = jar + delimiter + classpath;
  const sources = [resolve(port, 'src/main/java/dev/gaius/browser/kernel/worldgen/WorldgenIr.java'), resolve(port, 'native/golden/worldgen/StageTiming.java')];
  const hashes = {};
  for (const [key, transform] of [['exporter', legacyExporter], ['source', legacyReference]]) {
    let source = (await readFile(resolve(port, config[key]), 'utf8')).replaceAll('\r\n', '\n');
    if (config.compatibility === 'legacy-router') source = transform(source);
    else if (config.compatibility !== 'direct') throw new Error('Unknown compatibility adapter');
    const path = resolve(output, 'sources', basename(config[key]));
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, source);
    hashes[key] = createHash('sha256').update(source).digest('hex');
    sources.push(path);
  }
  await run('javac', ['-J-Xmx3g', '-nowarn', '-proc:none', '-encoding', 'UTF-8', '-d', classes, '-cp', classpath, ...sources], resolve(output, 'javac.log'));
  await run('java', ['-Xmx3g', '-XX:ActiveProcessorCount=1', '-XX:CICompilerCount=2', '-XX:+UseSerialGC',
    '-Dgaius.worldgen.rounds=' + rounds, '-Dgaius.worldgen.semantics=' + profile.kernelSemantics.code,
    '-cp', classes + delimiter + classpath, config.mainClass, seed, output, ...chunks], resolve(output, 'java.log'));
  await writeFile(resolve(output, 'provenance.json'), JSON.stringify({ profile: profile.id, semantics: profile.kernelSemantics,
    compatibility: config.compatibility, seed, rounds, chunks,
    jarSha256: createHash('sha256').update(await readFile(jar)).digest('hex'), sources: hashes }, null, 2) + '\n');
  console.log(profile.id + ': JVM reference ready');
}
if (!matched) throw new Error('Unknown profile: ' + selectedProfile);
