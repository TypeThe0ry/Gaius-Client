#!/usr/bin/env node
// BrowserFilePersistence 的无浏览器 JVM 合约测试。只抽取真实 Java 方法并配 fake backend，
// 因而不需要 TeaVM、Chrome 或 IndexedDB；用于把存储错误处理固定在 CI。
import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {mkdir, mkdtemp, rm} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../..", import.meta.url));
const out = process.argv[2] ? resolve(process.argv[2]) : await mkdtemp(join(tmpdir(), "gaius-persistence-jvm-"));
const source = await (await import("node:fs/promises")).readFile(
  join(root, "port/overrides/classlib/src/main/java/dev/gaius/browser/BrowserFilePersistence.java"), "utf8");

function method(signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `missing ${signature}`);
  const open = source.indexOf("{", start);
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    if (source[i] === "{") depth++;
    else if (source[i] === "}" && --depth === 0) return source.slice(start, i + 1);
  }
  throw new Error(`unbalanced ${signature}`);
}

const extracted = [
  "public static boolean persist(String path, byte[] bytes) {",
  "public static boolean supportsRangePersistence(String path) {",
  "public static boolean delete(String path) {",
  "public static void prepareWorldDirectoryForVanillaDelete(String worldId) throws IOException {",
  "private static void deleteWorldDirectoryChildren(",
  "public static void syncMove(String source, String target) throws IOException {",
  "private static boolean shouldPersist(String path) {",
  "private static boolean isScreenshotPath(String normalized) {",
  "private static boolean isDownloadedPackFile(String normalized) {",
  "private static boolean isUserResourcePackFile(String normalized) {",
  "private static boolean isBinaryChunkStoragePath(String normalized) {",
  "private static boolean isChunkStoragePath(String normalized) {",
  "private static boolean shouldRestoreAtStartup(String path) {",
  "private static String normalize(String path) {",
  "private static String parent(String path) {",
  "private static String name(String path) {",
].map(method).join("\n\n");

const fixture = `
import java.io.*; import java.util.*;
public final class BrowserFilePersistenceJvmTest {
  static final String SCREENSHOT_ROOT = "/gaius/screenshots/";
  static final String USER_RESOURCE_PACK_ROOT = "/gaius/resourcepacks/";
  static final String USER_RESOURCE_PACK_IMPORT_ROOT = "/gaius/resourcepack-imports/";
  static final Map<String,byte[]> files = new LinkedHashMap<>();
  static final Set<String> LAZY_CHUNK_FILE_PLACEHOLDERS = new HashSet<>();
  static final List<String> calls = new ArrayList<>();
  static boolean quota, patch; static int screenshot; static String activeWorld;
  static final class VirtualFile {
    final String name; final boolean directory; VirtualFile parent;
    final LinkedHashMap<String,VirtualFile> children = new LinkedHashMap<>();
    VirtualFile(String name, boolean directory){this.name=name;this.directory=directory;}
    boolean isDirectory(){return directory;} boolean isFile(){return !directory;}
    String[] listFiles(){return directory?children.keySet().toArray(String[]::new):null;}
    VirtualFile getChildFile(String name){return children.get(name);}
    boolean delete(){
      if(parent==null || (directory && !children.isEmpty())) return false;
      parent.children.remove(name); parent=null; return true;
    }
  }
  static final class VirtualFileSystemProvider {
    static final VirtualFile ROOT = new VirtualFile("", true);
    static VirtualFileSystemProvider getInstance(){return Holder.INSTANCE;}
    static final class Holder { static final VirtualFileSystemProvider INSTANCE = new VirtualFileSystemProvider(); }
    VirtualFile getFile(String path){
      String normalized=path.replace('\\\\','/');
      if(normalized.equals("/")) return ROOT;
      VirtualFile current=ROOT;
      for(String part:normalized.split("/")) if(!part.isEmpty()){
        current=current.children.get(part); if(current==null)return null;
      }
      return current;
    }
  }
  static VirtualFile addVirtual(String path, boolean directory){
    String[] parts=path.split("/"); VirtualFile current=VirtualFileSystemProvider.ROOT;
    for(int i=1;i<parts.length;i++) if(!parts[i].isEmpty()){
      VirtualFile next=current.children.get(parts[i]);
      if(next==null){next=new VirtualFile(parts[i], i==parts.length-1?directory:true); next.parent=current; current.children.put(parts[i],next);}
      current=next;
    }
    return current;
  }
  static void clearVirtual(){VirtualFileSystemProvider.ROOT.children.clear();}
  static String storagePrefix(){return "gaius.test:";}
  static String activeServerWorldId(){return activeWorld;}
  static boolean isOnDemandChunkStoragePath(String p){return false;}
  static boolean canPatchBytes(String p){return patch;}
  static boolean setBytes(String p, byte[] b){calls.add("bytes:"+p); if(quota)return false; files.put(p,b.clone()); return true;}
  static boolean setItem(String k,String v){calls.add("item:"+k); if(quota)return false; files.put(k.substring(storagePrefix().length()), v.getBytes()); return true;}
  static int storeScreenshot(String p, byte[] b){calls.add("shot:"+p); return screenshot;}
  static void removeItem(String k){calls.add("delete:"+k); files.remove(k.substring(storagePrefix().length()));}
  static boolean hasStoredPath(String p){return files.containsKey(p);}
  static String getItem(String k){byte[] b=files.get(k.substring(storagePrefix().length())); return b==null?null:new String(b);}
  static int storedByteLength(String p){return -1;} static int copyStoredBytes(String p,byte[] b){return -1;}
  static void markDownloadedPackPersisted(String p,int n){}
  static boolean syncFile(String p){ calls.add("sync:"+p); byte[] b=files.get(p); return b!=null && persist(p,b); }
  static boolean restore(String p){return false;} static void report(String e,String d){calls.add("report:"+e+":"+d);}
  static void reclaimMaterializedChunkFileIfUnreferenced(String p) { calls.add("reclaim:"+p); }
  static void expect(boolean x,String m){if(!x)throw new AssertionError(m);}
  static void eq(Object a,Object b,String m){if(!Objects.equals(a,b))throw new AssertionError(m+" got="+a+" want="+b);}
  ${extracted}
  public static void main(String[] a) throws Exception {
    // Durable selection includes worlds, options, servers and screenshots, while unrelated files stay in RAM.
    expect(shouldPersist("saves/W/level.dat") && shouldPersist("/options.txt") && shouldPersist("/x/servers.dat") && shouldPersist("/gaius/screenshots/a.png"),"durable paths");
    expect(!shouldPersist("/logs/latest.log"),"log is not durable");
    calls.clear(); quota=false; screenshot=-1;
    expect(persist("/gaius/screenshots/a.png",new byte[]{1}),"fallback screenshot mirror");
    eq(calls,List.of("shot:/gaius/screenshots/a.png","item:gaius.test:/gaius/screenshots/a.png"),"screenshot fallback ordering");
    calls.clear(); screenshot=1; expect(persist("/gaius/screenshots/b.png",new byte[]{1,2}),"screenshot store");
    eq(calls,List.of("shot:/gaius/screenshots/b.png"),"screenshot never mirrored");
    calls.clear(); quota=true; expect(!persist("/options.txt",new byte[]{1}),"quota is write failure");
    expect(calls.get(calls.size()-1).startsWith("report:storage-quota-or-error"),"quota telemetry");
    quota=false; files.put("/saves/old/level.dat",new byte[]{9}); calls.clear();
    expect(delete("/saves/old/level.dat"),"world delete is accepted");
    eq(calls,List.of("delete:gaius.test:/saves/old/level.dat"),"delete ordering");
    expect(!shouldRestoreAtStartup("/gaius/screenshots/a.png"),"screenshots are never restored");
    expect(shouldRestoreAtStartup("/gaius/options.txt"),"options are restored");
    expect(shouldRestoreAtStartup("/gaius/saves/W/level.dat"),"world summary is restored");
    expect(!shouldRestoreAtStartup("/gaius/saves/W/region/r.0.0.mca"),"regions are on demand");
    activeWorld="W"; patch=true;
    expect(isBinaryChunkStoragePath("/gaius/saves/W/region/r.0.0.mca"),"active region is binary storage");
    expect(supportsRangePersistence("/gaius/saves/W/region/r.0.0.mca"),"range backend capability");
    clearVirtual();
    addVirtual("/gaius/saves/W/session.lock", false);
    addVirtual("/gaius/saves/W/level.dat", false);
    addVirtual("/gaius/saves/W/region/r.0.0.mca", false);
    addVirtual("/gaius/saves/W/data/nested.dat", false);
    prepareWorldDirectoryForVanillaDelete("W");
    VirtualFile world = VirtualFileSystemProvider.getInstance().getFile("/gaius/saves/W");
    eq(Arrays.asList(world.listFiles()), List.of("session.lock"), "VFS delete keeps session lock only");
    expect(VirtualFileSystemProvider.getInstance().getFile("/gaius/saves/W/level.dat") == null, "VFS world file removed");
    expect(VirtualFileSystemProvider.getInstance().getFile("/gaius/saves/W/region") == null, "VFS empty region directory removed");
    expect(VirtualFileSystemProvider.getInstance().getFile("/gaius/saves/W/data") == null, "VFS empty data directory removed");
    expect(VirtualFileSystemProvider.getInstance().getFile("/gaius/saves/W/session.lock") != null, "VFS session lock preserved");
  }
}
`;

await rm(out,{recursive:true,force:true}); await mkdir(join(out,"src"),{recursive:true}); await mkdir(join(out,"classes"),{recursive:true});
await (await import("node:fs/promises")).writeFile(join(out,"src/BrowserFilePersistenceJvmTest.java"), fixture);
// javac catches signature drift in the production methods; execution checks their control flow.
execFileSync("javac",["--release","21","-proc:none","-d",join(out,"classes"),join(out,"src/BrowserFilePersistenceJvmTest.java")],{stdio:"inherit"});
execFileSync("java",["-cp",join(out,"classes"),"BrowserFilePersistenceJvmTest"],{stdio:"inherit"});
console.log("BrowserFilePersistence JVM contract PASS");
