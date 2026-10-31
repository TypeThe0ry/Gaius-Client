#!/usr/bin/env node
import assert from "node:assert/strict";
import {mkdirSync, mkdtempSync, writeFileSync} from "node:fs";
import {spawnSync} from "node:child_process";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {compiledText, loadProfiles, relocateText, resolveSources, TARGETS} from "./sourcesets.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
const outIndex = process.argv.indexOf("--out");
assert.ok(outIndex > 0 && process.argv[outIndex + 1], "provide --out DIR for retained evidence");
mkdirSync(process.argv[outIndex + 1], {recursive: true});
const work = mkdtempSync(join(resolve(process.argv[outIndex + 1]), "ops-test-"));
const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
const tool = name => home ? join(home, "bin", name + (process.platform === "win32" ? ".exe" : "")) : name;
function run(name, args, directory) {
    const result = spawnSync(tool(name), args, {encoding: "utf8"});
    writeFileSync(join(directory, `${name}.log`), (result.stdout ?? "") + (result.stderr ?? ""));
    assert.equal(result.status, 0, `${name}: read ${join(directory, `${name}.log`)}: ${result.error ?? "failed"}`);
    return result.stdout;
}
const fixtures = {
    "com.mojang.blaze3d.textures.GpuTexture": "public class GpuTexture {}",
    "com.mojang.blaze3d.buffers.GpuBufferSlice": "public class GpuBufferSlice {}",
    "org.teavm.jso.JSObject": "public interface JSObject {}",
    "net.minecraft.core.BlockPos": "public class BlockPos { public static final BlockPos ZERO = new BlockPos(); }",
    "net.minecraft.core.Direction": "public enum Direction { DOWN, UP, NORTH, SOUTH, WEST, EAST }",
    "net.minecraft.world.level.EmptyBlockGetter": "public class EmptyBlockGetter { public static final EmptyBlockGetter INSTANCE = new EmptyBlockGetter(); }",
    "net.minecraft.tags.BlockTags": "public class BlockTags { public static final Object BLOCKS_FLUID_FLOW = new Object(); }",
    "net.minecraft.world.level.block.state.BlockState": `public class BlockState {
        public boolean blocking, permeable, motion, flow; public int dampening;
        public boolean isViewBlocking(net.minecraft.world.level.EmptyBlockGetter world, net.minecraft.core.BlockPos position) { return blocking; }
        public int getLightDampening() { return dampening; }
        public boolean isLightPermeable() { return permeable; }
        public boolean blocksMotion() { return motion; }
        public boolean is(Object tag) { return flow; }
    }`,
    "net.minecraft.client.resources.model.geometry.BakedQuad": `public record BakedQuad(MaterialInfo materialInfo, net.minecraft.core.Direction direction) {
        public record MaterialInfo(boolean shade, net.minecraft.core.Direction shadeDirectionOverride) {}
    }`,
    "dev.gaius.browser.kernel.mesh.MeshKernelBridge": `public class MeshKernelBridge {
        public static int copied, calls;
        public static int copyVerticesToLinear(org.teavm.jso.JSObject record, int layer, long pointer, int bytes) { calls++; return copied; }
    }`,
    "com.mojang.blaze3d.systems.RenderPass": `public class RenderPass {
        public static class UniformUploader {
            public String method, name; public com.mojang.blaze3d.buffers.GpuBufferSlice slice;
            public void upload(String name, com.mojang.blaze3d.buffers.GpuBufferSlice slice) { this.method = "upload"; this.name = name; this.slice = slice; }
            public void setUniform(String name, com.mojang.blaze3d.buffers.GpuBufferSlice slice) { this.method = "setUniform"; this.name = name; this.slice = slice; }
        }
    }`,
    "com.mojang.blaze3d.systems.RenderSystem": `public class RenderSystem {
        public static Object texture, pixels; public static int format; public static int[] coordinates;
        public static Device getDevice() { return new Device(); }
        public static class Device { public Encoder createCommandEncoder() { return new Encoder(); } }
        public static class Encoder {
            public void writeToTexture(com.mojang.blaze3d.textures.GpuTexture texture, java.nio.ByteBuffer pixels, com.mojang.blaze3d.platform.NativeImage.Format format, int mip, int layer, int x, int y, int width, int height) { RenderSystem.format = 1; capture(texture, pixels, mip, layer, x, y, width, height); }
            public void writeToTexture(com.mojang.blaze3d.textures.GpuTexture texture, java.nio.ByteBuffer pixels, int mip, int layer, int x, int y, int width, int height) { RenderSystem.format = 0; capture(texture, pixels, mip, layer, x, y, width, height); }
            private void capture(Object texture, Object pixels, int mip, int layer, int x, int y, int width, int height) { RenderSystem.texture = texture; RenderSystem.pixels = pixels; RenderSystem.coordinates = new int[] {mip, layer, x, y, width, height}; }
        }
    }`,
};
let totalAssertions = 0;
for (const profile of loadProfiles(root)) {
    for (const target of TARGETS) {
        const resolution = resolveSources(profile, "src", target, root);
        const selected = [...resolution.files].filter(([path]) => /(?:VersionOps|MeshOps|MeshProfile|LightOps)\.java$/u.test(path));
        const modern = selected.some(([path]) => path.endsWith("/RenderpearlVersionOps.java"));
        // The light engine API of the legacy patch set: getLightBlock and a ChunkPos class with fields.
        const legacyLight = selected.some(([path]) => path.endsWith("/LightBlockLightOps.java"));
        const mesh = selected.some(([path]) => path.endsWith("/MeshVersionOps.java"));
        const relocations = resolution.files.get("dev/gaius/browser/ops/VersionOps.java").relocations;
        const directory = join(work, `${profile.id}-${target}`);
        mkdirSync(directory);
        const sources = [];
        function write(path, text) {
            const file = join(directory, "src", path);
            mkdirSync(dirname(file), {recursive: true});
            writeFileSync(file, text + "\n");
            sources.push(file);
        }
        for (const [path, info] of selected) write(path, compiledText(info));
        // The decoder and the structure manager accessor changed between generations; each
        // fixture has only its generation's member, so an implementation calling the other fails to compile.
        const generationFixtures = {
            "com.mojang.blaze3d.platform.NativeImage": `public class NativeImage {
                public enum Format { RGBA, RGB }
                public final String how;
                private NativeImage(String how) { this.how = how; }
                ${modern
                    ? 'public static NativeImage read(java.io.InputStream stream) throws java.io.IOException { return new NativeImage("stream"); }'
                    : 'public static NativeImage read(Format format, java.io.InputStream stream) throws java.io.IOException { return new NativeImage("format:" + format); }'}
            }`,
            "net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager": "public class StructureTemplateManager {}",
            "net.minecraft.world.level.block.state.BlockState": fixtures["net.minecraft.world.level.block.state.BlockState"]
                .replace("public int getLightDampening()", `public int ${legacyLight ? "getLightBlock" : "getLightDampening"}()`),
            "net.minecraft.world.level.ChunkPos": legacyLight
                ? `public class ChunkPos {
                    public final int x, z;
                    public ChunkPos(int x, int z) { this.x = x; this.z = z; }
                    public static long asLong(int x, int z) { return (long) x & 4294967295L | ((long) z & 4294967295L) << 32; }
                }`
                : `public record ChunkPos(int x, int z) {
                    public static long pack(int x, int z) { return (long) x & 4294967295L | ((long) z & 4294967295L) << 32; }
                }`,
            "net.minecraft.server.MinecraftServer": `public class MinecraftServer {
                private final net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager manager;
                public MinecraftServer(net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager manager) { this.manager = manager; }
                public net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager ${modern ? "getStructureTemplateManager" : "getStructureManager"}() { return manager; }
            }`,
        };
        for (const [type, body] of Object.entries({...fixtures, ...generationFixtures,
            "dev.gaius.browser.JsArrays": `public class JsArrays { public static final boolean LINEAR_MEMORY = ${target === "wasmgc"}; }`,
        })) {
            const relocated = relocateText(type, relocations).text;
            write(relocated.replaceAll(".", "/") + ".java",
                `package ${relocated.slice(0, relocated.lastIndexOf("."))}; ${relocateText(body, relocations).text}`);
        }
        const meshTests = mesh ? `
            check(MeshProfile.PROFILE == ${profile.kernelSemantics.code});
            for (int bits = 0; bits < 32; bits++) {
                BlockState state = new BlockState(); state.blocking = (bits & 1) != 0;
                state.permeable = (bits & 2) != 0; state.motion = (bits & 4) != 0;
                state.flow = (bits & 8) != 0; state.dampening = (bits & 16) != 0 ? 3 : 0;
                check(MeshProfile.lightPermeable(state) == (${modern} ? state.permeable : !state.blocking || state.dampening == 0));
                check(MeshProfile.blocksFluidFlow(state) == (${modern} ? state.flow : state.motion));
            }
            for (Direction direction : Direction.values()) for (int override = -1; override < Direction.values().length; override++) {
                Direction shadeOverride = override < 0 ? null : Direction.values()[override];
                for (boolean shade : new boolean[] {false, true}) {
                    BakedQuad quad = new BakedQuad(new BakedQuad.MaterialInfo(shade, shadeOverride), direction);
                    int expected = ${modern} ? (shadeOverride != null ? shadeOverride : direction).ordinal() : shade ? direction.ordinal() : Direction.UP.ordinal();
                    check(MeshProfile.shadeFace(quad) == expected);
                }
            }
            RenderPass.UniformUploader uploader = new RenderPass.UniformUploader();
            GpuBufferSlice slice = new GpuBufferSlice(); MeshVersionOps.INSTANCE.uploadUniform(uploader, slice);
            check(uploader.method.equals("${modern ? "setUniform" : "upload"}"));
            check(uploader.name.equals("ChunkSection")); check(uploader.slice == slice);
        ` : "";
        const main = `package dev.gaius.browser.kernel.mesh;
            import dev.gaius.browser.ops.VersionOps;
            import com.mojang.blaze3d.platform.NativeImage;
            import com.mojang.blaze3d.textures.GpuTexture;
            import com.mojang.blaze3d.buffers.GpuBufferSlice;
            import com.mojang.blaze3d.systems.RenderPass;
            import com.mojang.blaze3d.systems.RenderSystem;
            import net.minecraft.core.Direction;
            import net.minecraft.server.MinecraftServer;
            import net.minecraft.world.level.ChunkPos;
            import dev.gaius.browser.kernel.light.LightVersionOps;
            import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.client.resources.model.geometry.BakedQuad;
            public class OpsTest {
                private static int assertions;
                private static void check(boolean value) { assertions++; if (!value) throw new AssertionError(assertions); }
                public static void main(String[] args) throws Exception {
                    GpuTexture texture = new GpuTexture(); java.nio.ByteBuffer pixels = java.nio.ByteBuffer.allocate(16);
                    VersionOps.INSTANCE.uploadGlyph(texture, pixels, 7, 9, 2, 16);
                    check(RenderSystem.texture == texture); check(RenderSystem.pixels == pixels);
                    check(RenderSystem.format == ${modern ? 0 : 1});
                    check(java.util.Arrays.equals(RenderSystem.coordinates, new int[] {0, 0, 7, 9, 2, 16}));
                    StructureTemplateManager templates = new StructureTemplateManager();
                    check(VersionOps.INSTANCE.structureTemplateManager(new MinecraftServer(templates)) == templates);
                    check(VersionOps.INSTANCE.readImage(NativeImage.Format.RGB,
                        new java.io.ByteArrayInputStream(new byte[0])).how.equals("${modern ? "stream" : "format:RGB"}"));
                    check(VersionOps.INSTANCE.pauseMenuLanButtonColumns() == ${modern ? 2 : 1});
                    // The light kernel bridge's vanilla members come from this generation's light ops.
                    check(LightVersionOps.INSTANCE.getClass().getName().equals("dev.gaius.browser.kernel.light.${legacyLight ? "LightBlockLightOps" : "DampeningLightOps"}"));
                    BlockState dark = new BlockState(); dark.dampening = 7;
                    check(LightVersionOps.INSTANCE.lightDampening(dark) == 7);
                    ChunkPos chunk = new ChunkPos(-3, 5);
                    check(LightVersionOps.INSTANCE.chunkX(chunk) == -3); check(LightVersionOps.INSTANCE.chunkZ(chunk) == 5);
                    check(LightVersionOps.INSTANCE.packChunk(-3, 5) == (4294967293L | 5L << 32));
                    MeshKernelBridge.copied = 16;
                    check(VersionOps.INSTANCE.copyVertices(null, 1, 4096, 16) == ${modern && target === "wasmgc"});
                    check(MeshKernelBridge.calls == ${modern && target === "wasmgc" ? 1 : 0});
                    ${modern && target === "wasmgc" ? `MeshKernelBridge.copied = 15;
                        try { VersionOps.INSTANCE.copyVertices(null, 1, 4096, 16); throw new AssertionError("short copy accepted"); }
                        catch (IllegalStateException expected) { check(expected.getMessage().equals("kernel vertex bytes do not match the quad count")); }` : ""}
                    ${meshTests}
                    System.out.println("OPS assertions=" + assertions);
                }
            }`;
        write("dev/gaius/browser/kernel/mesh/OpsTest.java", relocateText(main, relocations).text);
        const classes = join(directory, "classes");
        mkdirSync(classes);
        const argfile = join(directory, "javac.args");
        const quote = text => JSON.stringify(text.replaceAll("\\", "/"));
        writeFileSync(argfile, ["--release", "21", "-encoding", "UTF-8", "-d", quote(classes), ...sources.map(quote)].join("\n") + "\n");
        run("javac", ["-J-Xmx3g", `@${argfile}`], directory);
        const result = run("java", ["-Xmx3g", "-cp", classes, "dev.gaius.browser.kernel.mesh.OpsTest"], directory);
        const assertions = Number(/OPS assertions=(\d+)/u.exec(result)[1]);
        totalAssertions += assertions;
        console.log(`version-ops ${profile.id} ${target}: ${assertions} assertions PASS`);
    }
}
writeFileSync(join(work, "summary.json"), JSON.stringify({profiles: loadProfiles(root).length,
    targets: TARGETS.length, assertions: totalAssertions}, null, 2) + "\n");
console.log(`version-ops self-test passed: ${totalAssertions} assertions; ${work}`);
