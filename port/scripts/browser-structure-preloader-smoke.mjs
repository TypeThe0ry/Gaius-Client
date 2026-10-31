import {CANONICAL_PROFILE, RENDERPEARL_PROFILE} from "./verification-profiles.mjs";
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {existsSync} from 'node:fs';
import {mkdir, mkdtemp, readdir, readFile, writeFile} from 'node:fs/promises';
import {homedir, tmpdir} from 'node:os';
import {delimiter, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {sourceFile} from '../tools/sourcesets/sourcesets.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
// --profile 26.3 compiles the 26.3 profile's sources; the default profile 26.2 keeps its own.
// BrowserStructurePreloader is one source for every profile: it reaches the manager through
// VersionOps.structureTemplateManager, whose generation implementation calls the accessor
// getStructureManager() (26.2) or getStructureTemplateManager() (26.3). The stub MinecraftServer
// has only its profile's accessor and the stub VersionOps binds exactly that accessor, so the
// preloader logic runs against the profile's server shape; the real VersionOps implementation
// is asserted to call the same accessor (and exercised by sourcesets/test-version-ops.mjs).
// When port/work/<profile>/client-named.jar exists, the accessor is also checked against the
// real MinecraftServer.
const profileIndex = process.argv.indexOf('--profile');
const profile = profileIndex >= 0 ? process.argv[profileIndex + 1] : CANONICAL_PROFILE.id;
const accessors = {[CANONICAL_PROFILE.id]: 'getStructureManager', [RENDERPEARL_PROFILE.id]: 'getStructureTemplateManager'};
const accessor = accessors[profile];
assert.ok(accessor, `unsupported --profile ${profile}`);
// The profile's copy: its port/mc/shared source sets over port/src/main (sourcesets.mjs).
const preloader = sourceFile(profile, 'src', 'dev/gaius/browser/BrowserStructurePreloader.java', {root});
assert.ok(preloader, `profile ${profile} compiles no BrowserStructurePreloader`);
const generationOps = sourceFile(profile, 'src', 'dev/gaius/browser/ops/RenderpearlVersionOps.java', {root})
  ?? sourceFile(profile, 'src', 'dev/gaius/browser/ops/Blaze3dVersionOps.java', {root});
assert.ok(generationOps, `profile ${profile} compiles no VersionOps implementation`);
assert.ok(generationOps.text.includes(`server.${accessor}()`),
  `${generationOps.path} does not read the structure manager through ${accessor}()`);
assert.ok(preloader.text.includes('VersionOps.INSTANCE.structureTemplateManager(server)'),
  'BrowserStructurePreloader no longer reaches the structure manager through VersionOps');
const repository = process.env.M2_REPO || join(homedir(), '.m2', 'repository');
const jars=[];
for (const artifact of await readdir(join(repository,'org','teavm'))) {
  const dir=join(repository,'org','teavm',artifact,'0.15.0');
  try { for (const n of await readdir(dir)) if(n.endsWith('.jar')&&!n.includes('sources')&&!n.includes('javadoc')) jars.push(join(dir,n)); } catch {}
}
assert.ok(jars.some(x=>x.includes('teavm-tooling')), 'TeaVM 0.15 tooling missing');
const executableSuffix=process.platform==='win32'?'.exe':'';
const java=process.env.JAVA_HOME?join(process.env.JAVA_HOME,'bin','java'+executableSuffix):'java';
const javac=process.env.JAVA_HOME?join(process.env.JAVA_HOME,'bin','javac'+executableSuffix):'javac';
const temp=await mkdtemp(join(tmpdir(),'gaius-structure-preloader-'));
const files={
 'dev/gaius/browser/BrowserStructurePreloader.java': preloader.text,
 'org/teavm/classlib/java/lang/TModernRuntimeSupport.java': `package org.teavm.classlib.java.lang; import org.teavm.interop.*; import org.teavm.platform.Platform; public final class TModernRuntimeSupport { public static int yields; @Async public static native void yieldToEventLoop(int delay); private static void yieldToEventLoop(int delay, AsyncCallback<Void> callback){ yields++; Platform.schedule(()->callback.complete(null),delay); } }`,
 'net/minecraft/resources/Identifier.java': `package net.minecraft.resources; public final class Identifier { private final String value; private Identifier(String v){value=v;} public static Identifier tryParse(String v){ if(v==null||!v.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) return null; return new Identifier(v);} public String toString(){return value;} }`,
 'net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager.java': `package net.minecraft.world.level.levelgen.structure.templatesystem; import java.util.*; import net.minecraft.resources.Identifier; public class StructureTemplateManager { public int gets; public static String mode="ok"; private final Set<String> cache=new HashSet<>(); public Optional<Object> get(Identifier id){ if("runtime".equals(mode)) throw new RuntimeException("fixture-failure"); if("error".equals(mode)) throw new AssertionError("fixture-error"); if("missing".equals(mode)) return Optional.empty(); if(cache.add(id.toString())) gets++; return Optional.of(new Object()); } }`,
 'dev/gaius/browser/ops/VersionOps.java': `package dev.gaius.browser.ops; import net.minecraft.server.MinecraftServer; import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager; public interface VersionOps { VersionOps INSTANCE = server -> server.${accessor}(); StructureTemplateManager structureTemplateManager(MinecraftServer server); }`,
 'net/minecraft/server/MinecraftServer.java': `package net.minecraft.server; import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager; public class MinecraftServer { private final StructureTemplateManager m; public MinecraftServer(StructureTemplateManager x){m=x;} public StructureTemplateManager ${accessor}(){return m;} }`,
 'PreloaderFixture.java': `import dev.gaius.browser.BrowserStructurePreloader; import net.minecraft.server.MinecraftServer; import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager; import org.teavm.interop.Async; import org.teavm.interop.AsyncCallback; import org.teavm.jso.*; import org.teavm.platform.Platform;
public class PreloaderFixture {
 @JSBody(script="globalThis.__gaiusStructurePreloadIds=undefined; globalThis.__gaiusStructurePreloadBudgetMillis=undefined;") static native void clear();
 @JSBody(script="globalThis.__gaiusStructurePreloadIds=['minecraft:a','minecraft:a','bad']; globalThis.__gaiusStructurePreloadBudgetMillis=30000;") static native void configure();
 @JSBody(script="globalThis.__gaiusStructurePreloadIds=['minecraft:missing']; globalThis.__gaiusStructurePreloadBudgetMillis=30000;") static native void configureMissing();
 @JSBody(script="globalThis.__gaiusStructurePreloadIds=['minecraft:runtime']; globalThis.__gaiusStructurePreloadBudgetMillis=30000;") static native void configureRuntime();
 @JSBody(script="globalThis.__gaiusStructurePreloadIds=['minecraft:error']; globalThis.__gaiusStructurePreloadBudgetMillis=30000;") static native void configureError();
 @JSBody(script="globalThis.__gaiusStructurePreloadIds=Array.from({length:70},(_,i)=>'minecraft:x'+i); globalThis.__gaiusStructurePreloadBudgetMillis=30000;") static native void configureMany();
 @JSBody(script="globalThis.__gaiusStructurePreloadIds=['minecraft:budget']; globalThis.__gaiusStructurePreloadBudgetMillis=0.1; let n=0; globalThis.performance={now:()=>++n};") static native void configureBudget();
 @JSBody(script="globalThis.__gaiusStructurePreloadIds=['minecraft:first','minecraft:second']; globalThis.__gaiusStructurePreloadBudgetMillis=4; let n=0; globalThis.performance={now:()=>++n};") static native void configureBetweenBudget();
 @JSBody(script="return globalThis.__gaiusWorldgenStats && globalThis.__gaiusWorldgenStats.structurePreload ? JSON.stringify(globalThis.__gaiusWorldgenStats.structurePreload) : ''; ") static native String stats();
 @JSBody(params="s",script="globalThis.preloaderResult=s;") static native void publish(String s);
 @JSBody(params="r",script="setTimeout(r,0);") static native void rawTimer(R r);
 @JSFunctor interface R extends JSObject { void run(); }
 @Async static native void checkpoint(); static void checkpoint(AsyncCallback<Void> cb){ Platform.schedule(()->cb.complete(null),0); }
 static void expect(boolean ok,String m){if(!ok)throw new AssertionError(m);}
 static void checkStats(String wanted){String x=stats(); expect(x.contains(wanted),"stats missing "+wanted+" in "+x);}
 public static void main(String[] a){
  StructureTemplateManager raw=new StructureTemplateManager(); MinecraftServer rs=new MinecraftServer(raw); clear(); BrowserStructurePreloader.preload(rs); expect(raw.gets==0,"default-off");
  rawTimer(()->{ StructureTemplateManager rm=new StructureTemplateManager(); configure(); BrowserStructurePreloader.preload(new MinecraftServer(rm)); expect(rm.gets==0,"raw timer must not preload"); expect(org.teavm.classlib.java.lang.TModernRuntimeSupport.yields==0,"raw timer must not yield"); expect(stats().contains("skipped-no-native-continuation"),"raw guard stats");
  Platform.startThread(()->{ try { checkpoint(); StructureTemplateManager m=new StructureTemplateManager(); MinecraftServer s=new MinecraftServer(m); configure(); BrowserStructurePreloader.preload(s); expect(m.gets==1,"duplicate cache"); checkStats("failed\\\":1");
    configureMissing(); StructureTemplateManager.mode="missing"; StructureTemplateManager mm=new StructureTemplateManager(); BrowserStructurePreloader.preload(new MinecraftServer(mm)); checkStats("missing\\\":1"); StructureTemplateManager.mode="ok";
    configureRuntime(); StructureTemplateManager mr=new StructureTemplateManager(); StructureTemplateManager.mode="runtime"; BrowserStructurePreloader.preload(new MinecraftServer(mr)); checkStats("failed\\\":1"); StructureTemplateManager.mode="ok";
    configureError(); boolean threw=false; try { StructureTemplateManager.mode="error"; BrowserStructurePreloader.preload(new MinecraftServer(new StructureTemplateManager())); } catch(AssertionError e){ threw="fixture-error".equals(e.getMessage()); } expect(threw,"Error identity"); StructureTemplateManager.mode="ok";
    configureMany(); StructureTemplateManager ma=new StructureTemplateManager(); BrowserStructurePreloader.preload(new MinecraftServer(ma)); expect(ma.gets==64,"64-item cap load count"); expect(org.teavm.classlib.java.lang.TModernRuntimeSupport.yields>=64,"real yielding through cap"); checkStats("limit\\\":64"); checkStats("remaining\\\":6");
    configureMany(); StructureTemplateManager.mode="missing"; BrowserStructurePreloader.preload(new MinecraftServer(new StructureTemplateManager())); checkStats("max-ids"); StructureTemplateManager.mode="ok";
    configureBudget(); StructureTemplateManager mb=new StructureTemplateManager(); BrowserStructurePreloader.preload(new MinecraftServer(mb)); checkStats("budget"); expect(mb.gets==0,"initial budget must not load");
    configureBetweenBudget(); StructureTemplateManager mi=new StructureTemplateManager(); BrowserStructurePreloader.preload(new MinecraftServer(mi)); checkStats("budget"); expect(mi.gets==1,"between-item budget must stop next load");
    StructureTemplateManager a1=new StructureTemplateManager(); StructureTemplateManager a2=new StructureTemplateManager(); configure(); BrowserStructurePreloader.preload(new MinecraftServer(a1)); BrowserStructurePreloader.preload(new MinecraftServer(a2)); expect(a1.gets==1&&a2.gets==1,"reload isolation"); publish("PRELOADER_OK"); } catch(Throwable e){ publish("PRELOADER_FAIL:"+e.toString()); throw e; }});
 }); }
}`,
 'CompileFixture.java': `import java.io.File; import org.teavm.tooling.*; import org.teavm.backend.javascript.JSModuleType; public class CompileFixture { public static void main(String[] a)throws Exception{TeaVMTool t=new TeaVMTool();t.setMainClass("PreloaderFixture");t.setTargetDirectory(new File(a[0]));t.setTargetFileName("fixture.cjs");t.setJsModuleType(JSModuleType.COMMON_JS);t.setObfuscated(false);t.setClassLoader(ClassLoader.getSystemClassLoader());t.generate();for(var p:t.getProblemProvider().getSevereProblems())System.err.println(p.getText()+" "+java.util.Arrays.toString(p.getParams()));if(!t.getProblemProvider().getSevereProblems().isEmpty())throw new AssertionError("TeaVM failed");}}`
};
const namedJar=join(root,'port/work',profile,'client-named.jar');
if (existsSync(namedJar)) {
  const javap=process.env.JAVA_HOME?join(process.env.JAVA_HOME,'bin','javap'+executableSuffix):'javap';
  const server=execFileSync(javap,['-cp',namedJar,'net.minecraft.server.MinecraftServer'],{encoding:'utf8',maxBuffer:20*1024*1024});
  assert.ok(server.includes(`public net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager ${accessor}()`),
    `${profile} MinecraftServer has no ${accessor}()`);
  console.log(`PRELOADER_ACCESSOR ${profile} MinecraftServer.${accessor}() present in ${namedJar}`);
}
const paths=[]; for(const [name,data] of Object.entries(files)){const f=join(temp,name); await mkdir(resolve(f,'..'),{recursive:true}); await writeFile(f,data); paths.push(f);}
const cp=[temp,...jars].join(delimiter); execFileSync(javac,['--release','21','-cp',cp,'-d',temp,...paths],{stdio:'pipe',maxBuffer:20*1024*1024}); execFileSync(java,['-Xmx1g','-cp',cp,'CompileFixture',temp],{stdio:'pipe',timeout:120000});
const out=execFileSync(process.execPath,['-e',`const m=require(${JSON.stringify(join(temp,'fixture.cjs'))});
const deadline=setTimeout(()=>{throw Error('preloader fixture completion timed out');},10000);
const poll=setInterval(()=>{if(globalThis.preloaderResult){clearInterval(poll);clearTimeout(deadline);console.log(globalThis.preloaderResult);}},20);
m.main([],e=>{if(e)throw e;});`],{encoding:'utf8',timeout:15000}); assert.equal(out.trim(),'PRELOADER_OK'); console.log(`${out.trim()} profile=${profile} source=${preloader.path}`);
