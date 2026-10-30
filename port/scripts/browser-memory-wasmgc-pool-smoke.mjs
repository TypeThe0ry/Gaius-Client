import assert from "node:assert/strict";
import {mkdirSync, readFileSync, writeFileSync} from "node:fs";
import {join, resolve} from "node:path";
import {spawnSync} from "node:child_process";

assert.ok(process.env.OUT, "OUT must name a scratch directory");
const out = resolve(process.env.OUT), classes = join(out, "classes"), sources = join(out, "src");
mkdirSync(classes, {recursive: true});
function write(path, text) {
  const full = join(sources, path);
  mkdirSync(resolve(full, ".."), {recursive: true});
  writeFileSync(full, text);
  return full;
}
const files = [];
files.push(write("org/lwjgl/system/BrowserMemory.java", readFileSync(new URL("../overrides/libraries/lwjgl/src/target-wasmgc/java/org/lwjgl/system/BrowserMemory.java", import.meta.url), "utf8")));
files.push(write("org/teavm/jso/JSBody.java", `package org.teavm.jso; public @interface JSBody {String[] params() default {}; String script();}`));
files.push(write("org/teavm/jso/typedarrays/Float64Array.java", `package org.teavm.jso.typedarrays; public interface Float64Array {}`));
files.push(write("dev/gaius/browser/JsArrays.java", `package dev.gaius.browser; public final class JsArrays {public static org.teavm.jso.typedarrays.Float64Array float64(double[] data){return null;}}`));
files.push(write("dev/gaius/browser/wasm/LinearMemory.java", `package dev.gaius.browser.wasm;
import java.nio.*;
public final class LinearMemory {
  public static ByteBuffer wrap(int address,int size){return ByteBuffer.wrap(org.teavm.interop.Address.memory,address,size).slice();}
  public static int address(Buffer buffer){return 0;}
}`));
files.push(write("org/joml/Matrix4fc.java", `package org.joml; public interface Matrix4fc {${[0,1,2,3].flatMap(column => [0,1,2].map(row => `float m${column}${row}();`)).join(" ")}}`));
files.push(write("org/teavm/interop/Address.java", `package org.teavm.interop;
import java.nio.*;
public final class Address {
  public static final byte[] memory=new byte[32*1024*1024];
  private static final ByteBuffer bytes=ByteBuffer.wrap(memory).order(ByteOrder.LITTLE_ENDIAN);
  private final int value;
  private Address(int value){this.value=value;}
  public static Address fromInt(int value){return new Address(value);}
  public int toInt(){return value;}
  public Address add(int offset){return fromInt(value+offset);}
  public static void moveMemoryBlock(Address source,Address target,int size){System.arraycopy(memory,source.value,memory,target.value,size);}
  public static void fillZero(Address address,int size){fill(address,(byte)0,size);}
  public static void fill(Address address,byte value,int size){java.util.Arrays.fill(memory,address.value,address.value+size,value);}
  ${["Byte","Short","Int","Long","Float","Double"].map(type => `public ${type.toLowerCase()} get${type}(){return bytes.get${type === "Byte" ? "" : type}(value);} public void put${type}(${type.toLowerCase()} data){bytes.put${type === "Byte" ? "" : type}(value,data);}`).join("\n")}
}`));
files.push(write("org/teavm/runtime/heap/Heap.java", `package org.teavm.runtime.heap;
import java.util.*;
import org.teavm.interop.Address;
public final class Heap {
  private static int next=1028;
  public static boolean move,fail;
  public static int calls,reallocs;
  public static final Map<Integer,Integer> allocations=new HashMap<>();
  public static Address alloc(int size){calls++;if(fail)return Address.fromInt(0);size=(size+3)&~3;int base=next;next+=size+128;allocations.put(base,size);return Address.fromInt(base);}
  public static Address realloc(Address address,int size){
    reallocs++;if(fail)return Address.fromInt(0);size=(size+3)&~3;
    if(!move){allocations.put(address.toInt(),size);next=Math.max(next,address.toInt()+size+128);return address;}
    Address replacement=alloc(size);Address.moveMemoryBlock(address,replacement,Math.min(allocations.get(address.toInt()),size));release(address);return replacement;
  }
  public static void release(Address address){if(allocations.remove(address.toInt())==null)throw new AssertionError("invalid Heap release");}
}`));
files.push(write("org/lwjgl/system/MemoryPoolSmoke.java", `package org.lwjgl.system;
import org.teavm.runtime.heap.Heap;
public final class MemoryPoolSmoke {
  private static long field(String name)throws Exception{var field=BrowserMemory.class.getDeclaredField(name);field.setAccessible(true);return field.getLong(null);}
  private static long owner(int index)throws Exception{var field=BrowserMemory.class.getDeclaredField("OWNER_LIVE");field.setAccessible(true);return ((long[])field.get(null))[index];}
  private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
  public static void main(String[] args)throws Exception {
    boolean enabled=!args[0].equals("disabled");
    int before=Heap.calls;
    for(int count=0;count<1000;count++){long address=BrowserMemory.allocate(1000);check((address&7)==0,"alignment");BrowserMemory.free(address);}
    int nativeCalls=Heap.calls-before;
    check(nativeCalls==(enabled?1:1000),"reuse switch");
    long tagged=BrowserMemory.allocate(100000);
    BrowserMemory.claimOwner(tagged,3);
    check(owner(0)==0 && owner(3)==100000,"owner transfer");
    tagged=BrowserMemory.reallocate(tagged,300000);
    check(owner(3)==300000,"owner realloc");
    long entryPeak=BrowserMemory.peakLiveBytes();
    check(entryPeak==(enabled?300000:400000),"realloc transient peak");
    BrowserMemory.free(tagged);
    check(owner(3)==0,"owner release");
    long staging=BrowserMemory.allocate(1024);
    BrowserMemory.claimOwner(staging,4);
    check(owner(4)==1024,"staging owner");
    BrowserMemory.free(staging);
    long aligned=BrowserMemory.alignedAllocate(64,1000);
    BrowserMemory.claimOwner(aligned,3);
    check(owner(2)==1000 && owner(3)==0,"aligned identity preserved");
    BrowserMemory.free(aligned);
    long address=BrowserMemory.allocate(1000);
    for(int index=0;index<1000;index++)BrowserMemory.putByte(address+index,(byte)index);
    long replacement=BrowserMemory.reallocate(address,1024);
    check(!enabled || replacement==address,"size-class realloc");
    for(int index=0;index<1000;index++)check(BrowserMemory.getByte(replacement+index)==(byte)index,"realloc bytes");
    BrowserMemory.free(replacement);
    for(int alignment=16;alignment<=65536;alignment*=2){long alignedBlock=BrowserMemory.alignedAllocate(alignment,777);check(alignedBlock%alignment==0,"large alignment");BrowserMemory.free(alignedBlock);}
    BrowserMemory.trimPool();
    check(field("reservedBytes")==0,"aligned reservations");
    for(boolean move:new boolean[]{false,true}){
      Heap.move=move;long large=BrowserMemory.allocate(100000);
      for(int index=0;index<100000;index++)BrowserMemory.putByte(large+index,(byte)index);
      large=BrowserMemory.reallocate(large,300000);
      for(int index=0;index<100000;index++)check(BrowserMemory.getByte(large+index)==(byte)index,"native realloc alignment copy");
      large=BrowserMemory.reallocate(large,90000);
      for(int index=0;index<90000;index++)check(BrowserMemory.getByte(large+index)==(byte)index,"shrink bytes move="+move+" index="+index+" address="+large+" actual="+BrowserMemory.getByte(large+index));
      Heap.fail=true;
      try{BrowserMemory.reallocate(large,500000);throw new AssertionError("missing OOM");}catch(OutOfMemoryError expected){}
      Heap.fail=false;check(BrowserMemory.getByte(large+12)==12,"OOM preserves old allocation");
      BrowserMemory.free(large);
    }
    long[] blocks=new long[64];
    for(int index=0;index<blocks.length;index++)blocks[index]=BrowserMemory.allocate(65536);
    for(long block:blocks)BrowserMemory.free(block);
    check(field("pooledBytes")<=2*1024*1024,"pool byte cap");
    check(field("pooledBytes")<=8*65536,"pool slot cap");
    long zero=BrowserMemory.allocate(0);BrowserMemory.free(zero);
    try{BrowserMemory.allocate(-1);throw new AssertionError("missing size rejection");}catch(OutOfMemoryError expected){}
    long calloc=BrowserMemory.calloc(1,1024);
    for(int index=0;index<1024;index++)check(BrowserMemory.getByte(calloc+index)==0,"calloc clears reused memory");
    BrowserMemory.free(calloc);
    try{BrowserMemory.free(calloc);throw new AssertionError("missing double-free rejection");}catch(IllegalArgumentException expected){}
    BrowserMemory.trimPool();
    check(BrowserMemory.liveBytes()==0 && BrowserMemory.liveRegions()==0,"live accounting");
    check(field("reservedBytes")==0 && Heap.allocations.isEmpty(),"all reservations released");
    for(int index=0;index<5;index++)check(owner(index)==0,"owner accounting empty");
    System.out.println("PASS "+args[0]+" nativeCalls="+nativeCalls+" reallocPeak="+entryPeak+" peakLive="+BrowserMemory.peakLiveBytes()+" peakReserved="+field("peakReservedBytes"));
  }
}`));
function run(command, args) {
  const result = spawnSync(command, args, {encoding: "utf8"});
  if (result.stdout) process.stdout.write(result.stdout);
  if (result.stderr) process.stderr.write(result.stderr);
  assert.equal(result.status, 0, command);
}
run("javac", ["-J-Xmx3g", "-encoding", "UTF-8", "-d", classes, ...files]);
for (const mode of ["enabled", "disabled"]) run("java", ["-Xmx3g",
  `-Dgaius.browser.memory.poolBytes=${mode === "enabled" ? 2097152 : 0}`,
  "-cp", classes, "org.lwjgl.system.MemoryPoolSmoke", mode]);
