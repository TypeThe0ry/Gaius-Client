import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class ReflectionFactoryScan {
  public static void main(String[] args) throws Exception {
    try (ZipFile jar = new ZipFile(args[0])) {
      var entries = jar.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (!entry.getName().endsWith(".class")) continue;
        try (InputStream input = jar.getInputStream(entry)) {
          new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
            private String owner;
            @Override public void visit(int version, int access, String name, String signature,
                String superName, String[] interfaces) { owner = name; }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
              return new MethodVisitor(Opcodes.ASM9) {
                @Override public void visitMethodInsn(int opcode, String callOwner, String callName,
                    String callDescriptor, boolean isInterface) {
                  if ((callOwner.equals("java/lang/reflect/Constructor") && callName.equals("newInstance"))
                      || (callOwner.equals("java/lang/Class") &&
                          (callName.equals("newInstance") || callName.equals("getConstructor") ||
                           callName.equals("getDeclaredConstructor")))) {
                    System.out.println(owner + "#" + name + descriptor + " -> " + callOwner + "." + callName);
                  }
                }
              };
            }
          }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
      }
    }
  }
}
