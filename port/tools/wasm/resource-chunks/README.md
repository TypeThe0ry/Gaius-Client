# WasmGC resource table regression

Run from the repository root, offline, with an empty output directory (every Java
invocation uses `-Xmx3g`):

```sh
node port/tools/wasm/resource-chunks/run.mjs --out <out>
```

The script uses only installed Java/Node and jars from the local Maven repository
(`MAVEN_REPOSITORY` overrides its location). It does not invoke Maven or download
anything. It builds the production core patcher and a copy of the stock core jar,
then checks every patched class with ASM CheckClassAdapter and a fresh JVM using
`-Xverify:all`.

It registers the production MinecraftResourceSupplier via ServiceLoader, writes
both of its resource lists, and supplies 12,001 one-byte classpath resources with
distinct expected values. The small Java program reads every resource, checks EOF
and a missing resource, including both sides of the 4,096 and 8,192 boundaries.
Stock, baseline, and patched TeaVM 0.15 WEBASSEMBLY_GC builds use the same program/resources.
The baseline retains every other core patch and disables only resource chunking,
so the measured resource delta isolates this change. Stock and baseline modules
must fail V8 with the 10,000 fixed-array limit; the patched
module must pass WebAssembly.validate, instantiate with the stock TeaVM runtime,
print RESOURCE_CHUNKS_OK, and pass wasm-check.mjs. Logs and module size results
are written under the supplied output directory.

This is a small compiler regression, not a Minecraft build. It uses TeaVM's
coroutine mode; the changed resource intrinsic is shared with JSPI mode. Product
JSPI startup and full profile gates remain the coordinator's integration checks.
