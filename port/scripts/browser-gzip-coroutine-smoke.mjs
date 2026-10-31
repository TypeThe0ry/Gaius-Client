import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdir, mkdtemp, readFile, readdir, rm, writeFile} from 'node:fs/promises';
import {homedir, tmpdir} from 'node:os';
import {delimiter, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {gzipSync} from 'node:zlib';

// Compile the real BrowserGzip with TeaVM and call it through a synchronous
// JavaScript callback. Minimal NBT types isolate the coroutine boundary; the
// decompressor is the real TeaVM GZIPInputStream, not a JS mock.
const root = fileURLToPath(new URL('../../', import.meta.url));
const input = resolve(process.argv[2] || join(root, 'port/src/main/java/dev/gaius/browser/BrowserGzip.java'));
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
assert.ok(jars.some(name => name.includes('teavm-tooling')), 'TeaVM 0.15 tooling must be installed');
jars.push(join(repository, 'com', 'jcraft', 'jzlib', '1.1.3', 'jzlib-1.1.3.jar'));
const suffix = process.platform === 'win32' ? '.exe' : '';
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', `java${suffix}`) : 'java';
const javac = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', `javac${suffix}`) : 'javac';
const temp = await mkdtemp(join(tmpdir(), 'gaius-gzip-coroutine-'));
const payloadParts = [];
const put8 = value => payloadParts.push(value & 255);
const put16 = value => { put8(value >> 8); put8(value); };
const put32 = value => { put8(value >> 24); put8(value >> 16); put8(value >> 8); put8(value); };
const put64 = value => { let n = BigInt(value); for (let i = 7; i >= 0; i--) put8(Number(n >> BigInt(i * 8))); };
const putModifiedUtf = value => {
  const encoded = [];
  for (let i = 0; i < value.length; i++) {
    const c = value.charCodeAt(i);
    if (c === 0) encoded.push(0xc0, 0x80);
    else if (c <= 0x7f) encoded.push(c);
    else if (c <= 0x7ff) encoded.push(0xc0 | (c >> 6), 0x80 | (c & 63));
    else encoded.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
  }
  put16(encoded.length); encoded.forEach(put8);
};
put32(96);
for (let i = 0; i < 96; i++) {
  putModifiedUtf(`NBT\0-${String.fromCharCode(0xD83D, 0xDE80)}-${i}`);
  put32(i * 17 - 301); put64(9007199254740000n + BigInt(i));
  put16((i * 13) & 0xffff); put8(i); put32(7);
  for (let j = 0; j < 7; j++) put8(i + j * 31);
}
const payload = Buffer.from(payloadParts);
const compressed = gzipSync(payload);
const javaBytes = [...compressed].map(value => value > 127 ? value - 256 : value).join(',');
try {
  const sources = {
    'dev/gaius/browser/BrowserGzip.java': await readFile(input, 'utf8'),
    'net/minecraft/nbt/CompoundTag.java': `package net.minecraft.nbt; public class CompoundTag { public long checksum; public int rows; public CompoundTag(long c,int r) { checksum=c; rows=r; } }`,
    'net/minecraft/nbt/NbtAccounter.java': `package net.minecraft.nbt; public class NbtAccounter { public static NbtAccounter unlimitedHeap() { return new NbtAccounter(); } }`,
    'net/minecraft/nbt/NbtIo.java': `package net.minecraft.nbt;
      import java.io.*; import java.util.zip.GZIPInputStream;
      public class NbtIo {
        public static CompoundTag readCompressed(InputStream in,NbtAccounter a) throws IOException {
          return read(new DataInputStream(new BufferedInputStream(new GZIPInputStream(in))),a);
        }
        public static CompoundTag read(DataInput in,NbtAccounter a) throws IOException {
          int rows=in.readInt(); long sum=rows;
          for(int i=0;i<rows;i++){String s=in.readUTF();if(!s.equals("NBT"+(char)0+"-"+new String(new char[]{(char)0xD83D,(char)0xDE80})+"-"+i))throw new IOException("modified UTF content");sum+=s.length();sum+=in.readInt();sum+=in.readLong();sum+=in.readUnsignedShort();sum+=in.readUnsignedByte();int n=in.readInt();byte[] b=new byte[n];in.readFully(b);for(byte x:b)sum+=x&255;}
          return new CompoundTag(sum,rows);
        }
      }`,
    'GzipCallbackFixture.java': `
      import java.io.*; import dev.gaius.browser.BrowserGzip; import net.minecraft.nbt.CompoundTag; import org.teavm.jso.*;
      public class GzipCallbackFixture {
        @JSFunctor interface Reader extends JSObject { int read(); }
        @JSBody(params="reader",script="globalThis.readGzipFixture=reader;") static native void install(Reader reader);
        public static void main(String[] args) {
          install(()-> {
            try {
              CompoundTag tag=BrowserGzip.readCompressedNbt(new ByteArrayInputStream(new byte[]{${javaBytes}}));
              if(tag.rows!=96 || tag.checksum!=${(() => { let x=96n; for(let i=0;i<96;i++){const s=`NBT\0-${String.fromCharCode(0xD83D,0xDE80)}-${i}`;x+=BigInt(s.length)+BigInt(i*17-301)+9007199254740000n+BigInt(i)+BigInt((i*13)&65535)+BigInt(i);for(let j=0;j<7;j++)x+=BigInt((i+j*31)&255)} return x; })()}L) throw new AssertionError("mixed primitive checksum");
              byte[] damaged=new byte[]{${javaBytes}};
              damaged[damaged.length-8]^=1;
              try {
                BrowserGzip.readCompressedNbt(new ByteArrayInputStream(damaged));
                throw new AssertionError("Bad gzip CRC was accepted");
              } catch(IOException expectedError) { }
              try {
                BrowserGzip.readCompressedNbt(new ByteArrayInputStream(damaged,0,damaged.length/2));
                throw new AssertionError("Truncated gzip was accepted");
              } catch(IOException expectedError) { }
              return tag.rows;
            } catch(IOException e) { throw new RuntimeException(e); }
          });
        }
      }`,
    'CompileGzipFixture.java': `
      import java.io.File; import org.teavm.tooling.*; import org.teavm.backend.javascript.JSModuleType;
      public class CompileGzipFixture {
        public static void main(String[] args) throws Exception {
          TeaVMTool tool=new TeaVMTool(); tool.setMainClass("GzipCallbackFixture");
          tool.setTargetDirectory(new File(args[0])); tool.setTargetFileName("fixture.cjs");
          tool.setJsModuleType(JSModuleType.COMMON_JS); tool.setObfuscated(false);
          tool.setClassLoader(ClassLoader.getSystemClassLoader()); tool.generate();
          for(var problem: tool.getProblemProvider().getSevereProblems())
            System.err.println(problem.getText()+" "+java.util.Arrays.toString(problem.getParams()));
          if(!tool.getProblemProvider().getSevereProblems().isEmpty()) throw new AssertionError("TeaVM compilation failed");
        }
      }`,
  };
  const files = [];
  for (const [name, content] of Object.entries(sources)) {
    const file = join(temp, name); await mkdir(resolve(file, '..'), {recursive:true});
    await writeFile(file, content); files.push(file);
  }
  const cp = [temp, ...jars].join(delimiter);
  execFileSync(javac, ['--release', '21', '-cp', cp, '-d', temp, ...files], {stdio:'pipe', maxBuffer:20*1024*1024});
  execFileSync(java, ['-Xmx1g', '-cp', cp, 'CompileGzipFixture', temp], {encoding:'utf8', timeout:120000, maxBuffer:20*1024*1024});
  const runner = join(temp, 'run.cjs');
  await writeFile(runner, `require('./fixture.cjs').main([], function(error) {
    if(error) { console.error(error); process.exitCode=1; return; }
    setImmediate(function() {
      const count=globalThis.readGzipFixture();
      if(count!==96) throw Error('Callback returned before gzip finished: '+count);
      console.log('TEAVM_GZIP_SYNC_CALLBACK_OK rows='+count);
    });
  });`);
  const output = execFileSync(process.execPath, [runner], {encoding:'utf8', timeout:15000});
  assert.equal(output.trim(), 'TEAVM_GZIP_SYNC_CALLBACK_OK rows='+96,
    'synchronous callback must complete once with the parsed mixed payload');
  console.log(output.trim());
} finally {
  await rm(temp, {recursive:true, force:true});
}
