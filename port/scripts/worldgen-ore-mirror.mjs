import {execFileSync} from 'node:child_process';
import {mkdirSync, writeFileSync} from 'node:fs';
import {join, resolve} from 'node:path';
import {loadProfiles} from '../tools/kernelmirror/kernelmirror.mjs';

const root = resolve(import.meta.dirname, '../..');
if (!process.argv[2]) throw new Error('Usage: node worldgen-ore-mirror.mjs <output>');
const output = resolve(process.argv[2]);
if (output.toLowerCase() === root.toLowerCase()
    || output.toLowerCase().startsWith(root.toLowerCase() + '/')
    || output.toLowerCase().startsWith(root.toLowerCase() + '\\')) {
  throw new Error('Use a separate output directory');
}
mkdirSync(output, {recursive: true});
const javap = process.env.GAIUS_JAVA_HOME ? join(process.env.GAIUS_JAVA_HOME, 'bin/javap') : 'javap';
const records = new Map();
for (const profile of loadProfiles(root).filter(profile => profile.worldgenReference)) {
  const jar = join(root, 'port/work', profile.id, 'client-named.jar');
  const inspect = owner => execFileSync(javap, ['-J-Xmx3g', '-p', '-s', '-classpath', jar, owner], {encoding: 'utf8'});
  const base = 'net.minecraft.world.level.levelgen.';
  const feature = base + 'feature.OreFeature';
  const text = inspect(feature);
  const owners = [feature, 'net.minecraft.util.Mth', base + 'structure.templatesystem.RuleTest',
    ...[...profile.worldgenReference.oreRules, profile.worldgenReference.oreUnionRule].filter(Boolean)
      .map(name => base + 'structure.templatesystem.' + name)];
  if (text.includes('extends ' + base + 'feature.AbstractOreFeature')) {
    owners.push(base + 'feature.AbstractOreFeature', base + 'feature.BlockReplacement');
  } else {
    owners.push(base + 'feature.configurations.OreConfiguration',
      base + 'feature.configurations.OreConfiguration$TargetBlockState');
  }
  for (const owner of owners) {
    const lines = inspect(owner).split(/\r?\n/);
    for (let index = 1; index < lines.length; index++) {
      const match = lines[index].match(/^\s+descriptor: (\(.*)$/);
      if (!match) continue;
      const previous = lines[index - 1].trim();
      let name = previous === 'static {};' ? '<clinit>' : previous.slice(0, previous.indexOf('(')).split(' ').at(-1);
      if (name === owner) name = '<init>';
      const identity = owner + '|' + name + '|' + match[1];
      if (!records.has(identity)) records.set(identity, {owner, name, descriptor: match[1], profiles: []});
      records.get(identity).profiles.push(profile.id);
    }
  }
}
const methods = [...records.values()].sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right)));
const toml = '\n[[kernels]]\nname = "ore"\ndependencies = ["worldgen"]\nmirrorsVanilla = true\n'
  + 'rationale = "Experimental ordered ore geometry and deterministic snapshot rules; complete feature and rule watch set."\n'
  + methods.map(method => '\n[[methods]]\nkernel = "ore"\n' + Object.entries(method)
    .map(([key, value]) => key + ' = ' + JSON.stringify(value)).join('\n') + '\n').join('');
const patch = '*** Begin Patch\n*** Update File: port/native/kernel-mirror.toml\n@@\n schemaVersion = 1\n'
  + toml.trimEnd().split('\n').map(line => '+' + line).join('\n') + '\n*** End Patch\n';
writeFileSync(join(output, 'ore-mirror.patch'), patch);
console.log('ore mirror: ' + methods.length + ' exact method identities; patch saved');
