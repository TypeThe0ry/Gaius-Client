# Gaius Wasm hot-path modules

This directory holds the small C hot-path module and the browser shader toolchain
(`shader-toolchain/`) used by the browser port while the supported official Minecraft
Java profiles remain compiled through TeaVM (WebAssembly GC with JSPI). The Rust kernels that run in Workers (noise, worldgen, light,
mesher, shader translation) are not here: they live in `port/native`.

Rules for adding Wasm work:

- Keep Java/TeaVM as the authoritative client implementation.
- Only move batch-oriented pure compute paths across the JS/Wasm boundary.
- Never call Wasm once per block, item, vertex, or entity from Java.
- Keep the Wasm boundary explicit and fail closed when a capability is unavailable.
- Prefer flat typed arrays, numeric enums, and explicit capacities over object
  graphs.

Current module:

- `hotpath/gaius_hotpath.c`
  - `gaius_shift_indices(...)`: shifts WebGL element indices for
    `drawElementsBaseVertex` fallback in bulk.
  - `gaius_repack_interleaved(...)`: repacks misaligned/interleaved vertex
    attributes into a browser-safe aligned buffer in bulk.
  - `gaius_unpack_bit_storage(...)`: unpacks a bit-packed long array (a
    vanilla `BitStorage`) into values in bulk.

Build:

```sh
GAIUS_VERSION_PROFILE_PATH=versions/26.2.json \
  ./port/scripts/build-wasm-hotpath.sh
```

Output:

- Profile-isolated release builds write
  `port/web/dist/<profile>/gaius-hotpath.wasm`.
