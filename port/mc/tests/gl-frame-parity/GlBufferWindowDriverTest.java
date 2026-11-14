package dev.gaius.mc.tests.gl;

import com.google.gson.*;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;
import java.util.*;
import static java.lang.foreign.ValueLayout.*;

/** Real surfaceless native GL readback of the JVM-recorded upload payloads. */
public final class GlBufferWindowDriverTest implements AutoCloseable {
    private final Arena arena=Arena.ofConfined();
    private final Linker linker=Linker.nativeLinker();
    private final SymbolLookup egl=SymbolLookup.libraryLookup("libEGL.so.1",arena);
    private final Map<String,MethodHandle> functions=new HashMap<>();
    private MemorySegment display=MemorySegment.NULL,surface=MemorySegment.NULL,context=MemorySegment.NULL;

    private Object call(String name,MemoryLayout result,List<MemoryLayout> parameters,Object... args) throws Throwable {
        MethodHandle handle=functions.get(name);
        if(handle==null) {
            MemorySegment address;
            if(name.startsWith("egl"))address=egl.find(name).orElseThrow();
            else address=(MemorySegment)call("eglGetProcAddress",ADDRESS,List.of(ADDRESS),arena.allocateFrom(name));
            if(address.address()==0)throw new AssertionError("missing native entry "+name);
            var fd=result==null?FunctionDescriptor.ofVoid(parameters.toArray(MemoryLayout[]::new))
                :FunctionDescriptor.of(result,parameters.toArray(MemoryLayout[]::new));
            handle=linker.downcallHandle(address,fd);functions.put(name,handle);
        }
        return handle.invokeWithArguments(args);
    }
    private void require(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
    private void init() throws Throwable {
        display=(MemorySegment)call("eglGetPlatformDisplay",ADDRESS,List.of(JAVA_INT,ADDRESS,ADDRESS),0x31DD,MemorySegment.NULL,MemorySegment.NULL);
        require(display.address()!=0,"surfaceless display unavailable");
        var major=arena.allocate(JAVA_INT);var minor=arena.allocate(JAVA_INT);
        require((int)call("eglInitialize",JAVA_INT,List.of(ADDRESS,ADDRESS,ADDRESS),display,major,minor)!=0,"eglInitialize");
        require((int)call("eglBindAPI",JAVA_INT,List.of(JAVA_INT),0x30A2)!=0,"eglBindAPI OpenGL");
        var attrs=arena.allocateFrom(JAVA_INT,0x3033,1,0x3040,8,0x3038);
        var config=arena.allocate(ADDRESS);var count=arena.allocate(JAVA_INT);
        require((int)call("eglChooseConfig",JAVA_INT,List.of(ADDRESS,ADDRESS,ADDRESS,JAVA_INT,ADDRESS),display,attrs,config,1,count)!=0&&count.get(JAVA_INT,0)>0,"eglChooseConfig");
        var selected=config.get(ADDRESS,0);
        surface=(MemorySegment)call("eglCreatePbufferSurface",ADDRESS,List.of(ADDRESS,ADDRESS,ADDRESS),display,selected,arena.allocateFrom(JAVA_INT,0x3057,1,0x3056,1,0x3038));
        context=(MemorySegment)call("eglCreateContext",ADDRESS,List.of(ADDRESS,ADDRESS,ADDRESS,ADDRESS),display,selected,MemorySegment.NULL,arena.allocateFrom(JAVA_INT,0x3098,4,0x30FB,5,0x3038));
        require(surface.address()!=0&&context.address()!=0,"native surface/context creation");
        require((int)call("eglMakeCurrent",JAVA_INT,List.of(ADDRESS,ADDRESS,ADDRESS,ADDRESS),display,surface,surface,context)!=0,"eglMakeCurrent");
    }
    private int error() throws Throwable {return (int)call("glGetError",JAVA_INT,List.of());}
    private String string(int key) throws Throwable {
        return ((MemorySegment)call("glGetString",ADDRESS,List.of(JAVA_INT),key)).reinterpret(4096).getString(0);
    }
    private Map<String,Object> upload(String entry,byte[] bytes,int size,int offset) throws Throwable {
        try(var a=Arena.ofConfined()) {
            var idMemory=a.allocate(JAVA_INT);
            call("glGenBuffers",null,List.of(JAVA_INT,ADDRESS),1,idMemory);
            int id=idMemory.get(JAVA_INT,0);
            call("glBindBuffer",null,List.of(JAVA_INT,JAVA_INT),0x8892,id);
            var initial=a.allocate(size);initial.fill((byte)0xCC);
            call("glBufferData",null,List.of(JAVA_INT,JAVA_LONG,ADDRESS,JAVA_INT),0x8892,(long)size,initial,0x88E4);
            require(error()==0,"initial buffer allocation failed");
            var input=a.allocate(Math.max(1,bytes.length));input.asByteBuffer().put(bytes);
            boolean sub=entry.endsWith("SubData"),named=entry.startsWith("glNamed");
            if(sub)call(named?"glNamedBufferSubData":"glBufferSubData",null,List.of(JAVA_INT,JAVA_LONG,JAVA_LONG,ADDRESS),named?id:0x8892,(long)offset,(long)bytes.length,input);
            else call(named?"glNamedBufferData":"glBufferData",null,List.of(JAVA_INT,JAVA_LONG,ADDRESS,JAVA_INT),named?id:0x8892,(long)bytes.length,input,0x88E4);
            int error=error(),readSize=sub?size:bytes.length;
            var read=a.allocate(Math.max(1,readSize));
            call("glGetBufferSubData",null,List.of(JAVA_INT,JAVA_LONG,JAVA_LONG,ADDRESS),0x8892,0L,(long)readSize,read);
            require(error()==0,"readback failed");
            byte[] actual=Arrays.copyOf(read.toArray(JAVA_BYTE),readSize),expected=new byte[readSize];
            if(sub)Arrays.fill(expected,(byte)0xCC);
            boolean fits=!sub||offset+bytes.length<=size;
            if(fits)System.arraycopy(bytes,0,expected,sub?offset:0,bytes.length);
            require(error==(fits?0:0x501),"unexpected upload error "+error);
            require(Arrays.equals(actual,expected),"native readback differs from atomic upload/reject outcome");
            call("glDeleteBuffers",null,List.of(JAVA_INT,ADDRESS),1,idMemory);
            return Map.of("size",readSize,"offset",sub?offset:0,"submittedBytes",bytes.length,"error",error,
                "outcome",fits?"uploaded":"whole-call-rejected","bytes",Base64.getEncoder().encodeToString(actual));
        }
    }
    public void close() {
        try {
            if(context.address()!=0) {
                call("eglMakeCurrent",JAVA_INT,List.of(ADDRESS,ADDRESS,ADDRESS,ADDRESS),display,MemorySegment.NULL,MemorySegment.NULL,MemorySegment.NULL);
                call("eglDestroyContext",JAVA_INT,List.of(ADDRESS,ADDRESS),display,context);
            }
            if(surface.address()!=0)call("eglDestroySurface",JAVA_INT,List.of(ADDRESS,ADDRESS),display,surface);
            if(display.address()!=0)call("eglTerminate",JAVA_INT,List.of(ADDRESS),display);
        } catch(Throwable e) {throw new RuntimeException(e);} finally {arena.close();}
    }
    public static void main(String[] args) throws Throwable {
        var recording=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        var rows=new ArrayList<Map<String,Object>>();int mismatches=0;String marker="";
        try(var driver=new GlBufferWindowDriverTest()) {
            driver.init();
            for(var value:recording.getAsJsonArray("bufferWindowProbes")) {
                var event=value.getAsJsonObject();
                if(event.has("marker")){marker=event.get("marker").getAsString();continue;}
                if(!event.has("browserBridge"))continue;
                String entry=event.get("call").getAsString();
                var input=event.getAsJsonArray("args").asList().stream().filter(v->v.isJsonObject()&&v.getAsJsonObject().has("bytes")).findFirst().orElseThrow().getAsJsonObject();
                byte[] nativeBytes=Base64.getDecoder().decode(input.get("bytes").getAsString());
                for(String mode:List.of("typed","linear")) {
                    byte[] browserBytes=Base64.getDecoder().decode(event.getAsJsonObject("browserBridge").getAsJsonObject(mode).get("bytes").getAsString());
                    boolean equivalent=Arrays.equals(nativeBytes,browserBytes);if(!equivalent)mismatches++;
                    for(int size:entry.endsWith("SubData")?new int[]{384,128,41}:new int[]{384}) {
                        var nativeResult=driver.upload(entry,nativeBytes,size,19);
                        var browserResult=driver.upload(entry,browserBytes,size,19);
                        byte[] n=Base64.getDecoder().decode((String)nativeResult.get("bytes")),b=Base64.getDecoder().decode((String)browserResult.get("bytes"));
                        int outsideChanged=0,insideChanged=0;
                        if(entry.endsWith("SubData"))for(int i=0;i<n.length;i++)if(n[i]!=b[i]) {
                            if(i<19||i>=19+nativeBytes.length)outsideChanged++;else insideChanged++;
                        }
                        rows.add(Map.of("case",marker,"entry",entry,"mode",mode,"nativeBytes",nativeBytes.length,
                            "browserBytes",browserBytes.length,"sourceEquivalent",equivalent,"native",nativeResult,"browser",browserResult,
                            "outsideNativeWindowDifferentBytes",outsideChanged,"insideNativeWindowDifferentBytes",insideChanged));
                    }
                }
            }
            var report=Map.of("schema",1,"nativeDriverAssertionsPassed",true,"sourceMismatches",mismatches,"windowEquivalent",mismatches==0,
                "vendor",driver.string(0x1F00),"renderer",driver.string(0x1F01),"version",driver.string(0x1F02),"cases",rows,
                "scope","Real native OpenGL upload/error/readback of JVM-recorded payloads; not a WebGL context or TeaVM execution.");
            Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
            System.out.println("GL_BUFFER_WINDOW_DRIVER_OK cases="+rows.size()+" sourceMismatches="+mismatches);
        }
    }
}
