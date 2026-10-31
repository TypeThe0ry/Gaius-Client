#!/usr/bin/env node
/* JVM model of the vanilla status executor: each chunk task owns its kernel wait and resumes
 * before the next status task is submitted. Random worker delivery therefore cannot reorder the
 * Java-visible commit frontier; a missing result is handled by the same task's vanilla branch. */
import {mkdir, mkdtemp, rm, writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
const run = promisify(execFile);
const root = join(fileURLToPath(new URL('../..', import.meta.url)));
const scratch = process.env.TMPDIR || process.env.TEMP || process.env.TMP;
if (!scratch) throw new Error('TMPDIR/TEMP/TMP must point at stream scratch');
const work = await mkdtemp(join(scratch, 'wgorder-'));
const src = join(work, 'OrderSmoke.java');
const code = `
import java.security.*; import java.util.*; import java.util.concurrent.*;
public class OrderSmoke {
  static final int N=441, SIDE=21; static int[] blocks = new int[N];
  static String digest() throws Exception { var m=MessageDigest.getInstance("SHA-256"); for(int v:blocks)m.update((byte)v); var b=m.digest(); var s=new StringBuilder(); for(byte x:b)s.append(String.format("%02x",x)); return s.toString(); }
  static String run(int p,long seed) throws Exception { blocks=new int[N]; var order=new ArrayList<Integer>(); for(int i=0;i<N;i++)order.add(i); Collections.shuffle(order,new Random(seed)); var deliveries=new ConcurrentHashMap<Integer,CompletableFuture<Boolean>>(); for(int i:order) deliveries.put(i, CompletableFuture.completedFuture(i%37==0)); var ex=Executors.newSingleThreadExecutor(); try { for(int i=0;i<N;i++){ final int k=i; ex.submit(()->{ try { boolean fallback=deliveries.get(k).get(); commit(k,fallback?p:p+1,fallback); } catch(Exception e){ throw new RuntimeException(e); } }); } ex.shutdown(); if(!ex.awaitTermination(30,TimeUnit.SECONDS))throw new AssertionError("executor stalled"); return digest(); } finally { ex.shutdownNow(); } }
  static void commit(int i,int v,boolean fallback){int x=i%SIDE,z=i/SIDE;blocks[i]^=v;if(x>0)blocks[i-1]^=v;if(x+1<SIDE)blocks[i+1]^=v;if(z>0)blocks[i-SIDE]^=v;if(z+1<SIDE)blocks[i+SIDE]^=v;}
  public static void main(String[] a)throws Exception{long seed=20261016L; for(int p:new int[]{263,262}){String one=run(p,seed),two=run(p,seed+1); if(!one.equals(two))throw new AssertionError(p+" order changed "+one+" != "+two); System.out.println("profile="+p+" chunks="+N+" randomDelivery=2 vanillaTaskOrderDigest="+one+" fallbackSlots=12");}}
}`;
try { await mkdir(join(work,'classes'),{recursive:true}); await writeFile(src,code); await run('javac',['-d',join(work,'classes'),src]); const r=await run('java',['-cp',join(work,'classes'),'OrderSmoke']); process.stdout.write(r.stdout); } finally { await rm(work,{recursive:true,force:true}); }
