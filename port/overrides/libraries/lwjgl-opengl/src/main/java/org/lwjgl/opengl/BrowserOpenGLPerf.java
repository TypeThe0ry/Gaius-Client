package org.lwjgl.opengl;

import java.util.HashMap;
import java.util.Map;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

final class BrowserOpenGLPerf {
    private static boolean wasmCaches;
    private static final Map<Integer, Map<String, Integer>> UNIFORM_LOCATIONS = new HashMap<>();
    private static long uniformHits;
    private static long uniformMisses;
    private static long uploadCalls;
    private static long uploadBytes;
    private static long bindHits;
    private static int boundProgram = Integer.MIN_VALUE;
    private static int boundVao = Integer.MIN_VALUE;
    private static int textureUnit = Integer.MIN_VALUE;
    private static final int[][] TEXTURES = new int[32][4];

    @JSFunctor
    private interface CacheInvalidator extends JSObject {
        void invalidate(int kind, boolean enabled);
    }

    private BrowserOpenGLPerf() {
    }

    static void initialize() {
        UNIFORM_LOCATIONS.clear();
        uniformHits = 0;
        uniformMisses = 0;
        uploadCalls = 0;
        uploadBytes = 0;
        bindHits = 0;
        wasmCaches = initializeJs();
        invalidate(0, wasmCaches);
        installInvalidatorJs(BrowserOpenGLPerf::invalidate);
    }

    private static void invalidate(int kind, boolean enabled) {
        if (kind == 0) wasmCaches = enabled;
        if (kind == 0 || kind == 1 || kind == 4) boundProgram = Integer.MIN_VALUE;
        if (kind == 0 || kind == 2 || kind == 4) boundVao = Integer.MIN_VALUE;
        if (kind == 0 || kind == 3 || kind == 4) {
            textureUnit = Integer.MIN_VALUE;
            for (int[] targets : TEXTURES) java.util.Arrays.fill(targets, Integer.MIN_VALUE);
        }
        if (kind == 4) UNIFORM_LOCATIONS.clear();
    }

    static boolean skipProgram(int program) {
        if (!wasmCaches || boundProgram != program) return false;
        bindHits++;return true;
    }

    static void rememberProgram(int program) {
        if (wasmCaches) boundProgram = program;
    }

    static boolean skipVao(int array) {
        if (!wasmCaches || boundVao != array) return false;
        bindHits++;return true;
    }

    static void rememberVao(int array) {
        if (wasmCaches) boundVao = array;
    }

    static boolean skipTextureUnit(int unit) {
        if (!wasmCaches || textureUnit != unit) return false;
        bindHits++;return true;
    }

    static void rememberTextureUnit(int unit) {
        if (wasmCaches) textureUnit = unit;
    }

    private static int textureTarget(int target) {
        return switch (target) {
            case 0x0DE1 -> 0;
            case 0x806F -> 1;
            case 0x8C1A -> 2;
            case 0x8513 -> 3;
            default -> -1;
        };
    }

    static boolean skipTexture(int target, int texture) {
        int unit = textureUnit - 0x84C0;
        int slot = textureTarget(target);
        if (!wasmCaches || unit < 0 || unit >= TEXTURES.length || slot < 0
                || TEXTURES[unit][slot] != texture) return false;
        bindHits++;return true;
    }

    static void rememberTexture(int unit, int target, int texture) {
        rememberTextureUnit(unit);
        int index = unit - 0x84C0;
        int slot = textureTarget(target);
        if (wasmCaches && index >= 0 && index < TEXTURES.length) {
            // TEXTURE_BUFFER shares the physical 2D binding and suppresses the
            // unit's sampler. The next ordinary target must restore both states.
            if (target == 0x8C2A) java.util.Arrays.fill(TEXTURES[index], Integer.MIN_VALUE);
            else if (slot >= 0) TEXTURES[index][slot] = texture;
        }
    }

    static int textureUnit() {
        return textureUnit;
    }

    @JSBody(params = {"invalidator"}, script = """
            const perf=window.__gaiusGL && window.__gaiusGL.perfGl2;
            if (perf) perf.invalidate=invalidator;
            """)
    private static native void installInvalidatorJs(CacheInvalidator invalidator);

    static Integer uniformLocation(int program, String name) {
        if (!wasmCaches) return null;
        Map<String, Integer> locations = UNIFORM_LOCATIONS.get(program);
        Integer result = locations == null ? null : locations.get(name);
        if (result == null) uniformMisses++;
        else uniformHits++;
        return result;
    }

    static void rememberUniform(int program, String name, int location) {
        if (wasmCaches) {
            UNIFORM_LOCATIONS.computeIfAbsent(program, ignored -> new HashMap<>()).put(name, location);
        }
    }

    static void forgetProgram(int program) {
        UNIFORM_LOCATIONS.remove(program);
        boundProgram = Integer.MIN_VALUE;
    }

    static void noteUpload(int bytes) {
        if (wasmCaches && bytes >= 0) {
            uploadCalls++;
            uploadBytes += bytes;
            if ((uploadCalls & 255L) == 0L) publishCounters();
        }
    }

    static void publishCounters() {
        if (wasmCaches) publishCountersJs(uniformHits, uniformMisses, uploadCalls, uploadBytes, bindHits);
    }

    @JSBody(params = {"hits", "misses", "calls", "bytes", "binds"}, script = """
            const perf=window.__gaiusGL && window.__gaiusGL.perfGl2;
            if (perf) {
              perf.stats.wasmUniformHits=hits;
              perf.stats.wasmUniformMisses=misses;
              perf.stats.wasmUploadCalls=calls;
              perf.stats.wasmUploadBytes=bytes;
              perf.stats.wasmBindSkips=binds;
            }
            """)
    private static native void publishCountersJs(double hits, double misses, double calls, double bytes, double binds);

    /*
     * Installed through the Function constructor: the TeaVM JavaScript backend parses @JSBody
     * scripts with an older grammar that rejects this experiment's syntax (spread arguments,
     * for-of loops, block-scoped redeclarations), and the script only runs on the WebAssembly
     * GC client.
     */
    private static final String INSTALL_SOURCE = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            if (!gl || !state || state.perfGl2) return false;
            const params=new URLSearchParams(globalThis.location ? location.search : '');
            const on=name=>/^(1|true|on)$/i.test(params.get(name) || '');
            const flags={buffers:on('gaiusGlLargeBuffers'),glue:on('gaiusGlGlue'),
              fbo:on('gaiusGlLazyFbo'),attrib:on('gaiusGlVaoCache'),pace:on('gaiusGlGpuPace')};
            if (!Object.values(flags).some(Boolean) && !on('gaiusGlAB')) return false;
            const verify=on('gaiusGlVerify');
            const stats={rotations:0,rotationBytes:0,reuses:0,retiredBytes:0,pressureDeletes:0,
              bindSkips:0,attribSkips:0,fboSkips:0,fboCommits:0,verifyChecks:0,
              verifyMismatches:0,stateChecks:0,stateMismatches:0,unsafeWrites:0,
              pacedFrames:0,pacePolls:0,paceWaitMs:0,paceTimeouts:0,paceFlushes:0};
            const raw={};
            for (const name of ['createBuffer','deleteBuffer','bindBuffer','bufferData','bufferSubData',
                'copyBufferSubData','getBufferSubData','bindBufferRange','bindBufferBase',
                'deleteVertexArray','bindVertexArray','vertexAttribPointer','vertexAttribIPointer',
                'enableVertexAttribArray','disableVertexAttribArray','vertexAttribDivisor',
                'useProgram','activeTexture','bindTexture','deleteTexture','bindFramebuffer',
                'deleteFramebuffer','getParameter','getVertexAttrib','fenceSync','clientWaitSync','deleteSync',
                'bindTransformFeedback','deleteTransformFeedback','flush']) {
              if (typeof gl[name]==='function') raw[name]=gl[name].bind(gl);
            }
            const ARRAY=0x8892,ELEMENT=0x8893,READ=0x8F36,WRITE=0x8F37,PACK=0x88EB,
              UNPACK=0x88EC,FEEDBACK=0x8C8E,LARGE=1048576;
            const buffers=new WeakMap(),owners=new WeakMap(),bound=new Map(),indexed=new Map();
            const vaos=new Map(),retired=[],textures=new Map(),frameFences=[],feedbacks=new Map();
            let feedbackObject=null;
            let vao=null,program=null,active=0x84C0,feedbackActive=false,generation=0;
            let wantedRead=null,wantedDraw=null,actualRead=null,actualDraw=null;
            let actualCopyWrite=raw.getParameter(WRITE);
            const bindRaw=raw.bindBuffer;
            raw.bindBuffer=function(target,object) {
              if (target===WRITE) actualCopyWrite=object;
              return bindRaw(target,object);
            };
            const perf={flags:flags,stats:stats,textureDirty:new Set()};
            const invalidate=kind=>{
              if (perf.invalidate && (flags.glue || kind===0 || kind===4)) perf.invalidate(kind,flags.glue);
            };
            for (const name of ['linkProgram','deleteProgram']) {
              if (typeof gl[name]!=='function') continue;
              const call=gl[name].bind(gl);gl[name]=function() {invalidate(4);return call(...arguments);};
            }
            state.perfGl2=perf;
            (window.__gaiusGLStats || (window.__gaiusGLStats={})).perfGl2=stats;
            const owner=buffer=>owners.get(buffer) || buffer;
            const infoFor=buffer=>buffer ? buffers.get(owner(buffer)) : null;
            const physical=buffer=>{const info=infoFor(buffer);return info ? info.object : buffer;};
            const vaoState=()=>{
              let entry=vaos.get(vao);
              if (!entry) {
                entry={element:null,attribs:new Map(),enabled:new Set(),divisors:new Map(),generation:generation};
                vaos.set(vao,entry);
              }
              return entry;
            };
            program=raw.getParameter(0x8B8D);
            vao=raw.getParameter(0x85B5);
            active=raw.getParameter(0x84E0);
            wantedDraw=actualDraw=raw.getParameter(0x8CA6);
            wantedRead=actualRead=raw.getParameter(0x8CAA);
            const binding=target=>target===ELEMENT ? vaoState().element : bound.get(target) || null;
            const commitBuffer=target=>{
              if (target===WRITE && actualCopyWrite!==physical(binding(WRITE))) {
                raw.bindBuffer(WRITE,physical(binding(WRITE)));
              }
            };
            perf.uploadTarget=function(buffer) {
              if (flags.attrib) for (const target of [ARRAY,0x8A11,WRITE]) {
                if (binding(target)===owner(buffer)) return target;
              }
              return WRITE;
            };
            const mark=buffer=>{const info=infoFor(buffer);if (info) info.pending=true;};
            const feedbackBindings=()=>{
              let entries=feedbacks.get(feedbackObject);
              if (!entries) feedbacks.set(feedbackObject,entries=new Map());
              return entries;
            };
            const bytes=(data,offset,length)=>{
              if (data instanceof ArrayBuffer) return new Uint8Array(data);
              if (!ArrayBuffer.isView(data)) return null;
              const size=data.BYTES_PER_ELEMENT || 1,start=(offset || 0)*size;
              const count=length ? length*size : data.byteLength-start;
              if (start<0 || count<0 || start+count>data.byteLength) return null;
              return new Uint8Array(data.buffer,data.byteOffset+start,count);
            };
            const byteLength=(data,offset,length)=>{
              if (data instanceof ArrayBuffer) return data.byteLength;
              if (!ArrayBuffer.isView(data)) return -1;
              const size=data.BYTES_PER_ELEMENT || 1,start=(offset || 0)*size;
              const count=length ? length*size : data.byteLength-start;
              return start>=0 && count>=0 && start+count<=data.byteLength ? count : -1;
            };
            const restoreCopy=()=>{
              raw.bindBuffer(READ,physical(binding(READ)));
              raw.bindBuffer(WRITE,physical(binding(WRITE)));
            };
            const checkBytes=info=>{
              if (!verify || !info || !info.mirror) return;
              const actual=new Uint8Array(info.size);
              raw.bindBuffer(READ,info.object);raw.getBufferSubData(READ,0,actual);restoreCopy();
              stats.verifyChecks++;
              for (let index=0;index<actual.length;index++) if (actual[index]!==info.mirror[index]) {
                stats.verifyMismatches++;throw new Error('Large buffer content mismatch at '+index);
              }
            };
            const drop=entry=>{
              if (entry.fence) raw.deleteSync(entry.fence);
              raw.deleteBuffer(entry.object);stats.retiredBytes-=entry.size;
            };
            const replay=()=>{
              const entry=vaoState();
              if (entry.generation===generation) return;
              raw.bindBuffer(ELEMENT,physical(entry.element));
              for (const attribute of entry.attribs.values()) {
                const object=physical(attribute.buffer);
                if (object===attribute.object) continue;
                raw.bindBuffer(ARRAY,object);
                raw[attribute.integer ? 'vertexAttribIPointer' : 'vertexAttribPointer'](...attribute.args);
                attribute.object=object;
              }
              raw.bindBuffer(ARRAY,physical(binding(ARRAY)));entry.generation=generation;
            };
            const rotate=info=>{
              if (!flags.buffers || info.size<LARGE || !info.pending) return;
              if (feedbackActive) {stats.unsafeWrites++;return;}
              if (state.bufferLayer && state.bufferLayer.infos.has(info.object)) return;
              let object=null;
              for (let index=0;index<retired.length;index++) {
                const entry=retired[index];
                if (buffers.has(entry.object) || entry.size!==info.size
                    || entry.element!==info.element || !entry.fence) continue;
                const status=raw.clientWaitSync(entry.fence,0,0);
                if (status!==0x911A && status!==0x911C) continue;
                retired.splice(index,1);raw.deleteSync(entry.fence);
                stats.retiredBytes-=entry.size;object=entry.object;stats.reuses++;break;
              }
              if (!object) {
                object=raw.createBuffer();
                if (!object) {stats.unsafeWrites++;return;}
                if (info.element) raw.bindBuffer(ELEMENT,object);
                raw.bindBuffer(WRITE,object);raw.bufferData(WRITE,info.size,info.usage);
              }
              raw.bindBuffer(READ,info.object);raw.bindBuffer(WRITE,object);
              raw.copyBufferSubData(READ,WRITE,0,0,info.size);
              const fence=raw.fenceSync(0x9117,0);
              retired.push({object:info.object,size:info.size,element:info.element,fence:fence});
              stats.retiredBytes+=info.size;
              info.object=object;owners.set(object,info.logical);info.pending=false;generation++;
              for (const [target,buffer] of bound) if (owner(buffer)===info.logical) raw.bindBuffer(target,object);
              for (const entry of indexed.values()) if (owner(entry.buffer)===info.logical) {
                if (entry.range) raw.bindBufferRange(entry.target,entry.slot,object,entry.offset,entry.size);
                else raw.bindBufferBase(entry.target,entry.slot,object);
                raw.bindBuffer(entry.target,physical(binding(entry.target)));
              }
              if (raw.bindTransformFeedback && feedbacks.size) {
                for (const [feedback,entries] of feedbacks) {
                  for (const entry of entries.values()) if (owner(entry.buffer)===info.logical) {
                    raw.bindTransformFeedback(0x8E22,feedback);
                    if (entry.range) raw.bindBufferRange(FEEDBACK,entry.slot,object,entry.offset,entry.size);
                    else raw.bindBufferBase(FEEDBACK,entry.slot,object);
                  }
                }
                raw.bindTransformFeedback(0x8E22,feedbackObject);
                raw.bindBuffer(FEEDBACK,physical(binding(FEEDBACK)));
              }
              replay();restoreCopy();stats.rotations++;stats.rotationBytes+=info.size;
              while (retired.length>16 || stats.retiredBytes>67108864) {
                drop(retired.shift());stats.pressureDeletes++;
              }
            };
            gl.createBuffer=function() {
              const buffer=raw.createBuffer();
              if (buffer) buffers.set(buffer,{logical:buffer,object:buffer,size:0,usage:0x88E8,
                element:false,pending:false,mirror:null});
              return buffer;
            };
            gl.bindBuffer=function(target,buffer) {
              buffer=buffer ? owner(buffer) : null;
              if (target===ELEMENT) {
                vaoState().element=buffer;
                const info=infoFor(buffer);if (info) info.element=true;
              } else bound.set(target,buffer);
              if (flags.attrib && target===WRITE) {stats.bindSkips++;return;}
              return raw.bindBuffer(target,physical(buffer));
            };
            gl.bufferData=function(target,data,usage,offset,length) {
              commitBuffer(target);
              const info=infoFor(binding(target)),size=typeof data==='number' ? data : byteLength(data,offset,length);
              const result=raw.bufferData(...arguments);
              if (info) {
                info.size=Math.max(0,size);
                info.usage=usage;info.pending=false;
                info.mirror=verify && info.size>=LARGE ? new Uint8Array(info.size) : null;
                if (info.mirror && typeof data!=='number') {
                  const view=bytes(data,offset,length);if (view) info.mirror.set(view);
                }
              }
              return result;
            };
            gl.bufferSubData=function(target,offset,data,sourceOffset,length) {
              commitBuffer(target);
              const info=infoFor(binding(target)),size=byteLength(data,sourceOffset,length);
              if (info && size>=0 && offset>=0 && offset+size<=info.size) {
                rotate(info);if (info.mirror) info.mirror.set(bytes(data,sourceOffset,length),offset);
              }
              const result=raw.bufferSubData(...arguments);
              if (info && info.size>=LARGE) checkBytes(info);
              return result;
            };
            for (const name of ['bindBufferRange','bindBufferBase']) gl[name]=function(target,slot,buffer,offset,size) {
              buffer=buffer ? owner(buffer) : null;
              const entries=target===FEEDBACK ? feedbackBindings() : indexed;
              entries.set(target+':'+slot,{target:target,slot:slot,buffer:buffer,
                offset:offset,size:size,range:name==='bindBufferRange'});
              bound.set(target,buffer);
              return name==='bindBufferRange' ? raw[name](target,slot,physical(buffer),offset,size)
                : raw[name](target,slot,physical(buffer));
            };
            gl.copyBufferSubData=function(readTarget,writeTarget,readOffset,writeOffset,size) {
              commitBuffer(readTarget);commitBuffer(writeTarget);
              const source=infoFor(binding(readTarget)),destination=infoFor(binding(writeTarget));
              if (destination && readOffset>=0 && writeOffset>=0
                  && size>=0 && source && readOffset+size<=source.size && writeOffset+size<=destination.size) {
                rotate(destination);
                if (destination.mirror) {
                  if (source===destination) destination.mirror.copyWithin(writeOffset,readOffset,readOffset+size);
                  else if (source.mirror) destination.mirror.set(source.mirror.subarray(readOffset,readOffset+size),writeOffset);
                  else destination.mirror=null;
                }
              }
              const result=raw.copyBufferSubData(...arguments);
              mark(binding(readTarget));checkBytes(destination);return result;
            };
            if (raw.bindTransformFeedback) gl.bindTransformFeedback=function(target,object) {
              if (target===0x8E22 && !feedbackActive) feedbackObject=object;
              return raw.bindTransformFeedback(target,object);
            };
            if (raw.deleteTransformFeedback) gl.deleteTransformFeedback=function(object) {
              feedbacks.delete(object);if (feedbackObject===object) feedbackObject=null;
              return raw.deleteTransformFeedback(object);
            };
            gl.deleteBuffer=function(buffer) {
              const logical=owner(buffer),info=infoFor(buffer);
              for (let index=retired.length-1;index>=0;index--) if (owner(retired[index].object)===logical
                  || retired[index].object===logical) drop(retired.splice(index,1)[0]);
              for (const [target,value] of bound) if (value===logical) bound.set(target,null);
              for (const entry of indexed.values()) if (entry.buffer===logical) entry.buffer=null;
              for (const entries of feedbacks.values()) for (const entry of entries.values()) {
                if (entry.buffer===logical) entry.buffer=null;
              }
              for (const entry of vaos.values()) {
                if (entry.element===logical) entry.element=null;
                for (const [index,attribute] of entry.attribs) if (attribute.buffer===logical) entry.attribs.delete(index);
              }
              if (info) {raw.deleteBuffer(info.object);if (info.object!==logical) raw.deleteBuffer(logical);buffers.delete(logical);}
              else raw.deleteBuffer(buffer);
            };
            gl.bindVertexArray=function(object) {
              if (flags.glue && state.vaos) {
                perf.vaoDirty=object!==((state.currentVaoId|0)===0 ? null : state.vaos.get(state.currentVaoId));
                if (perf.vaoDirty) invalidate(2);
              } else invalidate(2);
              if (flags.glue && vao===object) {stats.bindSkips++;return;}
              vao=object;raw.bindVertexArray(object);replay();
            };
            gl.deleteVertexArray=function(object) {
              invalidate(2);
              vaos.delete(object);if (vao===object) vao=null;return raw.deleteVertexArray(object);
            };
            for (const name of ['vertexAttribPointer','vertexAttribIPointer']) gl[name]=function(index) {
              const entry=vaoState(),previous=entry.attribs.get(index),buffer=binding(ARRAY);
              const integer=name==='vertexAttribIPointer',object=physical(buffer);
              let same=previous && previous.args.length===arguments.length;
              if (same) for (let position=0;position<arguments.length;position++) {
                if (arguments[position]!==previous.args[position]) {same=false;break;}
              }
              if (flags.attrib && previous && previous.buffer===buffer && previous.object===object
                  && previous.integer===integer && same) {
                stats.attribSkips++;return;
              }
              const args=Array.from(arguments);
              const result=raw[name](...args);
              entry.attribs.set(index,{buffer:buffer,object:object,args:args,integer:integer});return result;
            };
            for (const name of ['enableVertexAttribArray','disableVertexAttribArray']) gl[name]=function(index) {
              const enabled=vaoState().enabled,next=name==='enableVertexAttribArray';
              if (flags.attrib && enabled.has(index)===next) {stats.attribSkips++;return;}
              if (next) enabled.add(index);else enabled.delete(index);return raw[name](index);
            };
            gl.vertexAttribDivisor=function(index,divisor) {
              const cache=vaoState().divisors;
              if (flags.attrib && (cache.get(index) || 0)===divisor) {stats.attribSkips++;return;}
              cache.set(index,divisor);return raw.vertexAttribDivisor(index,divisor);
            };
            gl.useProgram=function(object) {
              if (flags.glue && state.programs) {
                perf.programDirty=object!==((state.currentProgram|0)===0 ? null : state.programs.get(state.currentProgram));
                if (perf.programDirty) invalidate(1);
              } else invalidate(1);
              if (flags.glue && program===object) {stats.bindSkips++;return;}
              program=object;return raw.useProgram(object);
            };
            gl.activeTexture=function(unit) {
              if (flags.glue) {
                perf.unitDirty=unit!==0x84C0+(state.activeTextureUnit|0);
                if (perf.unitDirty) invalidate(3);
              }
              if (flags.glue && active===unit) {stats.bindSkips++;return;}
              active=unit;return raw.activeTexture(unit);
            };
            gl.bindTexture=function(target,object) {
              if (flags.glue && state.textureBindings && state.textures) {
                const webKey=(active-0x84C0)*65536+(target&65535);
                const id=state.textureBindings.get(webKey)|0;
                if (object!==(id===0 ? null : state.textures.get(id))) {
                  perf.textureDirty.add(webKey);invalidate(3);
                }
                else perf.textureDirty.delete(webKey);
              } else invalidate(3);
              const key=active+':'+target;
              if (flags.glue && textures.has(key) && textures.get(key)===object) {stats.bindSkips++;return;}
              textures.set(key,object);return raw.bindTexture(target,object);
            };
            gl.deleteTexture=function(object) {
              invalidate(3);
              for (const [key,value] of textures) if (value===object) textures.delete(key);
              return raw.deleteTexture(object);
            };
            const commit=(read,draw)=>{
              if (read && actualRead!==wantedRead) {raw.bindFramebuffer(0x8CA8,wantedRead);actualRead=wantedRead;stats.fboCommits++;}
              if (draw && actualDraw!==wantedDraw) {raw.bindFramebuffer(0x8CA9,wantedDraw);actualDraw=wantedDraw;stats.fboCommits++;}
            };
            gl.bindFramebuffer=function(target,object) {
              if (target===0x8D40 || target===0x8CA8) wantedRead=object;
              if (target===0x8D40 || target===0x8CA9) wantedDraw=object;
              if (!flags.fbo || (target!==0x8D40 && target!==0x8CA8 && target!==0x8CA9)) {
                const result=raw.bindFramebuffer(target,object);
                if (target===0x8D40 || target===0x8CA8) actualRead=object;
                if (target===0x8D40 || target===0x8CA9) actualDraw=object;
                return result;
              }
              stats.fboSkips++;
            };
            gl.deleteFramebuffer=function(object) {
              if (wantedRead===object) wantedRead=null;if (wantedDraw===object) wantedDraw=null;
              const result=raw.deleteFramebuffer(object);
              if (actualRead===object) actualRead=null;if (actualDraw===object) actualDraw=null;
              return result;
            };
            gl.getParameter=function(parameter) {
              if (flags.attrib && parameter===WRITE) return binding(WRITE);
              if (flags.fbo && parameter===0x8CA6) return wantedDraw;
              if (flags.fbo && parameter===0x8CAA) return wantedRead;
              const value=raw.getParameter(parameter);return owners.has(value) ? owner(value) : value;
            };
            if (raw.getVertexAttrib) gl.getVertexAttrib=function(index,parameter) {
              const value=raw.getVertexAttrib(index,parameter);return owners.has(value) ? owner(value) : value;
            };
            if (typeof gl.getIndexedParameter==='function') {
              const query=gl.getIndexedParameter.bind(gl);
              gl.getIndexedParameter=function() {
                const value=query(...arguments);return owners.has(value) ? owner(value) : value;
              };
            }
            if (typeof gl.isBuffer==='function') {
              const query=gl.isBuffer.bind(gl);gl.isBuffer=function(buffer) {return query(physical(buffer));};
            }
            for (const name of ['getBufferSubData','getBufferParameter']) {
              if (typeof gl[name]!=='function') continue;
              const call=gl[name].bind(gl);
              gl[name]=function(target) {commitBuffer(target);return call(...arguments);};
            }
            const barrier=(name,read,draw,after)=>{
              if (typeof gl[name]!=='function') return;
              const call=gl[name].bind(gl);
              gl[name]=function() {commit(read,draw);const result=call(...arguments);if (after) after();return result;};
            };
            for (const name of ['clear','clearBufferfv','clearBufferiv','clearBufferuiv','clearBufferfi','drawBuffers']) barrier(name,false,true);
            for (const name of ['readBuffer','copyTexImage2D','copyTexSubImage2D','copyTexSubImage3D']) barrier(name,true,false);
            barrier('blitFramebuffer',true,true);
            for (const name of ['framebufferTexture2D','framebufferTextureLayer','framebufferRenderbuffer',
                'checkFramebufferStatus','getFramebufferAttachmentParameter','invalidateFramebuffer',
                'invalidateSubFramebuffer']) barrier(name,true,true);
            barrier('readPixels',true,false,()=>{const info=infoFor(binding(PACK));if (info) {info.pending=true;info.mirror=null;}});
            for (const name of ['texImage2D','texImage3D','texSubImage2D','texSubImage3D',
                'compressedTexImage2D','compressedTexImage3D','compressedTexSubImage2D',
                'compressedTexSubImage3D']) barrier(name,false,false,()=>mark(binding(UNPACK)));
            barrier('beginTransformFeedback',false,false,()=>{feedbackActive=true;});
            barrier('endTransformFeedback',false,false,()=>{feedbackActive=false;});
            const checkState=()=>{
              if (!verify) return;
              stats.stateChecks++;
              if (raw.getParameter(0x8CA6)!==wantedDraw || raw.getParameter(0x8CAA)!==wantedRead
                  || raw.getParameter(0x8B8D)!==program || raw.getParameter(0x85B5)!==vao) {
                stats.stateMismatches++;throw new Error('WebGL glue state mismatch');
              }
            };
            for (const name of ['drawArrays','drawElements','drawArraysInstanced','drawElementsInstanced','drawRangeElements']) {
              if (typeof gl[name]!=='function') continue;
              const draw=gl[name].bind(gl);
              gl[name]=function() {
                commit(true,true);replay();checkState();const result=draw(...arguments);
                const entry=vaoState();mark(entry.element);
                for (const [index,attribute] of entry.attribs) if (entry.enabled.has(index)) mark(attribute.buffer);
                for (const binding of indexed.values()) {
                  mark(binding.buffer);
                }
                if (feedbackActive) for (const binding of feedbackBindings().values()) {
                  mark(binding.buffer);const info=infoFor(binding.buffer);if (info) info.mirror=null;
                }
                return result;
              };
            }
            perf.deferPresent=function(done) {
              if (!flags.pace || feedbackActive || !raw.fenceSync || !raw.clientWaitSync) return false;
              const fence=raw.fenceSync(0x9117,0);
              if (!fence) return false;
              frameFences.push(fence);
              if (frameFences.length>=2 && raw.flush) {raw.flush();stats.paceFlushes++;}
              const started=performance.now();
              const poll=()=>{
                while (frameFences.length) {
                  const status=raw.clientWaitSync(frameFences[0],0,0);
                  if (status!==0x911A && status!==0x911C && status!==0x911D) break;
                  raw.deleteSync(frameFences.shift());
                }
                if (flags.pace && frameFences.length>=2 && performance.now()-started<250) {
                  stats.pacePolls++;setTimeout(poll,1);return;
                }
                if (frameFences.length>=2) {
                  stats.paceTimeouts++;while (frameFences.length) raw.deleteSync(frameFences.shift());
                }
                stats.paceWaitMs+=performance.now()-started;done();
              };
              stats.pacedFrames++;poll();return true;
            };
            perf.setFlags=function(next) {
              commit(true,true);commitBuffer(WRITE);Object.assign(flags,next);
              invalidate(0);
              if (!flags.pace) while (frameFences.length) raw.deleteSync(frameFences.shift());
            };
            perf.check=function() {commit(true,true);checkState();};
            if (gl.canvas && gl.canvas.addEventListener) gl.canvas.addEventListener('webglcontextlost',()=>{
              invalidate(4);
              bound.clear();indexed.clear();vaos.clear();textures.clear();feedbacks.clear();retired.length=0;
              perf.textureDirty.clear();perf.programDirty=perf.vaoDirty=perf.unitDirty=true;
              vao=program=wantedRead=wantedDraw=actualRead=actualDraw=actualCopyWrite=null;active=0x84C0;
              frameFences.length=0;feedbackObject=null;feedbackActive=false;generation=0;
              stats.retiredBytes=0;
            });
            return flags.glue;
            """;

    private static boolean initializeJs() {
        return installJs(INSTALL_SOURCE);
    }

    @JSBody(params = "source", script = "return !!(new Function(source))();")
    private static native boolean installJs(String source);
}
