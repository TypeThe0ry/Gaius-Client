// Compile verbatim production Java upload wrappers with recording boundary shims.
// The TeaVM typed-array and address bridges are models, not a TeaVM execution.
import assert from 'node:assert/strict';
export function productionMethod(source,signature) {
  const start=source.indexOf(signature);assert(start>=0,`missing Java wrapper ${signature}`);
  assert.equal(source.indexOf(signature,start+signature.length),-1,`ambiguous Java wrapper ${signature}`);
  const end=source.indexOf('\n    }',start);assert(end>=0,`missing Java method end ${signature}`);
  return source.slice(start,end+6);
}
export function bufferBridgeSource(source) {
  const signatures=[
    'public static void bufferData(int target, ByteBuffer data, int usage)',
    'public static void bufferSubData(int target, long offset, ByteBuffer data)',
    'public static void namedBufferData(int buffer, ByteBuffer data, int usage)',
    'public static void namedBufferSubData(int buffer, long offset, ByteBuffer data)',
    'private static Int8Array bytes(ByteBuffer buffer)',
    'private static int linearBase(ByteBuffer buffer)',
    'private static void bufferSubDataLinearWithCounters(int target, int offset, int address, int length)',
    'private static void namedBufferSubDataLinearWithCounters(int buffer, int offset, int address, int length)',
  ];
  if(source.includes('private static Int8Array remainingBytes(ByteBuffer buffer)'))
    signatures.push('private static Int8Array remainingBytes(ByteBuffer buffer)');
  return `package dev.gaius.mc.tests.gl;
import java.nio.ByteBuffer;
import java.util.*;
public final class BrowserBufferBridge {
  private static ByteBuffer input;
  private static Map<String,Object> receipt;
  private static final int BASE=4096;
  static final class JsArrays { static boolean LINEAR_MEMORY; }
  static final class BrowserOpenGLPerf { static void noteUpload(long length) {} }
  static final class MemoryUtil { static long memAddress(ByteBuffer data) {
    if(data!=input)throw new AssertionError("unexpected address input");return BASE+data.position();
  } }
  static final class Int8Array {
    final byte[] bytes;
    Int8Array(byte[] value){bytes=value;}
    static Int8Array fromJavaBuffer(ByteBuffer data){return new Int8Array(backing(data));}
  }
  private static byte[] backing(ByteBuffer data) {
    var copy=data.duplicate();copy.clear();var bytes=new byte[copy.remaining()];copy.get(bytes);return bytes;
  }
  private static void capture(String method,byte[] data) {
    receipt=Map.of("method",method,"bytes",Base64.getEncoder().encodeToString(data));
  }
  private static void bufferDataJs(int target,Int8Array data,int usage){capture("bufferDataJs",data.bytes);}
  private static void bufferSubDataJs(int target,int offset,Int8Array data){capture("bufferSubDataJs",data.bytes);}
  private static void namedBufferDataJs(int buffer,Int8Array data,int usage){capture("namedBufferDataJs",data.bytes);}
  private static void namedBufferSubDataJs(int buffer,int offset,Int8Array data){capture("namedBufferSubDataJs",data.bytes);}
  private static byte[] linear(int address,int length) {
    if(address<BASE||address-BASE+length>input.capacity())throw new AssertionError("invalid linear window");
    return Arrays.copyOfRange(backing(input),address-BASE,address-BASE+length);
  }
  private static void bufferDataLinearJs(int target,int address,int length,int usage){capture("bufferDataLinearJs",linear(address,length));}
  private static void bufferSubDataLinearJs(int target,int offset,int address,int length){capture("bufferSubDataLinearJs",linear(address,length));}
  private static void namedBufferDataLinearJs(int buffer,int address,int length,int usage){capture("namedBufferDataLinearJs",linear(address,length));}
  private static void namedBufferSubDataLinearJs(int buffer,int offset,int address,int length){capture("namedBufferSubDataLinearJs",linear(address,length));}
  public static Map<String,Object> record(String call,Object[] args) {
    boolean sub=call.endsWith("SubData"),named=call.startsWith("glNamed");
    input=(ByteBuffer)args[sub?2:1];
    int position=input.position(),limit=input.limit();
    var result=new LinkedHashMap<String,Object>();
    for(boolean mode:new boolean[]{false,true}) {
      JsArrays.LINEAR_MEMORY=mode;receipt=null;
      if(named) {
        if(sub)namedBufferSubData((Integer)args[0],((Number)args[1]).longValue(),input);
        else namedBufferData((Integer)args[0],input,(Integer)args[2]);
      } else {
        if(sub)bufferSubData((Integer)args[0],((Number)args[1]).longValue(),input);
        else bufferData((Integer)args[0],input,(Integer)args[2]);
      }
      if(position!=input.position()||limit!=input.limit())throw new AssertionError("caller window changed");
      if(receipt==null)throw new AssertionError("wrapper did not reach upload boundary");
      result.put(mode?"linear":"typed",receipt);
    }
    return result;
  }
${signatures.map(s=>productionMethod(source,s)).join('\n')}
}
`;
}
