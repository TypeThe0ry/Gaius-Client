package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Redirects the OpenGL subset used by Minecraft to WebGL2.
 *
 * <p>Most entry points delegate to {@code BrowserOpenGL}. The indexed-capability and read-buffer
 * natives that only LWJGL 3.4.3 clients (Minecraft 26.3) call delegate to
 * {@code BrowserOpenGLIndexed}, which only the lwjgl-opengl 3.4.3 overlay compiles in
 * ({@code port/overrides/libraries/lwjgl-opengl/src/versions/3.4.3}). Those delegates are
 * gated on that class being present in the input jar, so a 3.4.1 jar (26.2) is patched exactly
 * as before; a 3.4.3 or later jar without the class is refused.
 */
public final class LwjglOpenGLBrowserPatcher {
    private static final String BROWSER = "org/lwjgl/opengl/BrowserOpenGL";
    private static final String BROWSER_INDEXED = "org/lwjgl/opengl/BrowserOpenGLIndexed";

    private LwjglOpenGLBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: LwjglOpenGLBrowserPatcher INPUT_JAR OUTPUT_ROOT");
        }
        Map<String, String> delegates = delegates();
        Set<String> noOps = noOps();
        Map<String, String> indexedDelegates = indexedDelegates(args[0]);
        Set<String> owners = new HashSet<>();
        delegates.keySet().forEach(key -> owners.add(key.substring(0, key.indexOf('#'))));
        noOps.forEach(key -> owners.add(key.substring(0, key.indexOf('#'))));
        indexedDelegates.keySet().forEach(key -> owners.add(key.substring(0, key.indexOf('#'))));
        int replacements = 0;
        Set<String> indexedPatched = new HashSet<>();
        for (String owner : owners) {
            ClassNode node = read(args[0], owner + ".class");
            boolean changed = false;
            for (MethodNode method : node.methods) {
                String key = owner + "#" + method.name + method.desc;
                String target = delegates.get(key);
                String indexedTarget = indexedDelegates.get(key);
                if (target != null) {
                    delegate(method, target);
                    changed = true;
                    replacements++;
                } else if (indexedTarget != null) {
                    delegate(method, BROWSER_INDEXED, indexedTarget);
                    indexedPatched.add(key);
                    changed = true;
                    replacements++;
                } else if (noOps.contains(key)) {
                    replaceDefault(method);
                    changed = true;
                    replacements++;
                }
            }
            if (changed) {
                write(node, Path.of(args[1]).resolve(owner + ".class"));
            }
        }
        if (replacements < 65) {
            throw new IllegalStateException("Too few OpenGL methods patched: " + replacements);
        }
        if (!indexedPatched.equals(indexedDelegates.keySet())) {
            Set<String> missing = new HashSet<>(indexedDelegates.keySet());
            missing.removeAll(indexedPatched);
            throw new IllegalStateException(
                    "LWJGL 3.4.3 OpenGL entry points were not found: " + missing);
        }
        if (!indexedPatched.isEmpty()) {
            System.out.println("Delegated " + indexedPatched.size()
                    + " LWJGL 3.4.3 OpenGL entry points to BrowserOpenGLIndexed");
        }
        System.out.println("Patched " + replacements + " OpenGL methods");
    }

    /**
     * The natives that only LWJGL 3.4.3 clients reach, keyed like {@link #delegates()}. Empty for
     * a jar without {@code BrowserOpenGLIndexed} (the 3.4.1 overlay); a jar of lwjgl-opengl
     * 3.4.3 or later without it throws, because its client would silently lose blending.
     */
    private static Map<String, String> indexedDelegates(String jarPath) throws IOException {
        String version;
        boolean indexed;
        try (ZipFile jar = new ZipFile(jarPath)) {
            indexed = jar.getEntry(BROWSER_INDEXED + ".class") != null;
            version = specificationVersion(jar);
        }
        if (!indexed) {
            if (version != null && compareVersions(version, "3.4.3") >= 0) {
                throw new IllegalStateException("lwjgl-opengl " + version + " (" + jarPath
                        + ") needs " + BROWSER_INDEXED + " from port/overrides/libraries/"
                        + "lwjgl-opengl/src/versions/" + version + "/java");
            }
            return Map.of();
        }
        Map<String, String> methods = new HashMap<>();
        add(methods, "GL30C", "glEnablei", "(II)V", "enablei");
        add(methods, "GL30C", "glDisablei", "(II)V", "disablei");
        add(methods, "GL11C", "glReadBuffer", "(I)V", "readBuffer");
        return methods;
    }

    private static String specificationVersion(ZipFile jar) throws IOException {
        var entry = jar.getEntry("META-INF/MANIFEST.MF");
        if (entry == null) {
            return null;
        }
        try (var stream = jar.getInputStream(entry)) {
            return new java.util.jar.Manifest(stream).getMainAttributes()
                    .getValue("Specification-Version");
        }
    }

    private static int compareVersions(String left, String right) {
        String[] a = left.trim().split("[.]");
        String[] b = right.trim().split("[.]");
        for (int index = 0; index < Math.max(a.length, b.length); index++) {
            int x = index < a.length ? leadingNumber(a[index]) : 0;
            int y = index < b.length ? leadingNumber(b[index]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int leadingNumber(String part) {
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) {
            end++;
        }
        return end == 0 ? 0 : Integer.parseInt(part.substring(0, end));
    }

    private static Map<String, String> delegates() {
        Map<String, String> methods = new HashMap<>();
        add(methods, "GL", "setCapabilities", "(Lorg/lwjgl/opengl/GLCapabilities;)V", "setCapabilities");
        add(methods, "GL", "getCapabilities", "()Lorg/lwjgl/opengl/GLCapabilities;", "getCapabilities");
        for (String owner : new String[] {"GL11", "GL11C"}) {
            add(methods, owner, "glEnable", "(I)V", "enable");
            add(methods, owner, "glDisable", "(I)V", "disable");
            add(methods, owner, "glClearColor", "(FFFF)V", "clearColor");
            add(methods, owner, "glClearDepth", "(D)V", "clearDepth");
            add(methods, owner, "glClear", "(I)V", "clear");
            add(methods, owner, "glColorMask", "(ZZZZ)V", "colorMask");
            add(methods, owner, "glDrawBuffer", "(I)V", "drawBuffer");
            add(methods, owner, "glDepthFunc", "(I)V", "depthFunc");
            add(methods, owner, "glDepthMask", "(Z)V", "depthMask");
            add(methods, owner, "glDrawArrays", "(III)V", "drawArrays");
            add(methods, owner, "glDrawElements", "(IIIJ)V", "drawElements");
            add(methods, owner, "glGetError", "()I", "getError");
            add(methods, owner, "glGetInteger", "(I)I", "getInteger");
            add(methods, owner, "glGetFloat", "(I)F", "getFloat");
            add(methods, owner, "glGetString", "(I)Ljava/lang/String;", "getString");
            add(methods, owner, "glLogicOp", "(I)V", "logicOp");
            add(methods, owner, "glPixelStorei", "(II)V", "pixelStorei");
            add(methods, owner, "glPolygonMode", "(II)V", "polygonMode");
            add(methods, owner, "glPolygonOffset", "(FF)V", "polygonOffset");
            add(methods, owner, "glViewport", "(IIII)V", "viewport");
            add(methods, owner, "glScissor", "(IIII)V", "scissor");
            add(methods, owner, "glGenTextures", "()I", "genTexture");
            add(methods, owner, "glDeleteTextures", "(I)V", "deleteTexture");
            add(methods, owner, "glBindTexture", "(II)V", "bindTexture");
            add(methods, owner, "glTexParameteri", "(III)V", "texParameteri");
            add(methods, owner, "glTexImage2D", "(IIIIIIIILjava/nio/ByteBuffer;)V", "texImage2D");
            add(methods, owner, "glTexSubImage2D", "(IIIIIIIILjava/nio/ByteBuffer;)V", "texSubImage2D");
            add(methods, owner, "glTexSubImage2D", "(IIIIIIIIJ)V", "texSubImage2D");
            add(methods, owner, "nglReadPixels", "(IIIIIIJ)V", "readPixels");
            add(methods, owner, "glReadPixels", "(IIIIIIJ)V", "readPixels");
        }
        for (String owner : new String[] {"GL13", "GL13C"}) {
            add(methods, owner, "glActiveTexture", "(I)V", "activeTexture");
        }
        add(methods, "GL11", "glBlendFunc", "(II)V", "blendFunc");
        add(methods, "GL11C", "glBlendFunc", "(II)V", "blendFunc");
        add(methods, "GL14", "glBlendEquation", "(I)V", "blendEquation");
        add(methods, "GL14C", "glBlendEquation", "(I)V", "blendEquation");
        add(methods, "GL14", "glBlendFuncSeparate", "(IIII)V", "blendFuncSeparate");
        add(methods, "GL14C", "glBlendFuncSeparate", "(IIII)V", "blendFuncSeparate");
        add(methods, "GL14", "glBlendEquationSeparate", "(II)V", "blendEquationSeparate");
        add(methods, "GL14C", "glBlendEquationSeparate", "(II)V", "blendEquationSeparate");
        add(methods, "GL14", "nglMultiDrawArrays", "(IJJI)V", "multiDrawArrays");
        add(methods, "GL14C", "nglMultiDrawArrays", "(IJJI)V", "multiDrawArrays");
        add(methods, "GL20", "glBlendEquationSeparate", "(II)V", "blendEquationSeparate");
        add(methods, "GL20C", "glBlendEquationSeparate", "(II)V", "blendEquationSeparate");
        for (String owner : new String[] {"GL15", "GL15C"}) {
            add(methods, owner, "glGenBuffers", "()I", "genBuffer");
            add(methods, owner, "glDeleteBuffers", "(I)V", "deleteBuffer");
            add(methods, owner, "glBindBuffer", "(II)V", "bindBuffer");
            add(methods, owner, "glBufferData", "(IJI)V", "bufferData");
            add(methods, owner, "glBufferData", "(ILjava/nio/ByteBuffer;I)V", "bufferData");
            add(methods, owner, "glBufferSubData", "(IJLjava/nio/ByteBuffer;)V", "bufferSubData");
            add(methods, owner, "glUnmapBuffer", "(I)Z", "unmapBuffer");
        }
        for (String owner : new String[] {"GL15", "GL15C", "GL32", "GL32C"}) {
            add(methods, owner, "nglGenQueries", "(IJ)V", "genQueries");
            add(methods, owner, "glGenQueries", "(Ljava/nio/IntBuffer;)V", "genQueries");
            add(methods, owner, "glGenQueries", "()I", "genQuery");
            add(methods, owner, "glGenQueries", "([I)V", "genQueries");
        }
        for (String owner : new String[] {"GL20", "GL20C"}) {
            add(methods, owner, "nglDrawBuffers", "(IJ)V", "drawBuffers");
            add(methods, owner, "glDrawBuffers", "(Ljava/nio/IntBuffer;)V", "drawBuffers");
            add(methods, owner, "glDrawBuffers", "(I)V", "drawBuffers");
            add(methods, owner, "glDrawBuffers", "([I)V", "drawBuffers");
        }
        add(methods, "GL20", "glCreateShader", "(I)I", "createShader");
        add(methods, "GL20C", "glCreateShader", "(I)I", "createShader");
        add(methods, "GL20", "glCreateProgram", "()I", "createProgram");
        add(methods, "GL20C", "glCreateProgram", "()I", "createProgram");
        add(methods, "GL20", "glShaderSource", "(ILjava/lang/CharSequence;)V", "shaderSource");
        add(methods, "GL20C", "glShaderSource", "(ILjava/lang/CharSequence;)V", "shaderSource");
        add(methods, "GL20C", "glShaderSource", "(I[Ljava/lang/CharSequence;)V", "shaderSourceArray");
        add(methods, "GL20C", "nglShaderSource", "(IIJJ)V", "shaderSourceNative");
        add(methods, "GL20", "glCompileShader", "(I)V", "compileShader");
        add(methods, "GL20C", "glCompileShader", "(I)V", "compileShader");
        add(methods, "GL20", "glAttachShader", "(II)V", "attachShader");
        add(methods, "GL20C", "glAttachShader", "(II)V", "attachShader");
        add(methods, "GL20", "glBindAttribLocation", "(IILjava/lang/CharSequence;)V", "bindAttribLocation");
        add(methods, "GL20C", "glBindAttribLocation", "(IILjava/lang/CharSequence;)V", "bindAttribLocation");
        add(methods, "GL20", "glLinkProgram", "(I)V", "linkProgram");
        add(methods, "GL20C", "glLinkProgram", "(I)V", "linkProgram");
        add(methods, "GL20", "glUseProgram", "(I)V", "useProgram");
        add(methods, "GL20C", "glUseProgram", "(I)V", "useProgram");
        add(methods, "GL20", "glDeleteProgram", "(I)V", "deleteProgram");
        add(methods, "GL20C", "glDeleteProgram", "(I)V", "deleteProgram");
        add(methods, "GL20", "glDeleteShader", "(I)V", "deleteShader");
        add(methods, "GL20C", "glDeleteShader", "(I)V", "deleteShader");
        add(methods, "GL20", "glGetProgrami", "(II)I", "getProgrami");
        add(methods, "GL20C", "glGetProgrami", "(II)I", "getProgrami");
        add(methods, "GL20", "glGetShaderi", "(II)I", "getShaderi");
        add(methods, "GL20C", "glGetShaderi", "(II)I", "getShaderi");
        add(methods, "GL20", "glGetProgramInfoLog", "(II)Ljava/lang/String;", "getProgramInfoLog");
        add(methods, "GL20C", "glGetProgramInfoLog", "(II)Ljava/lang/String;", "getProgramInfoLog");
        add(methods, "GL20", "glGetShaderInfoLog", "(II)Ljava/lang/String;", "getShaderInfoLog");
        add(methods, "GL20C", "glGetShaderInfoLog", "(II)Ljava/lang/String;", "getShaderInfoLog");
        add(methods, "GL20", "glGetUniformLocation", "(ILjava/lang/CharSequence;)I", "getUniformLocation");
        add(methods, "GL20C", "glGetUniformLocation", "(ILjava/lang/CharSequence;)I", "getUniformLocation");
        add(methods, "GL20", "glUniform1i", "(II)V", "uniform1i");
        add(methods, "GL20C", "glUniform1i", "(II)V", "uniform1i");
        add(methods, "GL20", "glUniform1f", "(IF)V", "uniform1f");
        add(methods, "GL20C", "glUniform1f", "(IF)V", "uniform1f");
        add(methods, "GL20", "glUniform2f", "(IFF)V", "uniform2f");
        add(methods, "GL20C", "glUniform2f", "(IFF)V", "uniform2f");
        add(methods, "GL20", "glUniform3f", "(IFFF)V", "uniform3f");
        add(methods, "GL20C", "glUniform3f", "(IFFF)V", "uniform3f");
        add(methods, "GL20", "glUniform4f", "(IFFFF)V", "uniform4f");
        add(methods, "GL20C", "glUniform4f", "(IFFFF)V", "uniform4f");
        add(methods, "GL20", "glUniform2i", "(III)V", "uniform2i");
        add(methods, "GL20C", "glUniform2i", "(III)V", "uniform2i");
        add(methods, "GL20", "glUniform3i", "(IIII)V", "uniform3i");
        add(methods, "GL20C", "glUniform3i", "(IIII)V", "uniform3i");
        add(methods, "GL20", "glUniform4i", "(IIIII)V", "uniform4i");
        add(methods, "GL20C", "glUniform4i", "(IIIII)V", "uniform4i");
        for (String owner : new String[] {"GL20", "GL20C"}) {
            add(methods, owner, "glUniform1fv", "(ILjava/nio/FloatBuffer;)V", "uniform1fv");
            add(methods, owner, "glUniform2fv", "(ILjava/nio/FloatBuffer;)V", "uniform2fv");
            add(methods, owner, "glUniform3fv", "(ILjava/nio/FloatBuffer;)V", "uniform3fv");
            add(methods, owner, "glUniform4fv", "(ILjava/nio/FloatBuffer;)V", "uniform4fv");
            add(methods, owner, "glUniform1iv", "(ILjava/nio/IntBuffer;)V", "uniform1iv");
            add(methods, owner, "glUniform2iv", "(ILjava/nio/IntBuffer;)V", "uniform2iv");
            add(methods, owner, "glUniform3iv", "(ILjava/nio/IntBuffer;)V", "uniform3iv");
            add(methods, owner, "glUniform4iv", "(ILjava/nio/IntBuffer;)V", "uniform4iv");
            add(methods, owner, "glUniformMatrix2fv", "(IZLjava/nio/FloatBuffer;)V", "uniformMatrix2fv");
            add(methods, owner, "glUniformMatrix3fv", "(IZLjava/nio/FloatBuffer;)V", "uniformMatrix3fv");
            add(methods, owner, "glUniformMatrix4fv", "(IZLjava/nio/FloatBuffer;)V", "uniformMatrix4fv");
            add(methods, owner, "glUniform1fv", "(I[F)V", "uniform1fv");
            add(methods, owner, "glUniform2fv", "(I[F)V", "uniform2fv");
            add(methods, owner, "glUniform3fv", "(I[F)V", "uniform3fv");
            add(methods, owner, "glUniform4fv", "(I[F)V", "uniform4fv");
            add(methods, owner, "glUniform1iv", "(I[I)V", "uniform1iv");
            add(methods, owner, "glUniform2iv", "(I[I)V", "uniform2iv");
            add(methods, owner, "glUniform3iv", "(I[I)V", "uniform3iv");
            add(methods, owner, "glUniform4iv", "(I[I)V", "uniform4iv");
            add(methods, owner, "glUniformMatrix2fv", "(IZ[F)V", "uniformMatrix2fv");
            add(methods, owner, "glUniformMatrix3fv", "(IZ[F)V", "uniformMatrix3fv");
            add(methods, owner, "glUniformMatrix4fv", "(IZ[F)V", "uniformMatrix4fv");
            add(methods, owner, "nglUniform1fv", "(IIJ)V", "uniform1fv");
            add(methods, owner, "nglUniform2fv", "(IIJ)V", "uniform2fv");
            add(methods, owner, "nglUniform3fv", "(IIJ)V", "uniform3fv");
            add(methods, owner, "nglUniform4fv", "(IIJ)V", "uniform4fv");
            add(methods, owner, "nglUniform1iv", "(IIJ)V", "uniform1iv");
            add(methods, owner, "nglUniform2iv", "(IIJ)V", "uniform2iv");
            add(methods, owner, "nglUniform3iv", "(IIJ)V", "uniform3iv");
            add(methods, owner, "nglUniform4iv", "(IIJ)V", "uniform4iv");
            add(methods, owner, "nglUniformMatrix2fv", "(IIZJ)V", "uniformMatrix2fv");
            add(methods, owner, "nglUniformMatrix3fv", "(IIZJ)V", "uniformMatrix3fv");
            add(methods, owner, "nglUniformMatrix4fv", "(IIZJ)V", "uniformMatrix4fv");
        }
        add(methods, "GL20", "glEnableVertexAttribArray", "(I)V", "enableVertexAttribArray");
        add(methods, "GL20C", "glEnableVertexAttribArray", "(I)V", "enableVertexAttribArray");
        add(methods, "GL20", "glDisableVertexAttribArray", "(I)V", "disableVertexAttribArray");
        add(methods, "GL20C", "glDisableVertexAttribArray", "(I)V", "disableVertexAttribArray");
        add(methods, "GL20", "glVertexAttribPointer", "(IIIZIJ)V", "vertexAttribPointer");
        add(methods, "GL20C", "glVertexAttribPointer", "(IIIZIJ)V", "vertexAttribPointer");
        add(methods, "GL30", "glVertexAttribIPointer", "(IIIIJ)V", "vertexAttribIPointer");
        add(methods, "GL30C", "glVertexAttribIPointer", "(IIIIJ)V", "vertexAttribIPointer");
        add(methods, "ARBVertexAttribBinding", "glBindVertexBuffer", "(IIJI)V", "bindVertexBuffer");
        add(methods, "ARBVertexAttribBinding", "glVertexAttribBinding", "(II)V", "vertexAttribBinding");
        add(methods, "ARBVertexAttribBinding", "glVertexAttribFormat", "(IIIZI)V", "vertexAttribFormat");
        add(methods, "ARBVertexAttribBinding", "glVertexAttribIFormat", "(IIII)V", "vertexAttribIFormat");
        add(methods, "GL33", "glVertexAttribDivisor", "(II)V", "vertexAttribDivisor");
        add(methods, "GL33C", "glVertexAttribDivisor", "(II)V", "vertexAttribDivisor");
        add(methods, "ARBInstancedArrays", "glVertexAttribDivisorARB", "(II)V", "vertexAttribDivisor");
        for (String owner : new String[] {"GL30", "GL30C"}) {
            add(methods, owner, "glColorMaski", "(IZZZZ)V", "colorMaski");
            add(methods, owner, "glGenVertexArrays", "()I", "genVertexArray");
            add(methods, owner, "glBindVertexArray", "(I)V", "bindVertexArray");
            add(methods, owner, "glDeleteVertexArrays", "(I)V", "deleteVertexArray");
            add(methods, owner, "glGenFramebuffers", "()I", "genFramebuffer");
            add(methods, owner, "glBindFramebuffer", "(II)V", "bindFramebuffer");
            add(methods, owner, "glFramebufferTexture2D", "(IIIII)V", "framebufferTexture2D");
            add(methods, owner, "glCheckFramebufferStatus", "(I)I", "checkFramebufferStatus");
            add(methods, owner, "glDeleteFramebuffers", "(I)V", "deleteFramebuffer");
            add(methods, owner, "glBlitFramebuffer", "(IIIIIIIIII)V", "blitFramebuffer");
        }
        for (String owner : new String[] {"GL30", "GL30C"}) {
            add(methods, owner, "nglClearBufferfv", "(IIJ)V", "clearBufferfv");
            add(methods, owner, "glClearBufferfv", "(IILjava/nio/FloatBuffer;)V", "clearBufferfv");
            add(methods, owner, "glClearBufferfv", "(II[F)V", "clearBufferfv");
        }
        add(methods, "ARBDirectStateAccess", "glCreateBuffers", "()I", "createBuffer");
        add(methods, "ARBDirectStateAccess", "glNamedBufferData", "(IJI)V", "namedBufferData");
        add(methods, "ARBDirectStateAccess", "glNamedBufferData", "(ILjava/nio/ByteBuffer;I)V", "namedBufferData");
        add(methods, "ARBDirectStateAccess", "glNamedBufferSubData", "(IJLjava/nio/ByteBuffer;)V", "namedBufferSubData");
        add(methods, "ARBDirectStateAccess", "glMapNamedBufferRange", "(IJJI)Ljava/nio/ByteBuffer;", "mapNamedBufferRange");
        add(methods, "ARBDirectStateAccess", "glUnmapNamedBuffer", "(I)Z", "unmapNamedBuffer");
        add(methods, "ARBDirectStateAccess", "glFlushMappedNamedBufferRange", "(IJJ)V", "flushMappedNamedBufferRange");
        add(methods, "ARBDirectStateAccess", "glCopyNamedBufferSubData", "(IIJJJ)V", "copyNamedBufferSubData");
        add(methods, "ARBDirectStateAccess", "glCreateFramebuffers", "()I", "createFramebuffer");
        add(methods, "ARBDirectStateAccess", "glNamedFramebufferTexture", "(IIII)V", "namedFramebufferTexture");
        add(methods, "ARBDirectStateAccess", "glCheckNamedFramebufferStatus", "(II)I", "checkNamedFramebufferStatus");
        add(methods, "ARBDirectStateAccess", "glBlitNamedFramebuffer", "(IIIIIIIIIIII)V", "blitNamedFramebuffer");
        add(methods, "GL30", "glMapBufferRange", "(IJJI)Ljava/nio/ByteBuffer;", "mapBufferRange");
        add(methods, "GL30C", "glMapBufferRange", "(IJJI)Ljava/nio/ByteBuffer;", "mapBufferRange");
        add(methods, "GL30", "nglMapBufferRange", "(IJJI)J", "mapBufferRangeAddress");
        add(methods, "GL30C", "nglMapBufferRange", "(IJJI)J", "mapBufferRangeAddress");
        add(methods, "GL30", "glFlushMappedBufferRange", "(IJJ)V", "flushMappedBufferRange");
        add(methods, "GL30C", "glFlushMappedBufferRange", "(IJJ)V", "flushMappedBufferRange");
        add(methods, "GL30", "glBindBufferRange", "(IIIJJ)V", "bindBufferRange");
        add(methods, "GL30C", "glBindBufferRange", "(IIIJJ)V", "bindBufferRange");
        add(methods, "GL30", "glBindBufferBase", "(III)V", "bindBufferBase");
        add(methods, "GL30C", "glBindBufferBase", "(III)V", "bindBufferBase");
        add(methods, "GL31", "glCopyBufferSubData", "(IIJJJ)V", "copyBufferSubData");
        add(methods, "GL31C", "glCopyBufferSubData", "(IIJJJ)V", "copyBufferSubData");
        add(methods, "GL31", "glDrawArraysInstanced", "(IIII)V", "drawArraysInstanced");
        add(methods, "GL31", "glDrawElementsInstanced", "(IIIJI)V", "drawElementsInstanced");
        add(methods, "GL31C", "glDrawArraysInstanced", "(IIII)V", "drawArraysInstanced");
        add(methods, "GL31C", "glDrawElementsInstanced", "(IIIJI)V", "drawElementsInstanced");
        add(methods, "GL31", "glTexBuffer", "(III)V", "texBuffer");
        add(methods, "GL31C", "glTexBuffer", "(III)V", "texBuffer");
        add(methods, "GL32", "glDrawElementsBaseVertex", "(IIIJI)V", "drawElementsBaseVertex");
        add(methods, "GL32C", "glDrawElementsBaseVertex", "(IIIJI)V", "drawElementsBaseVertex");
        add(methods, "GL32", "glDrawElementsInstancedBaseVertex", "(IIIJII)V", "drawElementsInstancedBaseVertex");
        add(methods, "GL32C", "glDrawElementsInstancedBaseVertex", "(IIIJII)V", "drawElementsInstancedBaseVertex");
        add(methods, "GL32", "nglMultiDrawElementsBaseVertex", "(IJIJIJ)V", "multiDrawElementsBaseVertex");
        add(methods, "GL32C", "nglMultiDrawElementsBaseVertex", "(IJIJIJ)V", "multiDrawElementsBaseVertex");
        add(methods, "GL31", "glGetUniformBlockIndex", "(ILjava/lang/CharSequence;)I", "getUniformBlockIndex");
        add(methods, "GL31C", "glGetUniformBlockIndex", "(ILjava/lang/CharSequence;)I", "getUniformBlockIndex");
        add(methods, "GL31", "glGetActiveUniformBlockName", "(II)Ljava/lang/String;", "getActiveUniformBlockName");
        add(methods, "GL31C", "glGetActiveUniformBlockName", "(II)Ljava/lang/String;", "getActiveUniformBlockName");
        add(methods, "GL31", "glUniformBlockBinding", "(III)V", "uniformBlockBinding");
        add(methods, "GL31C", "glUniformBlockBinding", "(III)V", "uniformBlockBinding");
        for (String owner : new String[] {"GL32", "GL32C"}) {
            add(methods, owner, "glFenceSync", "(II)J", "fenceSync");
            add(methods, owner, "glClientWaitSync", "(JIJ)I", "clientWaitSync");
            add(methods, owner, "glDeleteSync", "(J)V", "deleteSync");
        }
        add(methods, "GL33C", "glGenSamplers", "()I", "genSampler");
        add(methods, "GL33C", "glBindSampler", "(II)V", "bindSampler");
        add(methods, "GL33C", "glDeleteSamplers", "(I)V", "deleteSampler");
        add(methods, "GL33C", "glSamplerParameteri", "(III)V", "samplerParameteri");
        add(methods, "GL33C", "glSamplerParameterf", "(IIF)V", "samplerParameterf");
        return methods;
    }

    private static Set<String> noOps() {
        Set<String> methods = new HashSet<>();
        noop(methods, "GL11", "glGetTexLevelParameteri", "(III)I");
        noop(methods, "GL32C", "glBeginQuery", "(II)V");
        for (String owner : new String[] {"GL15", "GL15C", "GL32", "GL32C"}) {
            noop(methods, owner, "nglDeleteQueries", "(IJ)V");
            noop(methods, owner, "glDeleteQueries", "(Ljava/nio/IntBuffer;)V");
            noop(methods, owner, "glDeleteQueries", "(I)V");
            noop(methods, owner, "glDeleteQueries", "([I)V");
        }
        noop(methods, "GL32C", "glEndQuery", "(I)V");
        noop(methods, "GL32C", "glGetQueryObjecti", "(II)I");
        noop(methods, "ARBTimerQuery", "glGetQueryObjecti64", "(II)J");
        for (String owner : new String[] {
                "ARBBufferStorage", "ARBDebugOutput", "ARBDirectStateAccess",
                "ARBVertexAttribBinding", "EXTDebugLabel", "KHRDebug"
        }) {
            // Added below from the exact official-client reference surface.
        }
        noop(methods, "ARBBufferStorage", "glBufferStorage", "(IJI)V");
        noop(methods, "ARBBufferStorage", "glBufferStorage", "(ILjava/nio/ByteBuffer;I)V");
        noop(methods, "ARBDebugOutput", "glDebugMessageCallbackARB", "(Lorg/lwjgl/opengl/GLDebugMessageARBCallbackI;J)V");
        noop(methods, "ARBDebugOutput", "glDebugMessageControlARB", "(III[IZ)V");
        noop(methods, "ARBDirectStateAccess", "glBlitNamedFramebuffer", "(IIIIIIIIIIII)V");
        noop(methods, "ARBDirectStateAccess", "glCopyNamedBufferSubData", "(IIJJJ)V");
        noop(methods, "ARBDirectStateAccess", "glCreateBuffers", "()I");
        noop(methods, "ARBDirectStateAccess", "glCreateFramebuffers", "()I");
        noop(methods, "ARBDirectStateAccess", "glFlushMappedNamedBufferRange", "(IJJ)V");
        noop(methods, "ARBDirectStateAccess", "glMapNamedBufferRange", "(IJJI)Ljava/nio/ByteBuffer;");
        noop(methods, "ARBDirectStateAccess", "glNamedBufferData", "(IJI)V");
        noop(methods, "ARBDirectStateAccess", "glNamedBufferData", "(ILjava/nio/ByteBuffer;I)V");
        noop(methods, "ARBDirectStateAccess", "glNamedBufferStorage", "(IJI)V");
        noop(methods, "ARBDirectStateAccess", "glNamedBufferStorage", "(ILjava/nio/ByteBuffer;I)V");
        noop(methods, "ARBDirectStateAccess", "glNamedBufferSubData", "(IJLjava/nio/ByteBuffer;)V");
        noop(methods, "ARBDirectStateAccess", "glNamedFramebufferTexture", "(IIII)V");
        noop(methods, "ARBDirectStateAccess", "glUnmapNamedBuffer", "(I)Z");
        noop(methods, "EXTDebugLabel", "glLabelObjectEXT", "(IILjava/lang/CharSequence;)V");
        noop(methods, "KHRDebug", "glDebugMessageCallback", "(Lorg/lwjgl/opengl/GLDebugMessageCallbackI;J)V");
        noop(methods, "KHRDebug", "glDebugMessageControl", "(III[IZ)V");
        noop(methods, "KHRDebug", "glObjectLabel", "(IILjava/lang/CharSequence;)V");
        noop(methods, "KHRDebug", "glPopDebugGroup", "()V");
        noop(methods, "KHRDebug", "glPushDebugGroup", "(IILjava/lang/CharSequence;)V");
        return methods;
    }

    private static void add(Map<String, String> methods, String owner, String name, String desc, String target) {
        methods.put("org/lwjgl/opengl/" + owner + "#" + name + desc, target);
    }

    private static void noop(Set<String> methods, String owner, String name, String desc) {
        methods.add("org/lwjgl/opengl/" + owner + "#" + name + desc);
    }

    private static void delegate(MethodNode method, String target) {
        delegate(method, BROWSER, target);
    }

    private static void delegate(MethodNode method, String owner, String target) {
        InsnList code = new InsnList();
        Type[] arguments = Type.getArgumentTypes(method.desc);
        int local = 0;
        for (Type argument : arguments) {
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, target, method.desc, false));
        Type result = Type.getReturnType(method.desc);
        code.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        replace(method, code, Math.max(6, local));
    }

    private static void replaceDefault(MethodNode method) {
        InsnList code = new InsnList();
        Type result = Type.getReturnType(method.desc);
        switch (result.getSort()) {
            case Type.VOID -> code.add(new InsnNode(Opcodes.RETURN));
            case Type.LONG -> {
                code.add(new InsnNode(Opcodes.LCONST_0));
                code.add(new InsnNode(Opcodes.LRETURN));
            }
            case Type.FLOAT -> {
                code.add(new InsnNode(Opcodes.FCONST_0));
                code.add(new InsnNode(Opcodes.FRETURN));
            }
            case Type.DOUBLE -> {
                code.add(new InsnNode(Opcodes.DCONST_0));
                code.add(new InsnNode(Opcodes.DRETURN));
            }
            case Type.OBJECT, Type.ARRAY -> {
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new InsnNode(Opcodes.ARETURN));
            }
            default -> {
                if (result.getSort() == Type.BOOLEAN
                        && (method.name.startsWith("glUnmap"))) {
                    code.add(new InsnNode(Opcodes.ICONST_1));
                } else {
                    code.add(new InsnNode(Opcodes.ICONST_0));
                }
                code.add(new InsnNode(Opcodes.IRETURN));
            }
        }
        replace(method, code, 2);
    }

    private static void replace(MethodNode method, InsnList code, int stack) {
        method.access &= ~(Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT);
        method.instructions = code;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxStack = stack;
        method.maxLocals = Math.max(method.maxLocals, Type.getArgumentsAndReturnSizes(method.desc) >> 2);
    }

    private static ClassNode read(String jarPath, String entryName) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath)) {
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException(entryName + " not found");
            }
            ClassNode node = new ClassNode();
            try (var stream = jar.getInputStream(entry)) {
                new ClassReader(stream.readAllBytes()).accept(node, 0);
            }
            return node;
        }
    }

    private static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
