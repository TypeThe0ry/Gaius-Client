import assert from 'node:assert/strict';
import {makeHarness} from './quality-post-cache-smoke.mjs';

let checked=0;
for(const [width,height] of [[1280,720],[800,600],[1279,577],[64,33]]) {
 for(const inset of [0,7]) for(const bloom of [false,true]) for(const ssao of [false,true])
 for(const fxaa of [false,true]) for(const ssr of [false,true]) {
  const h=makeHarness();
  const texture=(label,format)=>h.device.createTexture({label,size:{width,height},format});
  const projection=[1.2,1.7,0.1,-0.02,-1.0001,-0.1,-1,0];
  const frame={color:texture('color','rgba8unorm'),depth:texture('depth','depth32float'),
   width,height,rectWidth:width-inset,rectHeight:height-inset,projection,zeroToOne:true};
  const settings={enabled:true,fxaa,tonemap:true,ssao,bloom,ssr,ssaoRadius:1.5,ssaoIntensity:0.8,bloomThreshold:0.7,bloomIntensity:0.35};
  assert.equal(h.frame(frame,settings),true);
  const finalPasses=h.executed.filter(p=>/^gaius post (composite(?:Ssr)?|fxaa|copy)\|/.test(p.pipeline));
  assert.equal(finalPasses.length,2,'composite and one final output pass must be encoded');
  for(const p of finalPasses){
   const hex=/^0=([a-f\d]+)(?: |$)/.exec(p.bindings)?.[1];
   assert.ok(hex,'actual submitted pass must bind its uniform bytes');
   const bytes=Buffer.from(hex,'hex');
   const expected=[1/width,1/height,(frame.rectWidth-0.5)/width,(frame.rectHeight-0.5)/height,
    frame.rectWidth,frame.rectHeight,frame.rectWidth-1,frame.rectHeight-1,...projection];
   for(let i=0;i<expected.length;i++)assert.equal(bytes.readFloatLE(i*4),Math.fround(expected[i]),
    `${p.pipeline} uniform ${i}: ${width}x${height}, inset=${inset}, bloom=${bloom}, ssao=${ssao}, fxaa=${fxaa}, ssr=${ssr}`);
   checked++;
  }
 }
}
console.log(`quality post coordinates PASS (${checked} submitted pass uniform blocks)`);
