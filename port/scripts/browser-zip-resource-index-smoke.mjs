import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdtemp, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const root = fileURLToPath(new URL('../..', import.meta.url));
const javaSuffix = process.platform === 'win32' ? '.exe' : '';
const javac = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', `javac${javaSuffix}`) : 'javac';
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', `java${javaSuffix}`) : 'java';
const helper = resolve(root, 'port/src/main/java/dev/gaius/browser/BrowserZipResourceIndex.java');
const fixture = resolve(root, 'port/scripts/fixtures/BrowserZipResourceIndexFixture.java');
const output = await mkdtemp(join(tmpdir(), 'gaius-browser-zip-index-'));
try {
  execFileSync(javac, ['--release', '17', '-proc:none', '-d', output, helper, fixture], {stdio: 'pipe'});
  const result = execFileSync(java, ['-Xverify:all', '-cp', output, 'dev.gaius.browser.BrowserZipResourceIndexFixture'], {
    encoding: 'utf8', timeout: 30000
  }).trim();
  assert.match(result, /^BROWSER_ZIP_RESOURCE_INDEX_OK /);
  console.log(result);
} finally {
  await rm(output, {recursive: true, force: true});
}
