package dev.gaius.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/**
 * Symbols of a named (26.x) Minecraft client jar, found by probing the jar's contents rather
 * than by comparing version strings. MinecraftClientPatcher and Minecraft262BrowserPatcher use
 * it to serve 26.2 and 26.3 from one patch set; on a 26.2 jar every lookup returns the 26.2 name
 * unchanged.
 *
 * <h2>Probed facts</h2>
 * <ul>
 *   <li>{@link #renderApi}: {@code com/mojang/blaze3d/systems/GpuDevice} (26.2) or
 *       {@code com/mojang/renderpearl/api/device/GpuDevice} (26.3).</li>
 *   <li>Render type table: every top-level {@code com/mojang/blaze3d} class that 26.3 moved into
 *       {@code com/mojang/renderpearl} (or removed). Keys are 26.2 internal names; nested classes
 *       follow their outer class. {@link #renderType}, {@link #renderTypeRemoved},
 *       {@link #isInterface} and {@link #invokeOpcode} answer for this jar.</li>
 *   <li>{@link #uncheckedAutoCloseable}: {@code com/mojang/renderpearl/util/UncheckedAutoCloseable}
 *       is present.</li>
 *   <li>{@link #authlib}: {@code yggdrasil} (authlib 9) or {@code services} (authlib 10) package,
 *       from the client's own references and, when an authlib jar is known, from that jar.</li>
 *   <li>{@link #inputBackend}: GLFW or SDL, from the references of the vanilla input
 *       classes.</li>
 *   <li>{@link #inputKeys}: {@code InputConstants.KEY_*} ConstantValues (GLFW key codes on 26.2,
 *       SDL scancodes on 26.3).</li>
 *   <li>{@link #vertexSemantics}: {@code BufferBuilder.*_SEMANTIC_ID} ConstantValues (26.3 added
 *       UV3=5 and moved NORMAL to 6).</li>
 * </ul>
 *
 * <h2>Self-consistency</h2>
 * {@link #probe} throws {@link IllegalStateException} on any mixed state: both render API
 * packages, a moved or removed 26.2 render class still present next to renderpearl, a renderpearl
 * name missing from a renderpearl jar, one of the {@link #CLASS_TO_INTERFACE_KEYS} types that is
 * not an interface in a renderpearl jar, {@code UncheckedAutoCloseable} without renderpearl,
 * references to both GLFW and SDL (or SDLEventHandler without SDL references), key codes that do
 * not match the input backend, references to both authlib packages, an authlib jar of the other
 * flavour, BufferBuilder fields typed with the other render package, or semantic ids that are not
 * a dense 0..n-1 range.
 *
 * <p>The authlib jar is taken from {@link #probe(String, String)}, else the system property
 * {@code gaius.authlib.jar}, else the environment variable {@code GAIUS_AUTHLIB_JAR}, else the
 * {@code authlib} entry of a {@code classpath.txt} next to the client jar (the layout of
 * {@code port/work/<id>/}). Without one, the flavour comes from the client references alone.
 *
 * <p>{@code java dev.gaius.tools.ModernSymbols CLIENT_JAR [AUTHLIB_JAR]} prints the probe as
 * JSON.
 */
public final class ModernSymbols {
    public enum RenderApi { BLAZE3D, RENDERPEARL }

    public enum AuthlibFlavour { YGGDRASIL, SERVICES }

    public enum InputBackend { GLFW, SDL }

    public static final String BLAZE3D_PREFIX = "com/mojang/blaze3d/";
    public static final String RENDERPEARL_PREFIX = "com/mojang/renderpearl/";
    public static final String BLAZE3D_GPU_DEVICE = "com/mojang/blaze3d/systems/GpuDevice";
    public static final String RENDERPEARL_GPU_DEVICE = "com/mojang/renderpearl/api/device/GpuDevice";
    public static final String UNCHECKED_AUTO_CLOSEABLE =
            "com/mojang/renderpearl/util/UncheckedAutoCloseable";
    public static final String INPUT_CONSTANTS = "com/mojang/blaze3d/platform/InputConstants";
    public static final String SDL_EVENT_HANDLER = "com/mojang/blaze3d/platform/SDLEventHandler";
    public static final String BUFFER_BUILDER = "com/mojang/blaze3d/vertex/BufferBuilder";
    public static final String VERTEX_FORMAT = "com/mojang/blaze3d/vertex/VertexFormat";
    public static final String VERTEX_FORMAT_ELEMENT = "com/mojang/blaze3d/vertex/VertexFormatElement";
    public static final String AUTHLIB_YGGDRASIL_PREFIX = "com/mojang/authlib/yggdrasil/";
    public static final String AUTHLIB_SERVICES_PREFIX = "com/mojang/authlib/services/";
    public static final String AUTHLIB_JAR_PROPERTY = "gaius.authlib.jar";
    public static final String AUTHLIB_JAR_ENV = "GAIUS_AUTHLIB_JAR";

    /** The eight 26.2 render classes that are interfaces in 26.3 (render-backend notes V.2). */
    public static final List<String> CLASS_TO_INTERFACE_KEYS = List.of(
            "com/mojang/blaze3d/buffers/GpuBuffer",
            "com/mojang/blaze3d/systems/CommandEncoder",
            "com/mojang/blaze3d/systems/GpuDevice",
            "com/mojang/blaze3d/systems/GpuSurface",
            "com/mojang/blaze3d/systems/RenderPass",
            "com/mojang/blaze3d/textures/GpuSampler",
            "com/mojang/blaze3d/textures/GpuTexture",
            "com/mojang/blaze3d/textures/GpuTextureView");

    /** Vanilla classes whose references decide the input backend. */
    static final List<String> INPUT_ANCHORS = List.of(
            INPUT_CONSTANTS,
            "com/mojang/blaze3d/platform/Window",
            "net/minecraft/client/MouseHandler",
            "net/minecraft/client/KeyboardHandler");

    /** Vanilla classes whose references decide the authlib flavour. */
    static final List<String> AUTHLIB_ANCHORS = List.of(
            "net/minecraft/server/Services",
            "net/minecraft/util/SignatureValidator");

    private static final String AUTHLIB9_SESSION =
            "com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService";
    private static final String AUTHLIB10_SESSION =
            "com/mojang/authlib/services/MinecraftServicesSessionService";

    /**
     * 26.2 top-level render class (relative to {@code com/mojang/blaze3d/}) &rarr; 26.3 class
     * (relative to {@code com/mojang/renderpearl/}), or {@code null} when 26.3 removed it. Every
     * top-level 26.2 blaze3d class absent from 26.3 is listed (scratchpad rename-map.txt,
     * re-checked against both jars); blaze3d classes that 26.3 kept in place are not.
     */
    private static final String[] RENDER_RENAMES = {
            "GLFWErrorCapture", null,
            "GLFWErrorScope", null,
            "GpuDeviceLossException", "api/device/GpuDeviceLossException",
            "GpuFormat", "api/GpuFormat",
            "GpuOutOfMemoryException", "api/device/GpuOutOfMemoryException",
            "IndexType", "api/pipeline/IndexType",
            "PrimitiveTopology", "api/pipeline/PrimitiveTopology",
            "buffers/GpuBuffer", "api/buffers/GpuBuffer",
            "buffers/GpuBufferSlice", "api/buffers/GpuBufferSlice",
            "buffers/GpuFence", "api/commands/GpuFence",
            "opengl/BufferStorage", "backend/opengl/BufferStorage",
            "opengl/DirectStateAccess", "backend/opengl/DirectStateAccess",
            "opengl/FrameBufferAttachment", "backend/opengl/FrameBufferAttachment",
            "opengl/FrameBufferCache", "backend/opengl/FrameBufferCache",
            "opengl/GlBackend", "backend/opengl/GlBackend",
            "opengl/GlBuffer", "backend/opengl/GlBuffer",
            "opengl/GlCommandEncoder", "backend/opengl/GlCommandEncoder",
            "opengl/GlConst", "backend/opengl/GlConst",
            "opengl/GlDebug", "backend/opengl/GlDebug",
            "opengl/GlDebugLabel", "backend/opengl/GlDebugLabel",
            "opengl/GlDevice", "backend/opengl/GlDevice",
            "opengl/GlFence", "backend/opengl/GlFence",
            "opengl/GlHeuristics", "backend/opengl/GlHeuristics",
            "opengl/GlProgram", "backend/opengl/GlProgram",
            "opengl/GlQueryPool", "backend/opengl/GlQueryPool",
            "opengl/GlRenderPass", "backend/opengl/GlRenderPass",
            "opengl/GlRenderPipeline", "backend/opengl/GlRenderPipeline",
            "opengl/GlSampler", "backend/opengl/GlSampler",
            "opengl/GlShaderModule", "backend/opengl/GlShaderModule",
            "opengl/GlStateManager", "backend/opengl/GlStateManager",
            "opengl/GlSurface", "backend/opengl/GlSurface",
            "opengl/GlTexture", "backend/opengl/GlTexture",
            "opengl/GlTextureView", "backend/opengl/GlTextureView",
            "opengl/GlTransientMemory", "backend/opengl/GlTransientMemory",
            "opengl/GlUtil", "backend/opengl/GlUtil",
            "opengl/Uniform", "backend/opengl/Uniform",
            "opengl/VertexArrayCache", null,
            "pipeline/BindGroupLayout", "api/pipeline/BindGroupLayout",
            "pipeline/BlendEquation", "api/pipeline/BlendEquation",
            "pipeline/BlendFunction", "api/pipeline/BlendFunction",
            "pipeline/ColorTargetState", "api/pipeline/ColorTargetState",
            "pipeline/CompiledRenderPipeline", "api/pipeline/CompiledRenderPipeline",
            "pipeline/DepthStencilState", "api/pipeline/DepthStencilState",
            "pipeline/RenderPipeline", "api/pipeline/RenderPipeline",
            "platform/BlendFactor", "api/pipeline/BlendFactor",
            "platform/BlendOp", "api/pipeline/BlendOp",
            "platform/CompareOp", "api/pipeline/CompareOp",
            "platform/GLX", null,
            "platform/PolygonMode", "api/pipeline/PolygonMode",
            "preprocessor/GlslPreprocessor", null,
            "shaders/GpuDebugOptions", "api/device/GpuDebugOptions",
            "shaders/ShaderSource", "api/pipeline/ShaderSource",
            "shaders/ShaderType", "api/pipeline/ShaderType",
            "shaders/UniformType", "api/pipeline/UniformType",
            "systems/BackendCreationException", "api/device/BackendCreationException",
            "systems/CommandEncoder", "api/commands/CommandEncoder",
            "systems/CommandEncoderBackend", "backend/api/CommandEncoderBackend",
            "systems/DeviceFeatures", "api/device/DeviceFeatures",
            "systems/DeviceInfo", "api/device/DeviceInfo",
            "systems/DeviceLimits", "api/device/DeviceLimits",
            "systems/DeviceType", "api/device/DeviceType",
            "systems/GpuBackend", "api/device/GpuBackend",
            "systems/GpuDevice", "api/device/GpuDevice",
            "systems/GpuDeviceBackend", "backend/api/GpuDeviceBackend",
            "systems/GpuQuery", "api/commands/GpuQuery",
            "systems/GpuQueryPool", "api/commands/GpuQueryPool",
            "systems/GpuSurface", "api/device/GpuSurface",
            "systems/GpuSurfaceBackend", "backend/api/GpuSurfaceBackend",
            "systems/HintsAndWorkarounds", "api/device/HintsAndWorkarounds",
            "systems/RenderPass", "api/commands/RenderPass",
            "systems/RenderPassBackend", "backend/api/RenderPassBackend",
            "systems/RenderPassDescriptor", "api/commands/RenderPassDescriptor",
            "systems/SurfaceException", "api/device/SurfaceException",
            "systems/TracyGpuProfiler", "frontend/TracyGpuProfiler",
            "systems/TransientMemory", "api/buffers/TransientMemory",
            "textures/AddressMode", "api/textures/AddressMode",
            "textures/FilterMode", "api/textures/FilterMode",
            "textures/GpuSampler", "api/textures/GpuSampler",
            "textures/GpuTexture", "api/textures/GpuTexture",
            "textures/GpuTextureView", "api/textures/GpuTextureView",
            "util/TransientBlockAllocator", "backend/util/TransientBlockAllocator",
            "vertex/VertexFormat", "api/vertex/VertexFormat",
            "vertex/VertexFormatElement", "api/vertex/VertexFormatElement",
            "vulkan/Destroyable", "backend/vulkan/Destroyable",
            "vulkan/DestructionQueue", "backend/vulkan/DestructionQueue",
            "vulkan/VulkanBackend", "backend/vulkan/VulkanBackend",
            "vulkan/VulkanBindGroupLayout", null,
            "vulkan/VulkanCommandEncoder", "backend/vulkan/VulkanCommandEncoder",
            "vulkan/VulkanCommandPool", "backend/vulkan/VulkanCommandPool",
            "vulkan/VulkanConst", "backend/vulkan/VulkanConst",
            "vulkan/VulkanDebug", "backend/vulkan/VulkanDebug",
            "vulkan/VulkanDevice", "backend/vulkan/VulkanDevice",
            "vulkan/VulkanGpuBuffer", "backend/vulkan/VulkanGpuBuffer",
            "vulkan/VulkanGpuSampler", "backend/vulkan/VulkanGpuSampler",
            "vulkan/VulkanGpuSurface", "backend/vulkan/VulkanGpuSurface",
            "vulkan/VulkanGpuTexture", "backend/vulkan/VulkanGpuTexture",
            "vulkan/VulkanGpuTextureView", "backend/vulkan/VulkanGpuTextureView",
            "vulkan/VulkanInstance", "backend/vulkan/VulkanInstance",
            "vulkan/VulkanPhysicalDevice", "backend/vulkan/VulkanPhysicalDevice",
            "vulkan/VulkanQueryPool", "backend/vulkan/VulkanQueryPool",
            "vulkan/VulkanQueue", "backend/vulkan/VulkanQueue",
            "vulkan/VulkanRenderPass", "backend/vulkan/VulkanRenderPass",
            "vulkan/VulkanRenderPipeline", "backend/vulkan/VulkanRenderPipeline",
            "vulkan/VulkanTransientMemory", "backend/vulkan/VulkanTransientMemory",
            "vulkan/VulkanUtils", "backend/vulkan/VulkanUtils",
            "vulkan/checkpoints/AbstractCheckpointStorage", "backend/vulkan/checkpoints/AbstractCheckpointStorage",
            "vulkan/checkpoints/AmdCheckpointExtension", "backend/vulkan/checkpoints/AmdCheckpointExtension",
            "vulkan/checkpoints/CheckpointExtension", "backend/vulkan/checkpoints/CheckpointExtension",
            "vulkan/checkpoints/NoopCheckpointExtension", "backend/vulkan/checkpoints/NoopCheckpointExtension",
            "vulkan/checkpoints/NvidiaCheckpointExtension", "backend/vulkan/checkpoints/NvidiaCheckpointExtension",
            "vulkan/glsl/GlslCompiler", "frontend/shaders/GlslCompiler",
            "vulkan/glsl/IntermediaryShaderModule", null,
            "vulkan/glsl/ShaderCompileException", "util/ShaderCompileException",
            "vulkan/glsl/SpvSampler", null,
            "vulkan/glsl/SpvUniformBuffer", null,
            "vulkan/glsl/SpvVariable", null,
            "vulkan/glsl/SpvcUtil", null,
            "vulkan/init/VulkanFeature", "backend/vulkan/init/VulkanFeature",
            "vulkan/init/VulkanPNextStruct", "backend/vulkan/init/VulkanPNextStruct",
    };

    /**
     * authlib 9 class &rarr; authlib 10 class, or {@code null} when authlib 10 removed it. Only
     * renames the patchers need (server-network notes section 7); other {@code yggdrasil} names
     * throw so a new user has to add and verify its mapping. Nested classes follow their outer
     * class; authlib classes outside {@code yggdrasil/} did not move.
     */
    private static final String[] AUTHLIB_RENAMES = {
            "yggdrasil/FriendsService", "services/FriendsService",
            "yggdrasil/ProfileResult", "services/ProfileResult",
            "yggdrasil/ServicesKeyInfo", "services/ServicesKeyInfo",
            "yggdrasil/ServicesKeySet", "services/ServicesKeySet",
            "yggdrasil/ServicesKeyType", "services/ServicesKeyType",
            "yggdrasil/TextureUrlChecker", null,
            "yggdrasil/YggdrasilAuthenticationService", null,
            "yggdrasil/YggdrasilMinecraftSessionService", "services/MinecraftServicesSessionService",
            "yggdrasil/YggdrasilServicesKeyInfo", "services/MinecraftServicesKeyInfo",
            "yggdrasil/response/MinecraftTexturesPayload", "services/response/MinecraftTexturesPayload",
    };

    private static final Map<String, String> RENDER_TABLE = table(
            RENDER_RENAMES, BLAZE3D_PREFIX, RENDERPEARL_PREFIX);
    private static final Map<String, String> AUTHLIB_TABLE = table(
            AUTHLIB_RENAMES, "com/mojang/authlib/", "com/mojang/authlib/");
    private static final Pattern OBJECT_TYPE = Pattern.compile("L([^;<>]+)([;<])");
    private static final Map<String, ModernSymbols> CACHE = new HashMap<>();

    /** The probed client jar. */
    public final String jar;
    public final RenderApi renderApi;
    public final boolean uncheckedAutoCloseable;
    public final AuthlibFlavour authlib;
    /** The authlib jar that confirmed {@link #authlib}, or {@code null}. */
    public final String authlibJar;
    public final InputBackend inputBackend;
    /** {@code InputConstants.KEY_*} ConstantValues, by field name. */
    public final Map<String, Integer> inputKeys;
    /** All {@code static final int} ConstantValues of InputConstants, by field name. */
    public final Map<String, Integer> inputConstants;
    /** {@code BufferBuilder.<NAME>_SEMANTIC_ID} ConstantValues by NAME; empty when absent. */
    public final Map<String, Integer> vertexSemantics;

    private final Set<String> entries;
    private final Map<String, String> presentRenderTypes;
    private final Set<String> removedRenderTypes;
    private final Map<String, Boolean> interfaces;

    private ModernSymbols(String jar, RenderApi renderApi, boolean uncheckedAutoCloseable,
            AuthlibFlavour authlib, String authlibJar, InputBackend inputBackend,
            Map<String, Integer> inputConstants, Map<String, Integer> vertexSemantics,
            Set<String> entries, Map<String, String> presentRenderTypes,
            Set<String> removedRenderTypes, Map<String, Boolean> interfaces) {
        this.jar = jar;
        this.renderApi = renderApi;
        this.uncheckedAutoCloseable = uncheckedAutoCloseable;
        this.authlib = authlib;
        this.authlibJar = authlibJar;
        this.inputBackend = inputBackend;
        this.inputConstants = Collections.unmodifiableMap(new TreeMap<>(inputConstants));
        Map<String, Integer> keys = new TreeMap<>();
        inputConstants.forEach((name, value) -> {
            if (name.startsWith("KEY_")) {
                keys.put(name, value);
            }
        });
        this.inputKeys = Collections.unmodifiableMap(keys);
        this.vertexSemantics = Collections.unmodifiableMap(new LinkedHashMap<>(vertexSemantics));
        this.entries = entries;
        this.presentRenderTypes = presentRenderTypes;
        this.removedRenderTypes = removedRenderTypes;
        this.interfaces = interfaces;
    }

    /** Probes {@code jar}, looking up the authlib jar as described in the class comment. */
    public static ModernSymbols probe(String jar) throws IOException {
        return probe(jar, null);
    }

    /** Probes {@code jar}, confirming the authlib flavour against {@code authlibJar} if given. */
    public static ModernSymbols probe(String jar, String authlibJar) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            return probe(jar, zip, authlibJar);
        }
    }

    /** {@link #probe(String)}, memoized per jar path for this JVM. */
    public static synchronized ModernSymbols cached(String jar) throws IOException {
        String key = Path.of(jar).toAbsolutePath().normalize().toString();
        ModernSymbols symbols = CACHE.get(key);
        if (symbols == null) {
            symbols = probe(jar);
            CACHE.put(key, symbols);
        }
        return symbols;
    }

    public boolean renderpearl() {
        return renderApi == RenderApi.RENDERPEARL;
    }

    public boolean sdl() {
        return inputBackend == InputBackend.SDL;
    }

    public boolean authlibServices() {
        return authlib == AuthlibFlavour.SERVICES;
    }

    /** Every top-level 26.2 render class name that the table knows, in table order. */
    public static List<String> renderKeys() {
        return List.copyOf(RENDER_TABLE.keySet());
    }

    /** Whether {@code key} (a 26.2 name, nested classes allowed) is covered by the table. */
    public static boolean isRenderKey(String key) {
        return RENDER_TABLE.containsKey(outerOf(key));
    }

    /**
     * The name of the 26.2 render class {@code key} in this jar. On 26.2 this is {@code key}
     * itself. Throws when the key is unknown, removed in this jar, or absent from this jar.
     */
    public String renderType(String key) {
        String name = mapRender(key);
        if (!entries.contains(name + ".class")) {
            throw new IllegalStateException("Render type " + key + " -> " + name
                    + " is not present in " + jar);
        }
        return name;
    }

    /** Whether {@code key} resolves to a class present in this jar. */
    public boolean renderTypePresent(String key) {
        if (!isRenderKey(key) || renderTypeRemoved(key)) {
            return false;
        }
        return entries.contains(mapRender(key) + ".class");
    }

    /**
     * Whether this jar's render API removed {@code key} without a counterpart (for example
     * {@code com/mojang/blaze3d/platform/GLX} on 26.3). Always {@code false} on 26.2.
     */
    public boolean renderTypeRemoved(String key) {
        requireRenderKey(key);
        return renderpearl() && removedRenderTypes.contains(outerOf(key));
    }

    /** Whether the class {@code key} resolves to is an interface in this jar. */
    public synchronized boolean isInterface(String key) {
        String name = renderType(key);
        Boolean known = interfaces.get(name);
        if (known == null) {
            known = readInterfaceFlag(name);
            interfaces.put(name, known);
        }
        return known;
    }

    /** {@code INVOKEINTERFACE} for interface owners, {@code INVOKEVIRTUAL} otherwise. */
    public int invokeOpcode(String key) {
        return isInterface(key) ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
    }

    /**
     * Rewrites every render class of a descriptor or signature to this jar's names; blaze3d
     * classes outside the table (kept in place by 26.3) stay unchanged.
     */
    public String renderDesc(String descriptor) {
        return rewriteObjectTypes(descriptor, name -> isRenderKey(name) ? renderType(name) : name);
    }

    /**
     * The name of the authlib 9 class {@code key} for this jar's authlib. Classes outside
     * {@code com/mojang/authlib/yggdrasil/} are returned unchanged; unknown yggdrasil classes and
     * classes that authlib 10 removed throw.
     */
    public String authlibType(String key) {
        if (!key.startsWith(AUTHLIB_YGGDRASIL_PREFIX)) {
            return key;
        }
        String outer = outerOf(key);
        if (!AUTHLIB_TABLE.containsKey(outer)) {
            throw new IllegalArgumentException("authlib type " + key
                    + " has no verified authlib 10 mapping; add it to ModernSymbols");
        }
        if (authlib == AuthlibFlavour.YGGDRASIL) {
            return key;
        }
        String mapped = AUTHLIB_TABLE.get(outer);
        if (mapped == null) {
            throw new IllegalStateException("authlib type " + key + " was removed in authlib 10");
        }
        return mapped + key.substring(outer.length());
    }

    /** Rewrites every authlib 9 class of a descriptor or signature to this jar's authlib. */
    public String authlibDesc(String descriptor) {
        return rewriteObjectTypes(descriptor, this::authlibType);
    }

    /** {@code InputConstants.<name>} for this jar; throws when absent. */
    public int inputKey(String name) {
        Integer value = inputConstants.get(name);
        if (value == null) {
            throw new IllegalStateException("InputConstants." + name + " is not a constant in " + jar);
        }
        return value;
    }

    /** {@code BufferBuilder.<name>_SEMANTIC_ID} for this jar; throws when absent. */
    public int vertexSemantic(String name) {
        Integer value = vertexSemantics.get(name);
        if (value == null) {
            throw new IllegalStateException("BufferBuilder." + name + "_SEMANTIC_ID is not defined in "
                    + jar + " (known: " + vertexSemantics.keySet() + ")");
        }
        return value;
    }

    /** Whether the probed jar contains {@code entry} (a jar entry name). */
    public boolean hasEntry(String entry) {
        return entries.contains(entry);
    }

    /** One-line summary for patcher logs. */
    public String summary() {
        return "renderApi=" + renderApi + " uncheckedAutoCloseable=" + uncheckedAutoCloseable
                + " authlib=" + authlib + " authlibJar="
                + (authlibJar == null ? "-" : Path.of(authlibJar).getFileName())
                + " input=" + inputBackend + " vertexSemantics=" + vertexSemantics;
    }

    /** The probe as a JSON object (used by port/scripts/modern-symbols-smoke.mjs). */
    public String toJson() {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        field(out, "jar", jar).append(",\n");
        field(out, "renderApi", renderApi.name()).append(",\n");
        out.append("  \"uncheckedAutoCloseable\": ").append(uncheckedAutoCloseable).append(",\n");
        field(out, "authlib", authlib.name()).append(",\n");
        out.append("  \"authlibJar\": ").append(authlibJar == null ? "null" : quote(authlibJar))
                .append(",\n");
        field(out, "inputBackend", inputBackend.name()).append(",\n");
        out.append("  \"inputKeys\": ").append(intMap(inputKeys)).append(",\n");
        out.append("  \"inputConstants\": ").append(intMap(inputConstants)).append(",\n");
        out.append("  \"vertexSemantics\": ").append(intMap(vertexSemantics)).append(",\n");
        out.append("  \"renderTypes\": {");
        boolean first = true;
        for (String key : RENDER_TABLE.keySet()) {
            out.append(first ? "\n" : ",\n");
            first = false;
            out.append("    ").append(quote(key)).append(": ");
            if (renderTypeRemoved(key)) {
                out.append("{\"removed\": true}");
            } else if (!renderTypePresent(key)) {
                out.append("{\"absent\": true, \"name\": ").append(quote(mapRender(key))).append('}');
            } else {
                out.append("{\"name\": ").append(quote(renderType(key)))
                        .append(", \"interface\": ").append(isInterface(key)).append('}');
            }
        }
        out.append("\n  },\n");
        out.append("  \"authlibTypes\": {");
        first = true;
        for (String key : AUTHLIB_TABLE.keySet()) {
            out.append(first ? "\n" : ",\n");
            first = false;
            out.append("    ").append(quote(key)).append(": ");
            if (authlib == AuthlibFlavour.SERVICES && AUTHLIB_TABLE.get(key) == null) {
                out.append("null");
            } else {
                out.append(quote(authlibType(key)));
            }
        }
        out.append("\n  }\n}\n");
        return out.toString();
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("usage: ModernSymbols CLIENT_JAR [AUTHLIB_JAR]");
        }
        System.out.print(probe(args[0], args.length == 2 ? args[1] : null).toJson());
    }

    private static ModernSymbols probe(String jar, ZipFile zip, String explicitAuthlib)
            throws IOException {
        List<String> problems = new ArrayList<>();
        Set<String> entries = new LinkedHashSet<>();
        boolean renderpearlEntries = false;
        for (Enumeration<? extends ZipEntry> it = zip.entries(); it.hasMoreElements(); ) {
            String name = it.nextElement().getName();
            entries.add(name);
            if (name.startsWith(RENDERPEARL_PREFIX) && name.endsWith(".class")) {
                renderpearlEntries = true;
            }
        }
        Set<String> view = Collections.unmodifiableSet(entries);

        // Render API.
        boolean blazeDevice = entries.contains(BLAZE3D_GPU_DEVICE + ".class");
        boolean pearlDevice = entries.contains(RENDERPEARL_GPU_DEVICE + ".class");
        RenderApi renderApi;
        if (blazeDevice == pearlDevice) {
            throw new IllegalStateException("Cannot determine the render API of " + jar + ": "
                    + BLAZE3D_GPU_DEVICE + (blazeDevice ? " present" : " absent") + ", "
                    + RENDERPEARL_GPU_DEVICE + (pearlDevice ? " present" : " absent"));
        }
        renderApi = pearlDevice ? RenderApi.RENDERPEARL : RenderApi.BLAZE3D;
        boolean unchecked = entries.contains(UNCHECKED_AUTO_CLOSEABLE + ".class");
        Map<String, String> present = new LinkedHashMap<>();
        Set<String> removed = new LinkedHashSet<>();
        if (renderApi == RenderApi.BLAZE3D) {
            if (renderpearlEntries) {
                problems.add("blaze3d jar also contains " + RENDERPEARL_PREFIX + " classes");
            }
            if (unchecked) {
                problems.add(UNCHECKED_AUTO_CLOSEABLE + " present without the renderpearl API");
            }
            for (String key : RENDER_TABLE.keySet()) {
                if (entries.contains(key + ".class")) {
                    present.put(key, key);
                }
            }
        } else {
            for (Map.Entry<String, String> rename : RENDER_TABLE.entrySet()) {
                String key = rename.getKey();
                String target = rename.getValue();
                if (entries.contains(key + ".class")) {
                    problems.add("26.2 render class " + key + " is still present next to renderpearl");
                }
                if (target == null) {
                    removed.add(key);
                } else if (entries.contains(target + ".class")) {
                    present.put(key, target);
                } else {
                    problems.add("renderpearl class " + target + " (for " + key + ") is missing");
                }
            }
        }
        Map<String, Boolean> interfaces = new HashMap<>();
        for (String name : present.values()) {
            interfaces.put(name, (access(zip, name) & Opcodes.ACC_INTERFACE) != 0);
        }
        // The eight class-to-interface render types: renderpearl declares all of them as
        // interfaces, so a class there is a mixed jar whose invokeOpcode answers would not match
        // the 26.3 shape the modern patches are written for. blaze3d generations differ (1.21.11
        // declares CommandEncoder, GpuDevice and RenderPass as interfaces, 26.2 none of the
        // eight), so the blaze3d flags are read as they are.
        if (renderApi == RenderApi.RENDERPEARL) {
            for (String key : CLASS_TO_INTERFACE_KEYS) {
                String name = present.get(key);
                if (name != null && !interfaces.get(name)) {
                    problems.add("renderpearl class " + name + " (for " + key
                            + ") is not an interface");
                }
            }
        }

        // Input backend and InputConstants.
        boolean glfw = false;
        boolean sdl = false;
        for (String anchor : INPUT_ANCHORS) {
            ZipEntry entry = zip.getEntry(anchor + ".class");
            if (entry == null) {
                continue;
            }
            for (String utf8 : utf8Constants(zip, entry)) {
                glfw |= utf8.contains("org/lwjgl/glfw/");
                sdl |= utf8.contains("org/lwjgl/sdl/");
            }
        }
        boolean sdlEventHandler = entries.contains(SDL_EVENT_HANDLER + ".class");
        InputBackend inputBackend = null;
        if (glfw && sdl) {
            problems.add("input classes reference both org/lwjgl/glfw and org/lwjgl/sdl");
        } else if (!glfw && !sdl) {
            problems.add("input classes reference neither org/lwjgl/glfw nor org/lwjgl/sdl");
        } else {
            inputBackend = sdl ? InputBackend.SDL : InputBackend.GLFW;
            if (sdlEventHandler != sdl) {
                problems.add(SDL_EVENT_HANDLER + (sdlEventHandler ? " present" : " absent")
                        + " but the input backend is " + inputBackend);
            }
        }
        Map<String, Integer> inputConstants = new LinkedHashMap<>();
        ClassNode inputNode = readNode(zip, INPUT_CONSTANTS);
        if (inputNode == null) {
            problems.add(INPUT_CONSTANTS + " is missing");
        } else {
            for (FieldNode field : inputNode.fields) {
                if ((field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
                        == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
                        && field.desc.equals("I") && field.value instanceof Integer value) {
                    inputConstants.put(field.name, value);
                }
            }
            if (inputBackend != null) {
                int expectedA = inputBackend == InputBackend.GLFW ? 65 : 4;
                int expectedEscape = inputBackend == InputBackend.GLFW ? 256 : 41;
                Integer keyA = inputConstants.get("KEY_A");
                Integer keyEscape = inputConstants.get("KEY_ESCAPE");
                if (keyA == null || keyEscape == null || keyA != expectedA
                        || keyEscape != expectedEscape) {
                    problems.add("InputConstants KEY_A=" + keyA + " KEY_ESCAPE=" + keyEscape
                            + " do not match the " + inputBackend + " backend (expected "
                            + expectedA + "/" + expectedEscape + ")");
                }
            }
        }

        // BufferBuilder semantic ids and element owner.
        Map<String, Integer> semantics = new LinkedHashMap<>();
        ClassNode builder = readNode(zip, BUFFER_BUILDER);
        if (builder == null) {
            problems.add(BUFFER_BUILDER + " is missing");
        } else {
            List<Map.Entry<String, Integer>> ids = new ArrayList<>();
            for (FieldNode field : builder.fields) {
                if (field.name.endsWith("_SEMANTIC_ID") && field.desc.equals("I")
                        && (field.access & Opcodes.ACC_STATIC) != 0
                        && field.value instanceof Integer value) {
                    ids.add(Map.entry(field.name.substring(
                            0, field.name.length() - "_SEMANTIC_ID".length()), value));
                }
            }
            ids.sort(Map.Entry.comparingByValue());
            for (int index = 0; index < ids.size(); index++) {
                if (ids.get(index).getValue() != index) {
                    problems.add("BufferBuilder semantic ids are not a dense 0.." + (ids.size() - 1)
                            + " range: " + ids);
                    break;
                }
            }
            for (Map.Entry<String, Integer> id : ids) {
                semantics.put(id.getKey(), id.getValue());
            }
            if (!semantics.isEmpty()) {
                for (String required : List.of("POSITION", "COLOR", "UV0", "UV1", "UV2", "NORMAL")) {
                    if (!semantics.containsKey(required)) {
                        problems.add("BufferBuilder has no " + required + "_SEMANTIC_ID");
                    }
                }
            }
            for (FieldNode field : builder.fields) {
                String expected = null;
                if (field.name.equals("elements")) {
                    expected = "[L" + present.getOrDefault(VERTEX_FORMAT_ELEMENT, "?") + ";";
                } else if (field.name.equals("format")) {
                    expected = "L" + present.getOrDefault(VERTEX_FORMAT, "?") + ";";
                }
                if (expected != null && !field.desc.equals(expected)) {
                    problems.add("BufferBuilder." + field.name + " is " + field.desc
                            + ", expected " + expected + " for " + renderApi);
                }
            }
        }

        // authlib flavour.
        boolean yggdrasilRefs = false;
        boolean servicesRefs = false;
        for (String anchor : AUTHLIB_ANCHORS) {
            ZipEntry entry = zip.getEntry(anchor + ".class");
            if (entry == null) {
                continue;
            }
            for (String utf8 : utf8Constants(zip, entry)) {
                yggdrasilRefs |= utf8.contains(AUTHLIB_YGGDRASIL_PREFIX);
                servicesRefs |= utf8.contains(AUTHLIB_SERVICES_PREFIX);
            }
        }
        AuthlibFlavour clientFlavour = null;
        if (yggdrasilRefs && servicesRefs) {
            problems.add("client references both authlib yggdrasil and services packages");
        } else if (yggdrasilRefs || servicesRefs) {
            clientFlavour = servicesRefs ? AuthlibFlavour.SERVICES : AuthlibFlavour.YGGDRASIL;
        }
        String authlibJar = locateAuthlibJar(jar, explicitAuthlib);
        AuthlibFlavour jarFlavour = null;
        if (authlibJar != null) {
            try (ZipFile authlib = new ZipFile(authlibJar)) {
                boolean nine = authlib.getEntry(AUTHLIB9_SESSION + ".class") != null;
                boolean ten = authlib.getEntry(AUTHLIB10_SESSION + ".class") != null;
                if (nine == ten) {
                    problems.add("authlib jar " + authlibJar + " has "
                            + (nine ? "both " : "neither ") + AUTHLIB9_SESSION + " and "
                            + AUTHLIB10_SESSION);
                } else {
                    jarFlavour = ten ? AuthlibFlavour.SERVICES : AuthlibFlavour.YGGDRASIL;
                    // Older authlib 9-style jars (1.21.11 ships authlib 7) lack some of the
                    // yggdrasil keys; only the services mapping targets must all exist.
                    for (Map.Entry<String, String> rename : AUTHLIB_TABLE.entrySet()) {
                        if (jarFlavour != AuthlibFlavour.SERVICES) {
                            break;
                        }
                        String target = rename.getValue();
                        if (target != null && authlib.getEntry(target + ".class") == null) {
                            problems.add("authlib jar " + authlibJar + " lacks " + target);
                        }
                        if (authlib.getEntry(rename.getKey() + ".class") != null) {
                            problems.add("authlib 10 jar " + authlibJar + " still has "
                                    + rename.getKey());
                        }
                    }
                }
            }
        }
        AuthlibFlavour authlib = clientFlavour != null ? clientFlavour : jarFlavour;
        if (clientFlavour != null && jarFlavour != null && clientFlavour != jarFlavour) {
            problems.add("client references authlib " + clientFlavour + " but " + authlibJar
                    + " is " + jarFlavour);
        }
        if (authlib == null && problems.isEmpty()) {
            problems.add("cannot determine the authlib flavour: no references in "
                    + AUTHLIB_ANCHORS + " and no authlib jar");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("ModernSymbols: inconsistent client jar " + jar
                    + ":\n  - " + String.join("\n  - ", problems));
        }
        return new ModernSymbols(jar, renderApi, unchecked, authlib,
                jarFlavour != null ? authlibJar : null, inputBackend, inputConstants, semantics,
                view, Collections.unmodifiableMap(present), Collections.unmodifiableSet(removed),
                interfaces);
    }

    private String mapRender(String key) {
        requireRenderKey(key);
        String outer = outerOf(key);
        if (renderApi == RenderApi.BLAZE3D) {
            return key;
        }
        String mapped = RENDER_TABLE.get(outer);
        if (mapped == null) {
            throw new IllegalStateException("Render type " + key + " was removed in " + jar);
        }
        return mapped + key.substring(outer.length());
    }

    private static void requireRenderKey(String key) {
        if (key == null || !RENDER_TABLE.containsKey(outerOf(key))) {
            throw new IllegalArgumentException("Not a moved 26.2 render class: " + key
                    + " (keys are 26.2 internal names such as " + BLAZE3D_GPU_DEVICE + ")");
        }
    }

    private boolean readInterfaceFlag(String name) {
        try (ZipFile zip = new ZipFile(jar)) {
            return (access(zip, name) & Opcodes.ACC_INTERFACE) != 0;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read " + name + " from " + jar, exception);
        }
    }

    private static int access(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name + ".class");
        if (entry == null) {
            throw new IllegalStateException(name + ".class is missing from " + zip.getName());
        }
        try (InputStream input = zip.getInputStream(entry)) {
            return new ClassReader(input).getAccess();
        }
    }

    private static ClassNode readNode(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name + ".class");
        if (entry == null) {
            return null;
        }
        ClassNode node = new ClassNode();
        try (InputStream input = zip.getInputStream(entry)) {
            new ClassReader(input).accept(node,
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return node;
    }

    /** All CONSTANT_Utf8 strings of a class file (class names, descriptors, literals). */
    static List<String> utf8Constants(ZipFile zip, ZipEntry entry) throws IOException {
        byte[] bytes;
        try (InputStream input = zip.getInputStream(entry)) {
            bytes = input.readAllBytes();
        }
        return utf8Constants(bytes);
    }

    static List<String> utf8Constants(byte[] bytes) {
        if (bytes.length < 10 || (bytes[0] & 0xff) != 0xca || (bytes[1] & 0xff) != 0xfe
                || (bytes[2] & 0xff) != 0xba || (bytes[3] & 0xff) != 0xbe) {
            throw new IllegalStateException("not a class file");
        }
        int count = u2(bytes, 8);
        int offset = 10;
        List<String> strings = new ArrayList<>();
        for (int index = 1; index < count; index++) {
            int tag = bytes[offset] & 0xff;
            offset++;
            switch (tag) {
                case 1 -> {
                    int length = u2(bytes, offset);
                    // Modified UTF-8; class names and descriptors are ASCII.
                    strings.add(new String(bytes, offset + 2, length, StandardCharsets.ISO_8859_1));
                    offset += 2 + length;
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> offset += 4;
                case 5, 6 -> {
                    offset += 8;
                    index++;
                }
                case 7, 8, 16, 19, 20 -> offset += 2;
                case 15 -> offset += 3;
                default -> throw new IllegalStateException("unknown constant pool tag " + tag);
            }
        }
        return strings;
    }

    private static int u2(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static String locateAuthlibJar(String jar, String explicit) throws IOException {
        for (String candidate : new String[] {explicit, System.getProperty(AUTHLIB_JAR_PROPERTY),
                System.getenv(AUTHLIB_JAR_ENV)}) {
            if (candidate != null && !candidate.isBlank()) {
                Path path = PatchRegistry.hostPath(candidate);
                if (!Files.isRegularFile(path)) {
                    throw new IllegalStateException("authlib jar not found: " + candidate);
                }
                return path.toString();
            }
        }
        Path parent = Path.of(jar).toAbsolutePath().getParent();
        Path classpath = parent == null ? null : parent.resolve("classpath.txt");
        if (classpath == null || !Files.isRegularFile(classpath)) {
            return null;
        }
        String text = Files.readString(classpath, StandardCharsets.UTF_8).trim();
        for (String element : splitClasspath(text)) {
            String normalized = element.replace('\\', '/');
            if (normalized.contains("/com/mojang/authlib/") && normalized.endsWith(".jar")) {
                Path path = PatchRegistry.hostPath(element);
                if (Files.isRegularFile(path)) {
                    return path.toString();
                }
            }
        }
        return null;
    }

    /** Splits a {@code ;}- or {@code :}-separated classpath, keeping {@code C:/} drive letters. */
    static List<String> splitClasspath(String text) {
        List<String> parts = new ArrayList<>();
        if (text.contains(";")) {
            for (String part : text.split(";")) {
                if (!part.isBlank()) {
                    parts.add(part.trim());
                }
            }
            return parts;
        }
        String[] raw = text.split(":");
        for (int index = 0; index < raw.length; index++) {
            String part = raw[index];
            if (part.length() == 1 && Character.isLetter(part.charAt(0)) && index + 1 < raw.length
                    && (raw[index + 1].startsWith("/") || raw[index + 1].startsWith("\\"))) {
                part = part + ":" + raw[++index];
            }
            if (!part.isBlank()) {
                parts.add(part.trim());
            }
        }
        return parts;
    }

    private static String rewriteObjectTypes(
            String descriptor, java.util.function.UnaryOperator<String> mapper) {
        Matcher matcher = OBJECT_TYPE.matcher(descriptor);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String mapped = mapper.apply(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement("L" + mapped + matcher.group(2)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String outerOf(String name) {
        if (name == null) {
            return null;
        }
        int dollar = name.indexOf('$');
        return dollar < 0 ? name : name.substring(0, dollar);
    }

    private static Map<String, String> table(String[] pairs, String keyPrefix, String valuePrefix) {
        if (pairs.length % 2 != 0) {
            throw new IllegalStateException("odd rename table");
        }
        Map<String, String> table = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            String key = keyPrefix + pairs[index];
            String value = pairs[index + 1] == null ? null : valuePrefix + pairs[index + 1];
            if (key.contains("$") || (value != null && value.contains("$"))
                    || table.containsKey(key)) {
                throw new IllegalStateException("bad rename table entry " + key);
            }
            table.put(key, value);
        }
        return Collections.unmodifiableMap(table);
    }

    private static StringBuilder field(StringBuilder out, String name, String value) {
        return out.append("  ").append(quote(name)).append(": ").append(quote(value));
    }

    private static String intMap(Map<String, Integer> map) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            out.append(first ? "" : ", ").append(quote(entry.getKey())).append(": ")
                    .append(entry.getValue());
            first = false;
        }
        return out.append('}').toString();
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
