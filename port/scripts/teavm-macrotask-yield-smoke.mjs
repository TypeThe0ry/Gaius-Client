import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdir, mkdtemp, readFile, readdir, rm, writeFile} from 'node:fs/promises';
import {homedir, tmpdir} from 'node:os';
import {delimiter, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

// Compile and run the real TeaVM continuation implementation.  The yield methods are
// extracted verbatim from TModernRuntimeSupport (the fixture removes unrelated ICU/time
// methods so this smoke stays small); they resume through the JavaScript HostLoop, whose
// MessageChannel helper compiles unchanged.
//   node port/scripts/teavm-macrotask-yield-smoke.mjs [TModernRuntimeSupport.java [HostLoop.java]]
const root = fileURLToPath(new URL('../../', import.meta.url));
const input = resolve(process.argv[2] || join(root,
  'port/overrides/classlib/src/main/java/org/teavm/classlib/java/lang/TModernRuntimeSupport.java'));
const hostLoopInput = resolve(process.argv[3] || join(root,
  'port/overrides/classlib/src/main/java/dev/gaius/browser/HostLoop.java'));
const repository = process.env.M2_REPO || join(homedir(), '.m2', 'repository');
const jars = [];
for (const artifact of await readdir(join(repository, 'org', 'teavm'))) {
  const directory = join(repository, 'org', 'teavm', artifact, '0.15.0');
  try {
    for (const name of await readdir(directory)) {
      if (name.endsWith('.jar') && !name.includes('-sources') && !name.includes('-javadoc')) {
        jars.push(join(directory, name));
      }
    }
  } catch { /* only installed TeaVM 0.15 artifacts */ }
}
assert.ok(jars.some(name => name.includes('teavm-tooling')), 'TeaVM 0.15 tooling must be installed');
const suffix = process.platform === 'win32' ? '.exe' : '';
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', `java${suffix}`) : 'java';
const javac = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', `javac${suffix}`) : 'javac';
const temp = await mkdtemp(join(tmpdir(), 'gaius-teavm-yield-'));

function balancedMethod(source, start) {
  const open = source.indexOf('{', start);
  assert.ok(open >= 0, `method body not found at ${start}`);
  let depth = 0;
  for (let index = open; index < source.length; index++) {
    if (source[index] === '{') depth++;
    else if (source[index] === '}' && --depth === 0) return source.slice(start, index + 1);
  }
  throw new Error('unterminated method body');
}

const production = await readFile(input, 'utf8');
const asyncStart = production.indexOf('    /**\n     * Suspends only the current TeaVM continuation');
assert.ok(asyncStart >= 0, 'real yieldToEventLoop Javadoc not found');
// yieldToEventLoop, inClassInitializer and the @Async suspendToEventLoop declaration, then the
// callback overload that TeaVM runs for it.
const callbackAnchor = '\n\n    private static void suspendToEventLoop(int delayMillis, AsyncCallback<Void> callback)';
const callbackAt = production.indexOf(callbackAnchor, asyncStart);
assert.ok(callbackAt >= 0, 'real suspendToEventLoop callback overload not found');
const asyncMethod = production.slice(asyncStart, callbackAt);
const callbackStart = callbackAt + 2;
const callbackMethod = balancedMethod(production, callbackStart);
// The MessageChannel helper and the host timer live in dev.gaius.browser.HostLoop.
const hostLoop = await readFile(hostLoopInput, 'utf8');
const helperStart = hostLoop.indexOf('    @JSBody(params = "callback"');
const helperEnd = hostLoop.indexOf('private static native void postMacrotask(MacrotaskCallback callback);', helperStart);
assert.ok(helperStart >= 0 && helperEnd >= 0
    && hostLoop.slice(helperStart, helperEnd).includes('new MessageChannel()'),
  'real postMacrotask helper not found');

try {
  const sources = {
    'org/teavm/classlib/java/lang/TModernRuntimeSupport.java': `package org.teavm.classlib.java.lang;
import dev.gaius.browser.HostLoop;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
public final class TModernRuntimeSupport {
    private TModernRuntimeSupport() {}
${asyncMethod}
${callbackMethod}
}`,
    'dev/gaius/browser/HostLoop.java': hostLoop,
    'YieldFixture.java': `
import org.teavm.classlib.java.lang.TModernRuntimeSupport;
import org.teavm.jso.JSBody;
public class YieldFixture {
    @JSBody(params = "value", script = "globalThis.__yieldPhase=value; globalThis.__yieldPhases.push(value);")
    static native void phase(int value);
    public static void main(String[] args) {
        phase(1);
        TModernRuntimeSupport.yieldToEventLoop(0);
        phase(2);
        TModernRuntimeSupport.yieldToEventLoop(0);
        phase(3);
        TModernRuntimeSupport.yieldToEventLoop(15);
        phase(4);
        TModernRuntimeSupport.yieldToEventLoop(0);
        phase(5);
    }
}`,
    'CompileYieldFixture.java': `
import java.io.File;
import org.teavm.backend.javascript.JSModuleType;
import org.teavm.tooling.TeaVMTool;
public class CompileYieldFixture {
    public static void main(String[] args) throws Exception {
        TeaVMTool tool = new TeaVMTool();
        tool.setMainClass("YieldFixture");
        tool.setTargetDirectory(new File(args[0]));
        tool.setTargetFileName("fixture.cjs");
        tool.setJsModuleType(JSModuleType.COMMON_JS);
        tool.setObfuscated(false);
        tool.setClassLoader(ClassLoader.getSystemClassLoader());
        tool.generate();
        for (var problem : tool.getProblemProvider().getSevereProblems())
            System.err.println(problem.getText() + " " + java.util.Arrays.toString(problem.getParams()));
        if (!tool.getProblemProvider().getSevereProblems().isEmpty())
            throw new AssertionError("TeaVM compilation failed");
    }
}`,
  };
  const files = [];
  for (const [name, content] of Object.entries(sources)) {
    const file = join(temp, name);
    await mkdir(resolve(file, '..'), {recursive: true});
    await writeFile(file, content);
    files.push(file);
  }
  const cp = [temp, ...jars].join(delimiter);
  execFileSync(javac, ['--release', '21', '-cp', cp, '-d', temp, ...files], {stdio: 'pipe'});
  execFileSync(java, ['-Xmx1g', '-cp', cp, 'CompileYieldFixture', temp],
    {encoding: 'utf8', timeout: 120000});

  const runner = join(temp, 'run.cjs');
  await writeFile(runner, `
const {MessageChannel: NativeMessageChannel} = require('node:worker_threads');
const mode = process.argv[2];
const timerDelays = [];
const nativeSetTimeout = globalThis.setTimeout;
globalThis.__yieldPhase = 0;
globalThis.__yieldPhases = [];
globalThis.setTimeout = (callback, delay) => {
  timerDelays.push(Number(delay));
  return nativeSetTimeout(callback, delay);
};
if (mode === 'channel') {
  globalThis.MessageChannel = class extends NativeMessageChannel {
    constructor() {
      super();
      this.port1.unref();
      this.port2.unref();
    }
  };
} else if (mode === 'constructor-throws') {
  globalThis.MessageChannel = class {
    constructor() { throw new Error('constructor failure fixture'); }
  };
} else if (mode === 'post-throws') {
  globalThis.MessageChannel = class extends NativeMessageChannel {
    constructor() {
      super();
      this.port1.unref();
      this.port2.unref();
      this.port2.postMessage = () => { throw new Error('postMessage failure fixture'); };
    }
  };
} else {
  globalThis.MessageChannel = undefined;
}
require('./fixture.cjs').main([], (error) => {
  if (error) { console.error(error); process.exit(1); }
  if (globalThis.__yieldPhase !== 5 || JSON.stringify(globalThis.__yieldPhases) !== '[1,2,3,4,5]') {
    console.error('unexpected final phase', globalThis.__yieldPhase);
    process.exit(1);
  }
  console.log(JSON.stringify({phase: globalThis.__yieldPhase, timerDelays}));
  process.exit(0);
});
const inlinePhase = globalThis.__yieldPhase;
if (inlinePhase !== 1) {
  console.error('yield resumed inline at phase', inlinePhase);
  process.exit(1);
}
`);
  for (const mode of ['channel', 'fallback', 'constructor-throws', 'post-throws']) {
    const output = execFileSync(process.execPath, [runner, mode], {encoding: 'utf8', timeout: 15000});
    const result = JSON.parse(output.trim());
    assert.equal(result.phase, 5, `${mode}: continuation did not finish exactly once`);
    if (mode === 'channel') {
      assert.deepEqual(result.timerDelays, [15],
        'MessageChannel zero-delay path must avoid setTimeout while positive delay keeps its timer');
    } else if (mode === 'fallback') {
      assert.deepEqual(result.timerDelays, [0, 0, 15, 0],
        'MessageChannel fallback must schedule every zero-delay yield and preserve positive delay');
    } else if (mode === 'constructor-throws') {
      assert.deepEqual(result.timerDelays, [0, 0, 15, 0],
        'constructor failure must fallback every zero-delay yield and preserve positive delay');
    } else {
      assert.deepEqual(result.timerDelays, [0, 0, 15, 0],
        'postMessage failure must fallback the queued callback exactly once and preserve later delays');
    }
  }
  console.log('TEAVM_MACROTASK_YIELD_OK channel=async-once fallback=async-once constructor-throws=async-once post-throws=async-once queue=ordered');
} finally {
  await rm(temp, {recursive: true, force: true});
}
