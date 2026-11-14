// Diagnostic only: observe the existing frame guard without changing presentation.
export const FRAME_TAIL_PROBE_SOURCE = `(()=>{
  if(globalThis.__gaiusFrameTail)return;
  const guard=globalThis.__gaiusGlFrameGuard;
  if(!guard)throw new Error('frame guard unavailable');
  const descriptor=Object.getOwnPropertyDescriptor(guard,'frames');
  let value=guard.frames;
  const probe=globalThis.__gaiusFrameTail={active:false,starts:[],ends:[],last:null};
  Object.defineProperty(guard,'frames',{configurable:true,enumerable:descriptor.enumerable,
    get(){return descriptor.get?descriptor.get.call(guard):value},
    set(next){
      const previous=descriptor.get?descriptor.get.call(guard):value;
      if(descriptor.set)descriptor.set.call(guard,next);else value=next;
      if(probe.active&&next>previous){const now=performance.now();
        if(probe.last!==null){probe.starts.push(probe.last);probe.ends.push(now)}probe.last=now;}
    }});
})()`;

export const FRAME_TAIL_START_EXPRESSION = `(()=>{
  const p=globalThis.__gaiusFrameTail;
  if(!p)throw new Error('frame tail probe unavailable');
  p.starts=[];p.ends=[];p.last=null;p.active=true;
  performance.mark('gaius-frame-clock:'+performance.now());return true;
})()`;

export const FRAME_TAIL_STOP_EXPRESSION = `(()=>{
  const p=globalThis.__gaiusFrameTail;
  if(!p)return null;p.active=false;
  return {source:'presented-frame-guard',timeOrigin:performance.timeOrigin,starts:p.starts,ends:p.ends};
})()`;
