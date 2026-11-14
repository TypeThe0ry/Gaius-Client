import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
import {join,delimiter,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {bufferBridgeSource} from './buffer-bridge.mjs';
const root=fileURLToPath(new URL('../../..',import.meta.url));
const [source,output,profileFile]=process.argv.slice(2);
if(!source||!output||!profileFile||!process.env.TMPDIR)throw Error('SOURCE OUT PROFILE_JSON and private TMPDIR required');
const out=resolve(output),here=join(root,'port/mc/tests/gl-frame-parity');
mkdirSync(join(out,'classes'),{recursive:true});
const profile=JSON.parse(readFileSync(profileFile,'utf8')).id;
const cp=readFileSync(join(root,'port/work',profile,'classpath.txt'),'utf8').trim().split(delimiter);
const gson=cp.find(p=>p.includes('/gson/') && p.endsWith('.jar'));
writeFileSync(join(out,'BrowserBufferBridge.java'),bufferBridgeSource(readFileSync(source,'utf8')));
function run(tool,args){const r=spawnSync(tool,args,{encoding:'utf8',maxBuffer:4*1024*1024});process.stdout.write(r.stdout||'');process.stderr.write(r.stderr||'');return r.status;}
if(run('javac',['-J-Xmx1g','-cp',gson,'-d',join(out,'classes'),join(out,'BrowserBufferBridge.java'),join(here,'BufferUploadWindowTest.java'),join(here,'GlBufferWindowDriverTest.java')]))process.exit(1);
const test=run('java',['-Xmx1g','-Xverify:all','-cp',[join(out,'classes'),gson].join(delimiter),'dev.gaius.mc.tests.gl.BufferUploadWindowTest',join(out,'windows.json')]);
const driver=run('java',['-Xmx1g','--enable-native-access=ALL-UNNAMED','-cp',[join(out,'classes'),gson].join(delimiter),'dev.gaius.mc.tests.gl.GlBufferWindowDriverTest',join(out,'windows.json'),join(out,'driver.json')]);
process.exitCode=driver||test;
