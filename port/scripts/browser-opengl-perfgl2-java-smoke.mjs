import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdir,writeFile} from 'node:fs/promises';
import {homedir} from 'node:os';
import {delimiter,join,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

assert.ok(process.argv[2], 'Pass an output directory for the JVM harness');
const output=resolve(process.argv[2]);
const repository=fileURLToPath(new URL('../..',import.meta.url));
const classes=join(output,'java-perfgl2','classes'),sources=join(output,'java-perfgl2','src');
const dependencyRoot=join(homedir(),'.m2/repository/org/teavm');
const classpath=['teavm-jso','teavm-jso-apis'].map(name=>join(dependencyRoot,name,'0.15.0',name+'-0.15.0.jar')).join(delimiter);
const files=new Map([
  ['org/lwjgl/opengl/PerfRegression.java', `package org.lwjgl.opengl;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
public final class PerfRegression {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Method invalidate=BrowserOpenGLPerf.class.getDeclaredMethod("invalidate",int.class,boolean.class);
        invalidate.setAccessible(true);
        invalidate.invoke(null,0,true);
        check(BrowserOpenGLPerf.uniformLocation(7,"absent")==null,"Unexpected cached location");
        BrowserOpenGLPerf.rememberUniform(7,"absent",-1);
        check(BrowserOpenGLPerf.uniformLocation(7,"absent")==-1,"Negative location was not cached");
        BrowserOpenGLPerf.rememberUniform(8,"absent",12);
        BrowserOpenGLPerf.forgetProgram(7);
        check(BrowserOpenGLPerf.uniformLocation(7,"absent")==null,"Deleted pipeline survived");
        check(BrowserOpenGLPerf.uniformLocation(8,"absent")==12,"Other pipeline was invalidated");
        BrowserOpenGLPerf.rememberProgram(3);
        check(BrowserOpenGLPerf.skipProgram(3),"Program bind was not filtered");
        invalidate.invoke(null,1,true);
        check(!BrowserOpenGLPerf.skipProgram(3),"External program bind was ignored");
        BrowserOpenGLPerf.rememberVao(9);
        check(BrowserOpenGLPerf.skipVao(9),"VAO bind was not filtered");
        invalidate.invoke(null,2,true);
        check(!BrowserOpenGLPerf.skipVao(9),"External VAO bind was ignored");
        BrowserOpenGLPerf.rememberTexture(0x84C1,0x0DE1,11);
        check(BrowserOpenGLPerf.skipTexture(0x0DE1,11),"Texture bind was not filtered");
        check(!BrowserOpenGLPerf.skipTexture(0x806F,11),"Texture targets collided");
        invalidate.invoke(null,3,true);
        check(!BrowserOpenGLPerf.skipTexture(0x0DE1,11),"External texture bind was ignored");
        invalidate.invoke(null,4,true);
        check(BrowserOpenGLPerf.uniformLocation(8,"absent")==null,"Context loss retained a location");
        BrowserOpenGLPerf.noteUpload(32);
        Field bytes=BrowserOpenGLPerf.class.getDeclaredField("uploadBytes");bytes.setAccessible(true);
        check(bytes.getLong(null)==32,"Wasm upload accounting failed");
        invalidate.invoke(null,0,false);
        BrowserOpenGLPerf.rememberProgram(3);
        check(!BrowserOpenGLPerf.skipProgram(3),"Disabled cache filtered a bind");
        System.out.println("perfgl2 JVM caches, locations, invalidation, accounting PASS");
    }
}`],
  ['dev/gaius/browser/JsArrays.java', `package dev.gaius.browser;
import org.teavm.jso.typedarrays.Float64Array;
public final class JsArrays {
    public static final boolean LINEAR_MEMORY=false;
    public static Float64Array float64(double[] values) {return null;}
}`],
  ['org/teavm/classlib/java/lang/TModernRuntimeSupport.java', `package org.teavm.classlib.java.lang;
public final class TModernRuntimeSupport {public static void yieldToEventLoop(int millis) {}}
`],
  ['dev/gaius/browser/HostLoop.java', `package dev.gaius.browser;
public final class HostLoop {
    public static void startThread(Runnable task) {}
    public static void schedule(Runnable task,int millis) {}
}`],
  ['dev/gaius/browser/render/BrowserMeshInstallQueue.java', `package dev.gaius.browser.render;
public final class BrowserMeshInstallQueue {
    public static int inFlight() {return 0;}
    public static int readyCount() {return 0;}
    public static long drain(long bytes,long deadline) {return 0;}
}`],
  ['dev/gaius/browser/FrameTailRegression.java', `package dev.gaius.browser;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
public final class FrameTailRegression {
    private static void set(String name,boolean value) throws Exception {
        Field field=BrowserRenderScheduler.class.getDeclaredField(name);field.setAccessible(true);field.setBoolean(null,value);
    }
    private static void check(String name,long expected) throws Exception {
        Method method=BrowserRenderScheduler.class.getDeclaredMethod(name);method.setAccessible(true);
        if (((Number)method.invoke(null)).longValue()!=expected) throw new AssertionError(name);
    }
    public static void main(String[] args) throws Exception {
        set("fastProfileInitialized",true);set("frameTailProfileInitialized",true);
        check("effectiveMaxTasksPerFrame",8);
        set("frameTailProfile",true);
        check("effectiveMaxTasksPerFrame",4);check("effectiveQueueHighWater",64);
        check("effectiveFrameWorkBudgetNanos",1000000);check("effectiveMaxUploadAllocationsPerFrame",4);
        check("effectiveUploadWorkBudgetNanos",1000000);check("effectiveCompileRunsDuringUploadPerFrame",1);
        set("frameTailProfile",false);check("effectiveMaxTasksPerFrame",8);
        System.out.println("perfgl2 JVM opt-in pump/upload budget and fallback PASS");
    }
}`]
]);
await mkdir(classes,{recursive:true});
const sourcePaths=[];
for (const [relative,content] of files) {
  const path=join(sources,relative);await mkdir(resolve(path,'..'),{recursive:true});
  await writeFile(path,content);sourcePaths.push(path);
}
sourcePaths.push(join(repository,'port/overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGLPerf.java'));
sourcePaths.push(join(repository,'port/src/main/java/dev/gaius/browser/BrowserRenderScheduler.java'));
execFileSync('javac',['-J-Xmx3g','--release','21','-cp',classpath,'-d',classes,...sourcePaths],{stdio:'inherit'});
for (const main of ['org.lwjgl.opengl.PerfRegression','dev.gaius.browser.FrameTailRegression']) {
  execFileSync('java',['-Xmx3g','-cp',[classes,classpath].join(delimiter),main],{stdio:'inherit'});
}
