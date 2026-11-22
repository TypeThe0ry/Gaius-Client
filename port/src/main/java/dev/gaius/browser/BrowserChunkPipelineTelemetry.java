package dev.gaius.browser;

import org.teavm.jso.JSBody;

/**
 * Opt-in causal timing for a mesh section. The Java fast path is a single boolean check; the
 * bounded maps, rings and event records exist only after the URL/global switch is enabled.
 */
public final class BrowserChunkPipelineTelemetry {
    private static final boolean ENABLED = BrowserBuildFlags.telemetry() && enabledJs();

    static {
        if (ENABLED) {
            initializeEnabledStateJs();
            setEpochsJs(1, 1);
        }
    }

    private BrowserChunkPipelineTelemetry() {
    }

    public static void packetHandlerEnter(int chunkX, int chunkZ) {
        if (enabled()) {
            packetHandlerEnterJs(chunkX, chunkZ, nowMillis());
        }
    }

    public static void registerDrawSection(int uniformIndex, int sectionX, int sectionY, int sectionZ) {
        if (enabled()) {
            registerDrawSectionJs(uniformIndex, sectionX, sectionY, sectionZ);
        }
    }

    public static void firstDraw(int uniformIndex, int indexCount) {
        if (enabled() && indexCount > 0) {
            firstDrawJs(uniformIndex, indexCount, nowMillis());
        }
    }

    public static void snapshotStart(int ticket, int sectionX, int sectionY, int sectionZ) {
        if (enabled()) {
            snapshotStartJs(ticket, sectionX, sectionY, sectionZ, nowMillis());
        }
    }

    public static void snapshotEnd(int ticket, boolean succeeded) {
        if (enabled()) {
            snapshotEndJs(ticket, succeeded, nowMillis());
        }
    }

    public static void workerSubmit(int ticket, int requestSeq) {
        if (enabled()) {
            workerSubmitJs(ticket, requestSeq, nowMillis());
        }
    }

    public static void workerResult(int ticket, int status) {
        if (enabled()) {
            workerResultJs(ticket, status, nowMillis());
        }
    }

    public static void installQueueEnter(int ticket, long sectionNode, int requestSeq, int bytes) {
        if (enabled() && ticket != 0) {
            installQueueEnterJs(ticket, sectionNode, requestSeq, bytes, nowMillis());
        }
    }

    public static void installComplete(int ticket, boolean meshInstalled) {
        if (enabled() && ticket != 0) {
            installCompleteJs(ticket, meshInstalled, nowMillis());
        }
    }

    public static void installDuration(int ticket, long durationNanos) {
        if (enabled() && ticket != 0) {
            installDurationJs(ticket, Math.max(0L, durationNanos) / 1_000_000.0);
        }
    }

    public static void bridgeSubmitDuration(int ticket, long durationNanos) {
        if (enabled() && ticket != 0) {
            bridgeSubmitDurationJs(ticket, Math.max(0L, durationNanos) / 1_000_000.0);
        }
    }

    public static void discard(int ticket, int reason) {
        if (enabled() && ticket != 0) {
            discardJs(ticket, reason, nowMillis());
        }
    }

    public static void discard(int ticket, String reason) {
        if (enabled() && ticket != 0) {
            discardTextJs(ticket, reason, nowMillis());
        }
    }

    public static void schedulerSnapshot(int compileBacklog, int uploadBacklog, int queuedWork) {
        if (enabled()) {
            schedulerSnapshotJs(compileBacklog, uploadBacklog, queuedWork, nowMillis());
        }
    }

    static boolean enabled() {
        return ENABLED;
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    /** Publishes the Java-side render epochs only when causal telemetry is enabled. */
    public static void setEpochs(int levelEpoch, int resourceEpoch) {
        if (ENABLED) {
            setEpochsJs(levelEpoch, resourceEpoch);
        }
    }

    @JSBody(script = "return globalThis.__gaiusChunkCausalTelemetryEnabled === true;")
    private static native boolean enabledJs();

    @JSBody(params = {"levelEpoch", "resourceEpoch"}, script = """
            const root=globalThis.__gaiusChunkPipelineTelemetry;
            const causal=root&&root.causal;
            if(causal){
              const nextLevel=Number(levelEpoch)|0,nextResource=Number(resourceEpoch)|0;
              if(causal.levelEpoch!==nextLevel||causal.resourceEpoch!==nextResource){
                causal.latestBySection.clear();
                causal.uniforms.clear();
              }
              causal.levelEpoch=nextLevel;causal.resourceEpoch=nextResource;
            }
            """)
    private static native void setEpochsJs(int levelEpoch, int resourceEpoch);

    @JSBody(script = """
            const root=globalThis.__gaiusChunkPipelineTelemetry||
              (globalThis.__gaiusChunkPipelineTelemetry={});
            const causal=root.causal||(root.causal={schemaVersion:'gaius.chunk-causal.v1',enabled:true,
              sectionCapacity:32768,handlerCapacity:8192,schedulerCapacity:512,
              handlerTimestampSource:'ClientPacketListener.handleLevelChunkWithLight entry after decode',
              sections:[],handlerEntries:[],schedulerSamples:[],droppedSections:0,droppedHandlerEntries:0,
              handlerEntryCount:0,unmappedDraws:0,earlyDraws:0,repeatDraws:0,syntheticDraws:0,
              uniforms:new Map(),byTicket:new Map(),latestBySection:new Map(),handlerByChunk:new Map(),
              levelEpoch:1,resourceEpoch:1,nextVisualTicket:-1});
            if(!causal.handlerByChunk)causal.handlerByChunk=new Map();
            if(!causal.uniforms)causal.uniforms=new Map();
            if(!causal.byTicket)causal.byTicket=new Map();
            if(!causal.latestBySection)causal.latestBySection=new Map();
            if(!Array.isArray(causal.sections))causal.sections=[];
            if(!Array.isArray(causal.handlerEntries))causal.handlerEntries=[];
            if(!Array.isArray(causal.schedulerSamples))causal.schedulerSamples=[];
            if(!causal.mark)causal.mark=function(s,name,at){const events=s.events||(s.events=[]);if(events.length<24)events.push({name:name,atMillis:Number(at)});};
            if(!causal.add)causal.add=function(s){
              if(causal.sections.length>=causal.sectionCapacity){const removed=causal.sections.shift();
                if(removed&&removed.ticket!=null)causal.byTicket.delete(removed.ticket);
                if(removed&&removed.sectionKey!=null&&causal.latestBySection.get(removed.sectionKey)===removed)causal.latestBySection.delete(removed.sectionKey);
                causal.droppedSections=(Number(causal.droppedSections)||0)+1;}
              causal.sections.push(s);causal.byTicket.set(s.ticket,s);causal.latestBySection.set(s.sectionKey,s);};
            """)
    private static native void initializeEnabledStateJs();

    @JSBody(script = "return typeof performance !== 'undefined' ? performance.timeOrigin + performance.now() : Date.now();")
    private static native double nowMillis();

    @JSBody(params = {"chunkX", "chunkZ", "atMillis"}, script = """
            const root=globalThis.__gaiusChunkPipelineTelemetry;
            const causal=root&&root.causal;
            if(!causal)return;
            const chunkXValue=Number(chunkX)|0,chunkZValue=Number(chunkZ)|0,timestamp=Number(atMillis);
            causal.handlerEntryCount=(Number(causal.handlerEntryCount)||0)+1;
            if(causal.handlerEntries.length>=causal.handlerCapacity){
              causal.handlerEntries.shift();causal.droppedHandlerEntries=(Number(causal.droppedHandlerEntries)||0)+1;
            }
            causal.handlerEntries.push({chunkX:chunkXValue,chunkZ:chunkZValue,atMillis:Number.isFinite(timestamp)?timestamp:Date.now()});
            const key=chunkXValue+','+chunkZValue+','+(Number(causal.levelEpoch)||0)+','+(Number(causal.resourceEpoch)||0);
            if(causal.handlerByChunk.has(key))causal.handlerByChunk.delete(key);
            causal.handlerByChunk.set(key,Number.isFinite(timestamp)?timestamp:Date.now());
            while(causal.handlerByChunk.size>causal.handlerCapacity){
              const oldest=causal.handlerByChunk.keys().next();
              if(oldest.done)break;
              causal.handlerByChunk.delete(oldest.value);
            }
            """)
    private static native void packetHandlerEnterJs(int chunkX, int chunkZ, double atMillis);

    @JSBody(params = {"uniformIndex", "sectionX", "sectionY", "sectionZ"}, script = """
            const root=globalThis.__gaiusChunkPipelineTelemetry;
            const causal=root&&root.causal;
            if(!causal)return;
            const sectionXValue=Number(sectionX)|0,sectionYValue=Number(sectionY)|0,sectionZValue=Number(sectionZ)|0;
            const sectionKey=(Number(causal.levelEpoch)||0)+','+(Number(causal.resourceEpoch)||0)+','+sectionXValue+','+sectionYValue+','+sectionZValue;
            causal.uniforms.set(Number(uniformIndex)|0,{x:sectionXValue,y:sectionYValue,z:sectionZValue,key:sectionKey});
            """)
    private static native void registerDrawSectionJs(int uniformIndex, int sectionX, int sectionY, int sectionZ);

    @JSBody(params = {"uniformIndex", "indexCount", "atMillis"}, script = """
            const root=globalThis.__gaiusChunkPipelineTelemetry;
            const causal=root&&root.causal;
            if(!causal)return;
            const position=causal.uniforms.get(Number(uniformIndex)|0);
            if(!position){causal.unmappedDraws=(Number(causal.unmappedDraws)||0)+1;return;}
            let section=causal.latestBySection.get(position.key);
            const timestamp=Number(atMillis);
            if(section&&section.firstDrawAt!=null){section.repeatDraws=(Number(section.repeatDraws)||0)+1;return;}
            if(section){if(section.firstVisibleDrawAt==null)section.firstVisibleDrawAt=timestamp;section.visible=true;}
            if(section&&section.discardAt==null&&section.installCompleteAt==null){
              section.prematureDraws=(Number(section.prematureDraws)||0)+1;causal.earlyDraws=(Number(causal.earlyDraws)||0)+1;return;
            }
            if(section&&section.discardAt==null&&section.installSucceeded===true&&section.meshPublishedAt==null){
              section.prematureDraws=(Number(section.prematureDraws)||0)+1;causal.earlyDraws=(Number(causal.earlyDraws)||0)+1;return;
            }
            if(section&&section.discardAt!=null&&section.discardReason!=='snapshot-fallback'){
              section.prematureDraws=(Number(section.prematureDraws)||0)+1;return;
            }
            if(!section||section.discardAt!=null){
            const handlerAt=causal.handlerByChunk.get(position.x+','+position.z+','+(Number(causal.levelEpoch)||0)+','+(Number(causal.resourceEpoch)||0));
              const packetHandlerAt=handlerAt===undefined?null:handlerAt;
              section={ticket:causal.nextVisualTicket--,causalTicket:null,chunkX:position.x,sectionY:position.y,chunkZ:position.z,
                chunkKey:position.x+','+position.z,sectionKey:position.key,levelEpoch:Number(causal.levelEpoch)||0,resourceEpoch:Number(causal.resourceEpoch)||0,requestSeq:null,packetHandlerAt:packetHandlerAt,
                snapshotStartAt:null,snapshotEndAt:null,workerSubmitAt:null,workerResultAt:null,workerStatus:null,
                installQueueAt:null,installCompleteAt:null,installSucceeded:false,gpuUploadProxyAt:null,firstDrawAt:null,
                discardAt:null,discardReason:null,source:'visible-draw',events:[]};
              causal.add(section);causal.syntheticDraws=(Number(causal.syntheticDraws)||0)+1;
            }
            section.indexCount=Math.max(Number(section.indexCount)||0,Number(indexCount)||0);
            section.gpuUploadProxyAt=timestamp;section.firstDrawAt=timestamp;section.closedAt=timestamp;causal.mark(section,'gpuUploadProxy',timestamp);causal.mark(section,'firstDraw',timestamp);
            """)
    private static native void firstDrawJs(int uniformIndex, int indexCount, double atMillis);

    @JSBody(params = {"ticket", "sectionX", "sectionY", "sectionZ", "atMillis"}, script = """
            const root=globalThis.__gaiusChunkPipelineTelemetry;
            const causal=root&&root.causal;
            if(!causal)return;
            const sectionXValue=Number(sectionX)|0,sectionYValue=Number(sectionY)|0,sectionZValue=Number(sectionZ)|0,ticketValue=Number(ticket)|0;
            const levelEpoch=Number(causal.levelEpoch)||0,resourceEpoch=Number(causal.resourceEpoch)||0;
            const sectionKey=levelEpoch+','+resourceEpoch+','+sectionXValue+','+sectionYValue+','+sectionZValue;
            const handlerAt=causal.handlerByChunk.get(sectionXValue+','+sectionZValue+','+(Number(causal.levelEpoch)||0)+','+(Number(causal.resourceEpoch)||0));
            const packetHandlerAt=handlerAt===undefined?null:handlerAt;
            const section={ticket:ticketValue,causalTicket:ticketValue,chunkX:sectionXValue,sectionY:sectionYValue,chunkZ:sectionZValue,chunkKey:sectionXValue+','+sectionZValue,sectionKey:sectionKey,levelEpoch:levelEpoch,resourceEpoch:resourceEpoch,
              requestSeq:null,packetHandlerAt:packetHandlerAt,snapshotStartAt:Number(atMillis),snapshotEndAt:null,
              workerSubmitAt:null,workerResultAt:null,workerStatus:null,installQueueAt:null,installCompleteAt:null,
              installSucceeded:false,gpuUploadProxyAt:null,firstDrawAt:null,discardAt:null,discardReason:null,events:[]};
            causal.add(section);causal.mark(section,'snapshotStart',atMillis);
            """)
    private static native void snapshotStartJs(int ticket, int sectionX, int sectionY, int sectionZ, double atMillis);

    @JSBody(params = {"ticket", "succeeded", "atMillis"}, script = """
            const root=globalThis.__gaiusChunkPipelineTelemetry;
            const causal=root&&root.causal;
            const section=causal&&causal.byTicket?causal.byTicket.get(Number(ticket)|0):null;if(!section)return;
            section.snapshotEndAt=Number(atMillis);causal.mark(section,'snapshotEnd',atMillis);
            causal.snapshotCompleteCount=(Number(causal.snapshotCompleteCount)||0)+1;
            if(!succeeded){section.discardAt=section.snapshotEndAt;section.discardReason='snapshot-fallback';causal.mark(section,'discard',atMillis);}
            """)
    private static native void snapshotEndJs(int ticket, boolean succeeded, double atMillis);

    @JSBody(params = {"ticket", "requestSeq", "atMillis"}, script = """
            const root=globalThis.__gaiusChunkPipelineTelemetry;
            const causal=root&&root.causal;
            const section=causal&&causal.byTicket?causal.byTicket.get(Number(ticket)|0):null;if(!section)return;
            section.requestSeq=Number(requestSeq)|0;section.workerSubmitAt=Number(atMillis);causal.mark(section,'workerSubmit',atMillis);
            """)
    private static native void workerSubmitJs(int ticket, int requestSeq, double atMillis);

    @JSBody(params = {"ticket", "status", "atMillis"}, script = """
            const root=globalThis.__gaiusChunkPipelineTelemetry;
            const causal=root&&root.causal;
            const section=causal&&causal.byTicket?causal.byTicket.get(Number(ticket)|0):null;if(!section)return;
            section.workerResultAt=Number(atMillis);section.workerStatus=Number(status)|0;causal.mark(section,'workerResult',atMillis);
            """)
    private static native void workerResultJs(int ticket, int status, double atMillis);

    @JSBody(params = {"ticket", "sectionNode", "requestSeq", "bytes", "atMillis"}, script = """
            const root=globalThis.__gaiusChunkPipelineTelemetry;
            const causal=root&&root.causal;
            const section=causal&&causal.byTicket?causal.byTicket.get(Number(ticket)|0):null;if(!section)return;
            section.sectionNode=String(sectionNode);section.requestSeq=Number(requestSeq)|0;section.bytes=Math.max(0,Number(bytes)||0);
            section.installQueueAt=Number(atMillis);causal.mark(section,'installQueue',atMillis);
            """)
    private static native void installQueueEnterJs(int ticket, long sectionNode, int requestSeq, int bytes, double atMillis);

    @JSBody(params = {"ticket", "succeeded", "atMillis"}, script = """
            const root=globalThis.__gaiusChunkPipelineTelemetry;
            const causal=root&&root.causal;
            const section=causal&&causal.byTicket?causal.byTicket.get(Number(ticket)|0):null;if(!section)return;
            section.installCompleteAt=Number(atMillis);section.installSucceeded=succeeded===true;causal.mark(section,'installComplete',atMillis);
            causal.installCompleteCount=(Number(causal.installCompleteCount)||0)+1;
            if(!section.installSucceeded){section.discardAt=section.installCompleteAt;section.discardReason='install-failed';causal.mark(section,'discard',atMillis);}
            """)
    private static native void installCompleteJs(int ticket, boolean succeeded, double atMillis);

    @JSBody(params = {"ticket", "durationMillis"}, script = """
            const causal=globalThis.__gaiusChunkPipelineTelemetry&&globalThis.__gaiusChunkPipelineTelemetry.causal;
            const section=causal&&causal.byTicket?causal.byTicket.get(Number(ticket)|0):null;
            if(section)section.installDurationMs=Number(durationMillis)||0;
            """)
    private static native void installDurationJs(int ticket, double durationMillis);

    @JSBody(params = {"ticket", "durationMillis"}, script = """
            const causal=globalThis.__gaiusChunkPipelineTelemetry&&globalThis.__gaiusChunkPipelineTelemetry.causal;
            const section=causal&&causal.byTicket?causal.byTicket.get(Number(ticket)|0):null;
            if(section)section.bridgeSubmitMs=Number(durationMillis)||0;
            """)
    private static native void bridgeSubmitDurationJs(int ticket, double durationMillis);

    @JSBody(params = {"ticket", "atMillis"}, script = """
            const root=globalThis.__gaiusChunkPipelineTelemetry;
            const causal=root&&root.causal;
            const section=causal&&causal.byTicket?causal.byTicket.get(Number(ticket)|0):null;if(!section)return;
            section.meshPublishedAt=Number(atMillis);causal.mark(section,'meshPublished',atMillis);
            """)
    private static native void meshPublishedJs(int ticket, double atMillis);

    public static void meshPublished(int ticket) {
        if (ENABLED && ticket != 0) {
            meshPublishedJs(ticket, nowMillis());
        }
    }

    @JSBody(params = {"ticket", "reason", "atMillis"}, script = """
            const root=globalThis.__gaiusChunkPipelineTelemetry;
            const causal=root&&root.causal;
            const section=causal&&causal.byTicket?causal.byTicket.get(Number(ticket)|0):null;if(!section)return;
            const reasons={1:'superseded',2:'level-epoch',3:'resource-epoch',4:'failed'};
            section.discardAt=Number(atMillis);section.discardReason=reasons[Number(reason)|0]||'discarded';causal.mark(section,'discard',atMillis);
            """)
    private static native void discardJs(int ticket, int reason, double atMillis);

    @JSBody(params = {"ticket", "reason", "atMillis"}, script = """
            const root=globalThis.__gaiusChunkPipelineTelemetry;
            const causal=root&&root.causal;
            const section=causal&&causal.byTicket?causal.byTicket.get(Number(ticket)|0):null;if(!section)return;
            section.discardAt=Number(atMillis);section.discardReason=String(reason||'discarded');causal.mark(section,'discard',atMillis);
            """)
    private static native void discardTextJs(int ticket, String reason, double atMillis);

    @JSBody(params = {"compileBacklog", "uploadBacklog", "queuedWork", "atMillis"}, script = """
            const root=globalThis.__gaiusChunkPipelineTelemetry;
            const causal=root&&root.causal;if(!causal)return;
            causal.schedulerSamples.push({atMillis:Number(atMillis),compileBacklog:Number(compileBacklog)||0,
              uploadBacklog:Number(uploadBacklog)||0,queuedWork:Number(queuedWork)||0});
            if(causal.schedulerSamples.length>(Number(causal.schedulerCapacity)||512))causal.schedulerSamples.shift();
            """)
    private static native void schedulerSnapshotJs(int compileBacklog, int uploadBacklog, int queuedWork, double atMillis);
}
