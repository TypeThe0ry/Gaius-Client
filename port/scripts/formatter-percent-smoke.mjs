import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {copyFile, mkdir, mkdtemp, readFile, readdir, rm, writeFile} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import {homedir, tmpdir} from 'node:os';
import {delimiter, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const repositoryRoot = fileURLToPath(new URL('../../', import.meta.url));
const baseline = process.argv.includes('--baseline');
const repository = process.env.M2_REPO || join(homedir(), '.m2', 'repository');
const teaVmJars = [];
for (const artifact of await readdir(join(repository, 'org', 'teavm'))) {
  const directory = join(repository, 'org', 'teavm', artifact, '0.15.0');
  try {
    for (const name of await readdir(directory)) {
      if (name.endsWith('.jar') && !name.includes('-sources') && !name.includes('-javadoc')) {
        teaVmJars.push(join(directory, name));
      }
    }
  } catch {
    // Only installed TeaVM 0.15 artifacts are relevant.
  }
}
assert.ok(teaVmJars.some(name => name.includes('teavm-tooling')),
  'TeaVM 0.15 tooling must be installed');

const suffix = process.platform === 'win32' ? '.exe' : '';
const javaHome = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
const java = javaHome ? join(javaHome, 'bin', `java${suffix}`) : 'java';
const javac = javaHome ? join(javaHome, 'bin', `javac${suffix}`) : 'javac';
// The formatter fixture is profile-independent. Keep it runnable on the
// repository guard's JDK 21 job even when the active profile is 26.2.
const fixtureJavaHome = process.env.GAIUS_FORMATTER_JAVA_HOME || javaHome;
const fixtureJava = fixtureJavaHome ? join(fixtureJavaHome, 'bin', `java${suffix}`) : java;
const fixtureJavac = fixtureJavaHome ? join(fixtureJavaHome, 'bin', `javac${suffix}`) : javac;
const temp = await mkdtemp(join(tmpdir(), 'gaius-formatter-percent-'));
const outputDirectory = join(repositoryRoot, 'work/playability/formatter-percent-regression');
await mkdir(outputDirectory, {recursive: true});

const asmRoot = join(repository, 'org', 'ow2', 'asm');
const asm = join(asmRoot, 'asm/9.8/asm-9.8.jar');
const asmTree = join(asmRoot, 'asm-tree/9.8/asm-tree-9.8.jar');
const asmAnalysis = join(asmRoot, 'asm-analysis/9.8/asm-analysis-9.8.jar');
const suppliedClasslib = process.env.GAIUS_FORMATTER_CLASSLIB_JAR || null;
const rawClasslib = suppliedClasslib || join(repository,
  'org/teavm/teavm-classlib/0.15.0/teavm-classlib-0.15.0.jar');

const sources = {
  'FormatterPercentFixture.java': `
import java.util.Locale;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

public class FormatterPercentFixture {
  @JSFunctor interface ResultCallback extends JSObject { void accept(String value); }
  @JSBody(params = "callback", script = "globalThis.gaiusFormatterPercentCallback = callback;")
  static native void install(ResultCallback callback);
  @JSBody(params = "value", script = "globalThis.gaiusFormatterPercentResult = value;")
  static native void publish(String value);

  static String one(int index) {
    try {
      switch (index) {
        case 0: return String.format(Locale.ROOT, "%d%% %d", 1L, 2L);
        case 1: return String.format(Locale.ROOT, "Mem: %2d%% %03d/%03dMiB", 3L, 123L, 456L);
        case 2: return String.format(Locale.ROOT, "%s%% %<s", "x");
        case 3: return String.format(Locale.ROOT, "%2$d%% %1$d", 11L, 22L);
        case 4: return String.format(Locale.ROOT, "%%");
        case 5: return String.format(Locale.ROOT, "plain text");
        default: return String.format(Locale.ROOT, "a%nb");
      }
    } catch (Throwable error) {
      return "ERROR:" + error.getClass().getName() + ":" + error.getMessage();
    }
  }

  static String[] values() {
    String[] values = new String[7];
    for (int i = 0; i < values.length; i++) values[i] = one(i);
    return values;
  }

  public static void main(String[] args) {
    String[] values = values();
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < values.length; i++) {
      if (i != 0) result.append('\\u001f');
      result.append(values[i]);
    }
    String joined = result.toString();
    install(value -> {
      if (!joined.equals(value)) {
        throw new AssertionError("callback payload changed: " + value);
      }
    });
    publish(joined);
  }
}
`,
  'CompileFormatterPercentFixture.java': `
import java.io.File;
import org.teavm.backend.javascript.JSModuleType;
import org.teavm.tooling.TeaVMTool;

public class CompileFormatterPercentFixture {
  public static void main(String[] args) throws Exception {
    TeaVMTool tool = new TeaVMTool();
    tool.setMainClass("FormatterPercentFixture");
    tool.setTargetDirectory(new File(args[0]));
    tool.setTargetFileName("fixture.cjs");
    tool.setJsModuleType(JSModuleType.COMMON_JS);
    tool.setObfuscated(false);
    tool.setClassLoader(ClassLoader.getSystemClassLoader());
    tool.generate();
    if (!tool.getProblemProvider().getSevereProblems().isEmpty()) {
      for (var problem : tool.getProblemProvider().getSevereProblems()) {
        System.err.println(problem.getText() + " " + java.util.Arrays.toString(problem.getParams()));
      }
      throw new AssertionError("TeaVM compilation failed");
    }
  }
}
`
};

try {
  assert.ok(existsSync(rawClasslib), `TeaVM classlib jar missing: ${rawClasslib}`);
  const workingClasslib = join(temp, baseline
    ? 'teavm-classlib-0.15.0-baseline.jar'
    : 'teavm-classlib-0.15.0-patched.jar');
  await copyFile(rawClasslib, workingClasslib);
  if (!baseline && !suppliedClasslib) {
    assert.ok(existsSync(asm) && existsSync(asmTree) && existsSync(asmAnalysis),
      'ASM 9.8 jars are required to run the production TeaVMClasslibPatcher');
    const patcherClasses = join(temp, 'patcher-classes');
    const patchOutput = join(temp, 'formatter-patches');
    await mkdir(patcherClasses, {recursive: true});
    await mkdir(patchOutput, {recursive: true});
    const toolSources = join(repositoryRoot, 'port/tools/src/main/java');
    const patcherSource = join(toolSources, 'dev/gaius/tools/TeaVMClasslibPatcher.java');
    // The patcher reads the TeaVM target through BuildTarget; -sourcepath resolves any other
    // tools class it references.
    const buildTargetSource = join(toolSources, 'dev/gaius/tools/BuildTarget.java');
    const patcherClasspath = [asm, asmTree, asmAnalysis].join(delimiter);
    execFileSync(fixtureJavac, ['--release', '21', '-proc:none', '-cp', patcherClasspath,
      '-sourcepath', toolSources, '-d', patcherClasses, patcherSource, buildTargetSource], {
      stdio: 'pipe', maxBuffer: 20 * 1024 * 1024,
    });
    // The fixture compiles with the JavaScript backend: patch the class library for it.
    execFileSync(fixtureJava, ['-Dgaius.teavm.target=js',
      '-cp', [patcherClasses, patcherClasspath].join(delimiter),
      'dev.gaius.tools.TeaVMClasslibPatcher', workingClasslib, patchOutput], {
      stdio: 'pipe', timeout: 120_000, maxBuffer: 20 * 1024 * 1024,
    });
    const jar = javaHome ? join(javaHome, 'bin', `jar${suffix}`) : 'jar';
    execFileSync(jar, ['--update', '--file', workingClasslib, '-C', patchOutput, '.'], {
      stdio: 'pipe', timeout: 30_000, maxBuffer: 20 * 1024 * 1024,
    });
  }
  teaVmJars.unshift(workingClasslib);
  const files = [];
  for (const [name, content] of Object.entries(sources)) {
    const file = join(temp, name);
    await writeFile(file, content);
    files.push(file);
  }
  const classpath = [temp, ...teaVmJars].join(delimiter);
  execFileSync(fixtureJavac, ['--release', '21', '-cp', classpath, '-d', temp, ...files], {
    stdio: 'pipe', maxBuffer: 20 * 1024 * 1024,
  });
  const target = join(temp, 'generated');
  await mkdir(target, {recursive: true});
  execFileSync(fixtureJava, ['-Xmx1g', '-cp', classpath, 'CompileFormatterPercentFixture', target], {
    stdio: 'pipe', timeout: 120_000, maxBuffer: 20 * 1024 * 1024,
  });
  const runner = join(temp, 'run.cjs');
  await writeFile(runner, `
require(${JSON.stringify(join(target, 'fixture.cjs'))}).main([], function(error) {
  if (error) { console.error(error); process.exitCode = 1; return; }
  setImmediate(function() {
    const callback = globalThis.gaiusFormatterPercentCallback;
    if (typeof callback !== 'function') throw Error('formatter callback was not installed');
    const expected = [
      '1% 2',
      'Mem:  3% 123/456MiB',
      'x% x',
      '22% 11',
      '%',
      'plain text',
      'a\\nb'
    ];
    const received = globalThis.gaiusFormatterPercentResult;
    callback(received);
    console.log(JSON.stringify({expected: expected, received: received, callbackInstalled: true}));
  });
});
`);
  const output = execFileSync(process.execPath, [runner], {
    encoding: 'utf8', timeout: 15_000, maxBuffer: 2 * 1024 * 1024,
  }).trim();
  const result = JSON.parse(output);
  assert.deepEqual(result.expected, [
    '1% 2', 'Mem:  3% 123/456MiB', 'x% x', '22% 11', '%', 'plain text', 'a\nb',
  ]);
  const received = result.received.split(String.fromCharCode(31));
  assert.equal(received.length, result.expected.length,
    'TeaVM callback did not return one result per formatter case');
  if (baseline) {
    assert.ok(received[0].startsWith('ERROR:'), 'baseline %d%% case no longer reproduces the formatter bug');
    assert.ok(received[1].startsWith('ERROR:'), 'baseline Mem format no longer reproduces the formatter bug');
    assert.ok(received[2].startsWith('ERROR:'), 'baseline %s%% reuse case no longer reproduces the formatter bug');
    assert.equal(received[3], '22% 11', 'baseline explicit-index case changed unexpectedly');
    assert.equal(received[4], '%', 'baseline literal-percent case changed unexpectedly');
    assert.equal(received[5], 'plain text', 'baseline ordinary case changed unexpectedly');
    assert.ok(received[6].startsWith('ERROR:java.util.UnknownFormatConversionException:'),
      'baseline %n unsupported case changed unexpectedly');
  } else {
    assert.deepEqual(received.slice(0, 6), result.expected.slice(0, 6),
      'patched Formatter output did not match the supported percent cases');
    assert.ok(received[6].startsWith('ERROR:java.util.UnknownFormatConversionException:'),
      'patched Formatter changed the documented unsupported %n behavior');
  }
  const report = {
    status: baseline ? 'REGRESSION_REPRODUCED' : 'PATCHED_FORMATTER_PERCENT_PASS',
    teaVmVersion: '0.15.0',
    cases: result.expected,
    observed: received,
    casesCovered: ['d-percent-d', 'Mem-3-Long-percent', 's-percent-reuse',
      'explicit-index-percent', 'literal-percent', 'ordinary-format', 'newline'],
    mode: baseline ? 'baseline' : (suppliedClasslib
      ? 'prebuilt-production-patcher' : 'production-patcher'),
    note: baseline
      ? 'Raw TeaVM 0.15 classlib regression capture.'
      : 'Production TeaVMClasslibPatcher output was used before TeaVM compilation; %n remains the known TeaVM 0.15 unsupported conversion.'
  };
  await writeFile(join(outputDirectory, baseline ? 'baseline-result.json' : 'result.json'),
    `${JSON.stringify(report, null, 2)}\n`);
  console.log('FORMATTER_PERCENT_REGRESSION_PASS ' + JSON.stringify(report));
} finally {
  await rm(temp, {recursive: true, force: true});
}

