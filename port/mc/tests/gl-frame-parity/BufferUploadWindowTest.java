package dev.gaius.mc.tests.gl;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;

/** Executes verbatim production wrappers; interop boundary is explicitly modeled. */
public final class BufferUploadWindowTest {
    public static void main(String[] args) throws Exception {
        var probes = new ArrayList<Map<String,Object>>();
        int checks = 0, mismatches = 0;
        for (boolean direct : new boolean[]{false,true}) {
            for (String shape : List.of("full","trimmed","empty","end-empty","slice","nested-slice","duplicate","read-only")) {
                ByteBuffer data = direct ? ByteBuffer.allocateDirect(160) : ByteBuffer.allocate(160);
                for (int i=0;i<data.capacity();i++) data.put(i,(byte)(i+1));
                if (shape.equals("slice") || shape.equals("nested-slice")) {
                    data.position(11).limit(139); data=data.slice();
                    if(shape.equals("nested-slice")){data.position(3).limit(120);data=data.slice();}
                } else { data.limit(128); data=data.slice(); }
                switch(shape) {
                    case "full" -> {}
                    case "empty" -> data.position(7).limit(7);
                    case "end-empty" -> data.position(data.capacity());
                    default -> data.position(7).limit(29);
                }
                if(shape.equals("duplicate"))data=data.duplicate();
                if(shape.equals("read-only"))data=data.asReadOnlyBuffer();
                int position=data.position(),limit=data.limit();
                byte[] expected=new byte[data.remaining()];data.duplicate().get(expected);
                for(String entry:List.of("glBufferData","glBufferSubData","glNamedBufferData","glNamedBufferSubData")) {
                    Object[] parameters=entry.endsWith("SubData")?new Object[]{0x8892,19L,data}:new Object[]{0x8892,data,0x88e4};
                    var receipts=BrowserBufferBridge.record(entry,parameters);
                    if(data.position()!=position || data.limit()!=limit)throw new AssertionError("caller window changed");
                    for(String mode:List.of("typed","linear")) {
                        var receipt=(Map<?,?>)receipts.get(mode);
                        byte[] actual=Base64.getDecoder().decode((String)receipt.get("bytes"));
                        checks++;if(!Arrays.equals(expected,actual))mismatches++;
                    }
                    probes.add(Map.of("marker",(direct?"direct:":"heap:")+shape));
                    probes.add(Map.of("call",entry,"args",List.of(0x8892,19,Map.of("bytes",Base64.getEncoder().encodeToString(expected))),"browserBridge",receipts));
                }
            }
        }
        var result=Map.of("checks",checks,"sourceMismatches",mismatches,"bufferWindowProbes",probes);
        Files.writeString(Path.of(args[0]),new GsonBuilder().setPrettyPrinting().create().toJson(result)+"\n");
        System.out.println("BUFFER_UPLOAD_WINDOWS checks="+checks+" mismatches="+mismatches);
        if(mismatches!=0)System.exit(2);
    }
}
