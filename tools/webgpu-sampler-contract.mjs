import {mkdir, writeFile} from "node:fs/promises";
import {resolve, join} from "node:path";
import {fileURLToPath} from "node:url";
import {execFileSync} from "node:child_process";

if (!process.argv[2]) throw new Error("An explicit scratch output directory is required");
const output = resolve(process.argv[2]);
const root = fileURLToPath(new URL("../", import.meta.url));
await mkdir(output, {recursive: true});
const glue = join(output, "WebGpuGlue.java"), test = join(output, "SamplerContractTest.java");
await writeFile(glue, String.raw`package dev.gaius.gpu.webgpu;
import java.util.ArrayList;
import java.util.List;
final class WebGpuGlue {
    record Descriptor(String addressU, String addressV, String min, String mag, String mip, int anisotropy, double maxLod) {}
    static final List<Descriptor> descriptors = new ArrayList<>();
    static final List<Integer> released = new ArrayList<>();
    static int createSampler(String addressU, String addressV, String min, String mag, String mip, int anisotropy, double maxLod) {
        descriptors.add(new Descriptor(addressU, addressV, min, mag, mip, anisotropy, maxLod));
        return descriptors.size();
    }
    static void releaseHandle(int handle) { released.add(handle); }
}
`);
await writeFile(test, String.raw`package dev.gaius.gpu.webgpu;
public final class SamplerContractTest {
    public static void main(String[] args) {
        int combinations = 0;
        for (CoreAddressMode addressU : CoreAddressMode.values()) {
            for (CoreAddressMode addressV : CoreAddressMode.values()) {
                for (CoreFilterMode min : CoreFilterMode.values()) {
                    for (CoreFilterMode mag : CoreFilterMode.values()) {
                        for (double maxLod : new double[] {-1, 0, 4}) {
                            CoreSampler sampler = new CoreSampler(addressU, addressV, min, mag, 8, maxLod);
                            WebGpuGlue.Descriptor normal = WebGpuGlue.descriptors.get(sampler.handle - 1);
                            check(normal.addressU().equals(addressU.webName()) && normal.addressV().equals(addressV.webName()), "address modes");
                            check(normal.min().equals(min.webName()) && normal.mag().equals(mag.webName()), "in-level filters");
                            check(normal.mip().equals("linear"), "vanilla linear mip interpolation");
                            check(normal.anisotropy() == 8 && normal.maxLod() == maxLod, "anisotropy and LOD");
                            check(sampler.minFilter() == min && sampler.magFilter() == mag, "facade filters");
                            int depthHandle = sampler.nonFilteringHandle();
                            check(depthHandle == sampler.nonFilteringHandle(), "cached depth sampler");
                            WebGpuGlue.Descriptor depth = WebGpuGlue.descriptors.get(depthHandle - 1);
                            check(depth.min().equals("nearest") && depth.mag().equals("nearest") && depth.mip().equals("nearest"), "unfilterable depth remains nearest");
                            check(depth.anisotropy() == 1 && depth.maxLod() == maxLod, "depth LOD clamp");
                            check(depth.addressU().equals(addressU.webName()) && depth.addressV().equals(addressV.webName()), "depth addressing");
                            int releases = WebGpuGlue.released.size();
                            sampler.close();
                            sampler.close();
                            check(sampler.isClosed() && WebGpuGlue.released.size() == releases + 2, "idempotent close");
                            combinations++;
                        }
                    }
                }
            }
        }
        System.out.println("PASS " + combinations + " sampler combinations; linear mip interpolation and nearest depth fallback");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
`);
const core = join(root, "port/gpu/webgpu-core/src/main/java/dev/gaius/gpu/webgpu");
execFileSync(process.env.JAVAC || "javac", ["-J-Xmx2g", "-d", output, glue, test,
  ...["CoreSampler.java", "CoreAddressMode.java", "CoreFilterMode.java"].map(name => join(core, name))], {stdio: "inherit"});
execFileSync(process.env.JAVA || "java", ["-Xmx2g", "-classpath", output, "dev.gaius.gpu.webgpu.SamplerContractTest"], {stdio: "inherit"});
