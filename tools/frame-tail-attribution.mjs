import {readFile,writeFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {traceEvents} from './main-thread-profile.mjs';

export function summarizeTailFrames(frames,threshold=50){
  if(!Array.isArray(frames?.starts)||frames.starts.length!==frames.ends?.length)
    throw new Error('paired frame starts/ends required');
  const intervals=frames.starts.map((start,i)=>({index:i,startMs:start,endMs:frames.ends[i],durationMs:frames.ends[i]-start}));
  if(intervals.some(f=>!Number.isFinite(f.durationMs)||f.durationMs<0))throw new Error('invalid frame interval');
  const sorted=intervals.map(f=>f.durationMs).sort((a,b)=>a-b);
  const q=p=>sorted.length?sorted[Math.min(sorted.length-1,Math.floor(sorted.length*p))]:null;
  return {source:frames.source||'requestAnimationFrame',frames:intervals.length,
    over50:intervals.filter(f=>f.durationMs>50).length,over100:intervals.filter(f=>f.durationMs>100).length,
    p99Ms:q(.99),maxMs:sorted.at(-1)??null,longFrames:intervals.filter(f=>f.durationMs>threshold)};
}

export async function attributeTailFrames(framesPath,tracePath,profilePath){
  const result=summarizeTailFrames(JSON.parse(await readFile(framesPath,'utf8')));
  let offset=null,main=null;
  for await(const e of traceEvents(tracePath)){
    if(e.name?.startsWith('gaius-frame-clock:')){
      offset=e.ts/1000-Number(e.name.slice('gaius-frame-clock:'.length));main=[e.pid,e.tid];break;
    }
  }
  if(!Number.isFinite(offset))throw new Error('trace has no frame clock marker; cannot align frames');
  result.traceClockOffsetMs=offset;result.mainThread=main;
  for(const f of result.longFrames){f.events=[];f.samples={};f.sampleGapsOver10Ms=0;}
  const pendingMeasures=new Map();
  for await(let e of traceEvents(tracePath)){
    if(e.pid!==main[0]||e.tid!==main[1])continue;
    if(e.name?.startsWith('gaius-gl:')&&(e.ph==='b'||e.ph==='e')){
      const key=e.name+':'+JSON.stringify(e.id2||e.id);
      if(e.ph==='b'){pendingMeasures.set(key,e);continue;}
      const begin=pendingMeasures.get(key);pendingMeasures.delete(key);
      if(!begin)continue;e={...begin,ph:'X',dur:e.ts-begin.ts};
    }
    if(e.ph!=='X'||!(e.dur>0))continue;
    if(!/GC|Scavenge|MarkCompact|V8\.GC|gaius-gl:|RunTask|RunMicrotasks|FunctionCall|FireAnimationFrame/.test(e.name))continue;
    const start=e.ts/1000-offset,end=start+e.dur/1000;
    for(const f of result.longFrames){
      const overlap=Math.min(end,f.endMs)-Math.max(start,f.startMs);
      if(overlap>0)f.events.push({name:e.name,overlapMs:overlap,durationMs:e.dur/1000});
    }
  }
  if(profilePath){
    const p=JSON.parse(await readFile(profilePath,'utf8')),nodes=new Map((p.nodes||[]).map(n=>[n.id,n]));
    let timestamp=p.startTime;
    for(let i=0;i<(p.samples||[]).length;i++){
      const delta=p.timeDeltas?.[i]||0;timestamp+=delta;
      const at=timestamp/1000-offset,n=nodes.get(p.samples[i]);
      for(const f of result.longFrames){
        if(at<f.startMs||at>f.endMs)continue;
        const name=n?.callFrame?.functionName||'(unknown)';
        f.samples[name]=(f.samples[name]||0)+1;
        if(delta>10000)f.sampleGapsOver10Ms++;
      }
    }
  }
  result.caveat='Task durations and profiler gaps include descheduling; overlap is evidence, not proof of CPU causality. rAF is not a presented frame unless source says presented-frame-guard.';
  return result;
}

if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  const [frames,trace,output,profile]=process.argv.slice(2);
  if(!output)throw new Error('usage: frame-tail-attribution.mjs frames.json trace.json output.json [symbolized.cpuprofile]');
  const result=await attributeTailFrames(frames,trace,profile);
  await writeFile(output,JSON.stringify(result,null,2));
  console.log(JSON.stringify({...result,longFrames:result.longFrames.length}));
}
