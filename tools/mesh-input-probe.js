// Page-side probe for the mesher A/B work: wraps GaiusMeshJob.MeshTableSession.mesh and records, per
// submitted section job, FNV-1a fingerprints of the snapshot inputs (states, light, biome grid, palette,
// swamp mask) and of the kernel's output layers (vertices, translucent order), plus the status and the
// visibility bits. Load it into a capture run with MESH_INPUT_PROBE=<this file> (minecraft-263-singleplayer-cdp.mjs
// writes the records to <OUT>/mesh-inputs.json). Two sessions whose inputs fingerprint equal must have equal
// outputs; sections whose inputs differ identify a world or streaming divergence, not a mesher one.
(()=>{
const records=[];globalThis.__gaiusMeshInputProbe=records;
const hash=a=>{if(!a)return null;const b=new Uint8Array(a.buffer,a.byteOffset,a.byteLength);let h=2166136261;for(const v of b)h=Math.imul(h^v,16777619);return (h>>>0).toString(16);};
let api;
Object.defineProperty(globalThis,'GaiusMeshJob',{configurable:true,get(){return api;},set(value){
 api=value;const proto=value.MeshTableSession.prototype;const original=proto.mesh;
 proto.mesh=function(submit,input,opts){
  const r={n:records.length,time:performance.now(),section:input.section,seq:input.requestSeq,ao:input.ao,cutoutLeaves:input.cutoutLeaves,blend:input.biomeBlend,states:hash(input.states),light:hash(input.light),biome:hash(input.biomeQuarts),palette:hash(input.biomePalette),swamp:hash(input.swampMask),camera:input.camera};
  const centerLight=[],haloLight=[];for(let y=-2;y<18;y++)for(let z=-2;z<18;z++)for(let x=-2;x<18;x++)(x>=0&&x<16&&y>=0&&y<16&&z>=0&&z<16?centerLight:haloLight).push(input.light[((y+2)*20+z+2)*20+x+2]);
  r.centerLight=hash(new Uint8Array(centerLight));r.haloLight=hash(new Uint8Array(haloLight));
  let nonAir=0;for(const v of input.states)if(v)nonAir++;r.nonAir=nonAir;
  records.push(r);
  return original.call(this,submit,input,opts).then(result=>{r.status=result.statusCode;r.vis=[result.visibilityLo,result.visibilityHi];r.layers=Object.fromEntries(Object.entries(result.layers||{}).map(([k,v])=>[k,{quads:v.quadCount,vertices:hash(v.vertices),order:hash(v.order)}]));return result;},error=>{r.error=error.code||String(error);throw error;});
 };
}});
})();
