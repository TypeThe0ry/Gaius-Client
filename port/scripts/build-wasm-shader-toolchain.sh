#!/usr/bin/env bash
# Builds the browser shader toolchain (PLAN D5, backend T1): shaderc (glslang +
# SPIRV-Tools) and SPIRV-Cross compiled to WebAssembly with emscripten, plus
# the hand-written loader port/wasm/shader-toolchain/loader/gaius-shader-toolchain.js.
#
# The WebAssembly modules are build outputs and are not checked in (the
# repository keeps large binaries only under port/web/dist, in Git LFS).  A
# release build of a profile whose client needs the toolchain runs this script
# and copies the result next to index.html (--dist).
#
# usage: build-wasm-shader-toolchain.sh [--work DIR] [--out DIR] [--dist DIR]
#                                       [--emsdk DIR] [--jobs N] [--force]
#                                       [--prebuilt DIR]
#   --work DIR   sources and build trees (default port/target/shader-toolchain).
#                Keep it short on Windows: CMake object paths below it exceed
#                MAX_PATH otherwise.
#   --out DIR    where the artifacts are written (default <work>/out)
#   --dist DIR   also copy the artifacts into DIR (a profile dist directory)
#   --emsdk DIR  an emsdk checkout with emscripten $GAIUS_ST_EMSCRIPTEN_VERSION
#                activated (default $GAIUS_EMSDK, else emcc from PATH)
#   --jobs N     ninja parallelism (default: ninja's)
#   --force      rebuild the WebAssembly modules even when the stamp matches
#   --prebuilt DIR  take the four module files from DIR instead of building them
#                (default $GAIUS_SHADER_TOOLCHAIN_PREBUILT): the output of an
#                earlier run, whose gaius-shader-toolchain.json must name exactly
#                the pinned sources and emscripten and whose files must match
#                its sha256 values (no emsdk needed; the loader always comes
#                from this checkout)
#
# Artifacts (all five are needed at runtime, see the loader):
#   gaius-shaderc.js   gaius-shaderc.wasm   emscripten glue + module of shaderc
#   gaius-spvc.js      gaius-spvc.wasm      emscripten glue + module of SPIRV-Cross
#   gaius-shader-toolchain.js               the loader (window.__gaiusShaderToolchain)
#   gaius-shader-toolchain.json             pins, emscripten version, sizes, sha256
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
toolchain_dir="$root/port/wasm/shader-toolchain"
# shellcheck source=../wasm/shader-toolchain/pins.env
source "$toolchain_dir/pins.env"

work="$root/port/target/shader-toolchain"
out=""
dist=""
emsdk="${GAIUS_EMSDK:-}"
jobs=""
force=false
prebuilt="${GAIUS_SHADER_TOOLCHAIN_PREBUILT:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --work) work="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    --dist) dist="$2"; shift 2 ;;
    --emsdk) emsdk="$2"; shift 2 ;;
    --jobs) jobs="$2"; shift 2 ;;
    --force) force=true; shift ;;
    --prebuilt) prebuilt="$2"; shift 2 ;;
    *)
      echo "build-wasm-shader-toolchain: unknown argument $1" >&2
      exit 2
      ;;
  esac
done
out="${out:-$work/out}"
src="$work/src"
build="$work/build"
mkdir -p "$src" "$build" "$out"

native_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}
posix_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -u "$1"; else printf '%s' "$1"; fi
}

module_files=(gaius-shaderc.js gaius-shaderc.wasm gaius-spvc.js gaius-spvc.wasm)

# ---- exported C API -------------------------------------------------------------
# Exactly the entry points the loader calls (plus malloc/free for its buffers);
# port/wasm/shader-toolchain/check-loader-exports.mjs keeps the two lists in step.
# auto_map_locations: the blaze3d GLSL front-end (26.2, 1.21.11) compiles like
# GlslCompiler, which sets it; renderpearl (26.3) does not call it.
spvc_exports=(
  spvc_context_create spvc_context_destroy spvc_context_get_last_error_string
  spvc_context_parse_spirv spvc_context_create_compiler
  spvc_compiler_create_compiler_options spvc_compiler_options_set_uint
  spvc_compiler_options_set_bool spvc_compiler_install_compiler_options
  spvc_compiler_create_shader_resources spvc_resources_get_resource_list_for_type
  spvc_compiler_get_decoration spvc_compiler_set_name spvc_compiler_get_name
  spvc_compiler_set_entry_point spvc_compiler_compile
  spvc_compiler_get_binary_offset_for_decoration spvc_compiler_get_declared_struct_size
  spvc_compiler_get_type_handle spvc_type_get_basetype spvc_type_get_image_dimension
  spvc_type_get_vector_size spvc_type_get_num_array_dimensions spvc_type_get_array_dimension
  malloc free
)
shaderc_exports=(
  shaderc_compiler_initialize shaderc_compiler_release
  shaderc_compile_options_initialize shaderc_compile_options_release
  shaderc_compile_options_add_macro_definition shaderc_compile_options_set_target_env
  shaderc_compile_options_set_auto_bind_uniforms shaderc_compile_options_set_preserve_bindings
  shaderc_compile_options_set_auto_map_locations
  shaderc_compile_options_set_generate_debug_info shaderc_compile_options_set_optimization_level
  shaderc_compile_options_set_include_callbacks shaderc_compile_into_spv
  shaderc_result_get_length shaderc_result_get_bytes shaderc_result_get_compilation_status
  shaderc_result_get_error_message shaderc_result_get_num_warnings
  shaderc_result_get_num_errors shaderc_result_release
  malloc free
)
# The shaderc exports of the modules this run publishes (recorded in the manifest).
module_shaderc_exports=""
module_spvc_exports=""

build_modules() {
  # ---- toolchain ---------------------------------------------------------------
  if [[ -n "$emsdk" ]]; then
    if [[ ! -f "$emsdk/.emscripten" ]]; then
      echo "build-wasm-shader-toolchain: $emsdk has no .emscripten config (run emsdk activate $GAIUS_ST_EMSCRIPTEN_VERSION)" >&2
      exit 1
    fi
    EM_CONFIG="$(native_path "$emsdk")/.emscripten"
    export EM_CONFIG
    export EMSDK="$(native_path "$emsdk")"
    emsdk="$(posix_path "$emsdk")"
    node_dir="$(find "$emsdk/node" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort | tail -n 1)"
    python_dir="$(find "$emsdk/python" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort | tail -n 1)"
    PATH="$emsdk/upstream/emscripten${node_dir:+:$node_dir/bin:$node_dir}${python_dir:+:$python_dir}:$PATH"
    export PATH
    if [[ -n "$python_dir" ]]; then
      for candidate in "$python_dir/python.exe" "$python_dir/bin/python3"; do
        if [[ -x "$candidate" ]]; then export EMSDK_PYTHON="$(native_path "$candidate")"; break; fi
      done
    fi
  fi
  for tool in emcc em++ emcmake cmake ninja git; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      echo "build-wasm-shader-toolchain: $tool not found." >&2
      echo "Install emsdk (git clone https://github.com/emscripten-core/emsdk;" \
        "emsdk install $GAIUS_ST_EMSCRIPTEN_VERSION; emsdk activate $GAIUS_ST_EMSCRIPTEN_VERSION)" \
        "and pass --emsdk or GAIUS_EMSDK; CMake, Ninja and Git must be on PATH." >&2
      exit 1
    fi
  done
  emcc_version="$(emcc --version 2>/dev/null | grep -m 1 -o '[0-9][0-9.]* ([0-9a-f]*)' || true)"
  if [[ "$emcc_version" != "$GAIUS_ST_EMSCRIPTEN_VERSION ($GAIUS_ST_EMSCRIPTEN_COMMIT)" ]]; then
    echo "build-wasm-shader-toolchain: emscripten '$emcc_version' is not the pinned" \
      "$GAIUS_ST_EMSCRIPTEN_VERSION ($GAIUS_ST_EMSCRIPTEN_COMMIT)" >&2
    exit 1
  fi
  python_for_cmake="${EMSDK_PYTHON:-$(command -v python3 || command -v python)}"

  # ---- sources -------------------------------------------------------------------
  # fetch_source DIR URL COMMIT: a shallow checkout of exactly COMMIT (verified
  # through git, so the tree is content-addressed by the pinned commit id).
  fetch_source() {
    local dir="$1" url="$2" commit="$3"
    if [[ -d "$dir/.git" ]] && [[ "$(git -C "$dir" rev-parse HEAD 2>/dev/null)" == "$commit" ]] \
        && git -C "$dir" diff --quiet HEAD 2>/dev/null; then
      return 0
    fi
    echo "Fetching $url@$commit"
    rm -rf "$dir"
    mkdir -p "$dir"
    git -C "$dir" init -q
    git -C "$dir" -c core.longpaths=true fetch -q --depth 1 "$url" "$commit"
    git -C "$dir" -c core.longpaths=true -c advice.detachedHead=false checkout -q FETCH_HEAD
    if [[ "$(git -C "$dir" rev-parse HEAD)" != "$commit" ]]; then
      echo "build-wasm-shader-toolchain: $dir is not at $commit" >&2
      exit 1
    fi
  }
  fetch_source "$src/shaderc" "$GAIUS_ST_SHADERC_URL" "$GAIUS_ST_SHADERC_COMMIT"
  fetch_source "$src/shaderc/third_party/glslang" "$GAIUS_ST_GLSLANG_URL" "$GAIUS_ST_GLSLANG_COMMIT"
  fetch_source "$src/shaderc/third_party/spirv-tools" "$GAIUS_ST_SPIRV_TOOLS_URL" "$GAIUS_ST_SPIRV_TOOLS_COMMIT"
  fetch_source "$src/shaderc/third_party/spirv-headers" "$GAIUS_ST_SPIRV_HEADERS_URL" "$GAIUS_ST_SPIRV_HEADERS_COMMIT"
  fetch_source "$src/SPIRV-Cross" "$GAIUS_ST_SPIRV_CROSS_URL" "$GAIUS_ST_SPIRV_CROSS_COMMIT"

  join_exports() {
    local list="" name
    for name in "$@"; do list="${list:+$list,}_$name"; done
    printf '%s' "$list"
  }

  src_native="$(native_path "$src")"
  build_native="$(native_path "$build")"
  # Build paths must not leak into the modules: map them to fixed names.
  prefix_map="-ffile-prefix-map=$src_native=/gaius-src -ffile-prefix-map=$build_native=/gaius-build"
  common_link=(
    -sMODULARIZE=1 -sENVIRONMENT=web,worker,node -sFILESYSTEM=0
    -sALLOW_MEMORY_GROWTH=1
    "-sEXPORTED_RUNTIME_METHODS=UTF8ToString,stringToUTF8,lengthBytesUTF8,HEAPU8,HEAPU32,HEAP32,addFunction,removeFunction"
  )
  stamp_input="$(cat "$toolchain_dir/pins.env"; printf '%s\n' "build-wasm-shader-toolchain-v1" "${spvc_exports[*]}" "${shaderc_exports[*]}" "${common_link[*]}")"
  stamp="$(printf '%s' "$stamp_input" | sha256sum | cut -d' ' -f1)"
  ninja_jobs=()
  if [[ -n "$jobs" ]]; then ninja_jobs=(-j "$jobs"); fi

  if [[ "$force" != true && -f "$build/stamp" && "$(cat "$build/stamp")" == "$stamp" \
      && -s "$build/gaius-shaderc.wasm" && -s "$build/gaius-spvc.wasm" ]]; then
    echo "Shader toolchain modules are up to date ($stamp)"
  else
    rm -f "$build/stamp"
    # ---- SPIRV-Cross: C API + GLSL backend.  SPIRV-Cross reports errors with C++
    # exceptions; the C API catches them, so the module needs wasm exceptions
    # (legacy encoding: Chrome 95+, Firefox 100+, Safari 15.2+).
    cmake_flags="-fwasm-exceptions $prefix_map"
    mkdir -p "$build/spvc"
    (
      cd "$build/spvc"
      emcmake cmake -G Ninja "$src_native/SPIRV-Cross" -DCMAKE_BUILD_TYPE=MinSizeRel \
        "-DCMAKE_CXX_FLAGS=$cmake_flags" "-DCMAKE_C_FLAGS=$cmake_flags" \
        -DSPIRV_CROSS_SHARED=OFF -DSPIRV_CROSS_STATIC=ON -DSPIRV_CROSS_CLI=OFF \
        -DSPIRV_CROSS_ENABLE_TESTS=OFF -DSPIRV_CROSS_ENABLE_HLSL=OFF -DSPIRV_CROSS_ENABLE_MSL=OFF \
        -DSPIRV_CROSS_ENABLE_CPP=OFF -DSPIRV_CROSS_ENABLE_REFLECT=OFF -DSPIRV_CROSS_ENABLE_UTIL=OFF \
        -DSPIRV_CROSS_ENABLE_GLSL=ON -DSPIRV_CROSS_ENABLE_C_API=ON >cmake.log
      ninja "${ninja_jobs[@]}" >ninja.log
    )
    (
      cd "$build"
      em++ -Os -fwasm-exceptions -sWASM_LEGACY_EXCEPTIONS=1 \
        spvc/libspirv-cross-c.a spvc/libspirv-cross-glsl.a spvc/libspirv-cross-core.a \
        -o gaius-spvc.js -sEXPORT_NAME=GaiusSpvcModule -sSTACK_SIZE=524288 \
        "-sEXPORTED_FUNCTIONS=$(join_exports "${spvc_exports[@]}")" "${common_link[@]}"
    )

    # ---- shaderc: the libshaderc C API over glslang and SPIRV-Tools (no
    # exceptions).  The include resolver is a JS function added to the table.
    mkdir -p "$build/shaderc"
    (
      cd "$build/shaderc"
      emcmake cmake -G Ninja "$src_native/shaderc" -DCMAKE_BUILD_TYPE=MinSizeRel \
        "-DCMAKE_CXX_FLAGS=$prefix_map" "-DCMAKE_C_FLAGS=$prefix_map" \
        -DSHADERC_SKIP_TESTS=ON -DSHADERC_SKIP_EXAMPLES=ON -DSHADERC_SKIP_COPYRIGHT_CHECK=ON \
        -DSPIRV_SKIP_EXECUTABLES=ON -DSPIRV_SKIP_TESTS=ON -DSPIRV_WERROR=OFF \
        -DENABLE_GLSLANG_BINARIES=OFF -DGLSLANG_TESTS=OFF -DSPIRV_TOOLS_BUILD_STATIC=ON \
        -DBUILD_SHARED_LIBS=OFF "-DPython3_EXECUTABLE=$python_for_cmake" >cmake.log
      ninja "${ninja_jobs[@]}" shaderc >ninja.log
    )
    (
      cd "$build"
      em++ -Os \
        shaderc/libshaderc/libshaderc.a shaderc/libshaderc_util/libshaderc_util.a \
        shaderc/third_party/glslang/glslang/libglslang.a \
        shaderc/third_party/spirv-tools/source/opt/libSPIRV-Tools-opt.a \
        shaderc/third_party/spirv-tools/source/libSPIRV-Tools.a \
        -o gaius-shaderc.js -sEXPORT_NAME=GaiusShadercModule -sSTACK_SIZE=1048576 \
        -sALLOW_TABLE_GROWTH=1 \
        "-sEXPORTED_FUNCTIONS=$(join_exports "${shaderc_exports[@]}")" "${common_link[@]}"
    )
    printf '%s' "$stamp" >"$build/stamp"
  fi
  module_dir="$build"
  module_shaderc_exports="${shaderc_exports[*]}"
  module_spvc_exports="${spvc_exports[*]}"
}

# use_prebuilt DIR: verifies an earlier run's modules against the pins.
use_prebuilt() {
  local dir="$1" manifest="$1/gaius-shader-toolchain.json" name expected actual
  if [[ ! -f "$manifest" ]]; then
    echo "build-wasm-shader-toolchain: $manifest is missing" >&2
    exit 1
  fi
  for name in shaderc glslang spirv-tools spirv-headers spirv-cross; do
    case "$name" in
      shaderc) expected="$GAIUS_ST_SHADERC_COMMIT" ;;
      glslang) expected="$GAIUS_ST_GLSLANG_COMMIT" ;;
      spirv-tools) expected="$GAIUS_ST_SPIRV_TOOLS_COMMIT" ;;
      spirv-headers) expected="$GAIUS_ST_SPIRV_HEADERS_COMMIT" ;;
      spirv-cross) expected="$GAIUS_ST_SPIRV_CROSS_COMMIT" ;;
    esac
    actual="$(jq -r --arg name "$name" '.sources[$name] // empty' "$manifest")"
    if [[ "$actual" != "$expected" ]]; then
      echo "build-wasm-shader-toolchain: prebuilt $name is '$actual', pinned $expected" >&2
      exit 1
    fi
  done
  actual="$(jq -r '.emscripten // empty' "$manifest")"
  if [[ "$actual" != "$GAIUS_ST_EMSCRIPTEN_VERSION ($GAIUS_ST_EMSCRIPTEN_COMMIT)" ]]; then
    echo "build-wasm-shader-toolchain: prebuilt modules come from emscripten '$actual'" >&2
    exit 1
  fi
  for name in "${module_files[@]}"; do
    expected="$(jq -r --arg name "$name" '.files[$name].sha256 // empty' "$manifest")"
    actual="$(sha256sum "$dir/$name" 2>/dev/null | cut -d' ' -f1)"
    if [[ -z "$expected" || "$actual" != "$expected" ]]; then
      echo "build-wasm-shader-toolchain: prebuilt $dir/$name does not match its manifest" >&2
      exit 1
    fi
  done
  # Modules built before an export was added still work for the profiles that do not need it;
  # say which exports they lack (a manifest without the record predates it).
  module_shaderc_exports="$(jq -r '.exports.shaderc // empty' "$manifest")"
  module_spvc_exports="$(jq -r '.exports.spvc // empty' "$manifest")"
  if [[ "$module_shaderc_exports" != "${shaderc_exports[*]}" || "$module_spvc_exports" != "${spvc_exports[*]}" ]]; then
    echo "build-wasm-shader-toolchain: warning: the prebuilt modules do not export exactly what the loader" \
      "may call (manifest exports: '${module_shaderc_exports:-not recorded}'); the blaze3d profiles (26.2," \
      "1.21.11) need shaderc_compile_options_set_auto_map_locations: rebuild with emsdk" >&2
  fi
  echo "Using the prebuilt shader toolchain modules in $dir"
  module_dir="$dir"
}

if [[ -n "$prebuilt" ]]; then
  use_prebuilt "$prebuilt"
else
  build_modules
fi

# ---- artifacts ----------------------------------------------------------------------
for artifact in "${module_files[@]}"; do
  if [[ "$module_dir/$artifact" -ef "$out/$artifact" ]]; then
    continue
  fi
  cp "$module_dir/$artifact" "$out/$artifact"
done
cp "$toolchain_dir/loader/gaius-shader-toolchain.js" "$out/gaius-shader-toolchain.js"

describe() {
  local file="$out/$1"
  printf '    "%s": {"bytes": %s, "sha256": "%s"}' \
    "$1" "$(wc -c <"$file" | tr -d ' ')" "$(sha256sum "$file" | cut -d' ' -f1)"
}
{
  printf '{\n  "schema": 1,\n'
  printf '  "emscripten": "%s (%s)",\n' "$GAIUS_ST_EMSCRIPTEN_VERSION" "$GAIUS_ST_EMSCRIPTEN_COMMIT"
  printf '  "sources": {\n'
  printf '    "shaderc": "%s",\n    "glslang": "%s",\n    "spirv-tools": "%s",\n' \
    "$GAIUS_ST_SHADERC_COMMIT" "$GAIUS_ST_GLSLANG_COMMIT" "$GAIUS_ST_SPIRV_TOOLS_COMMIT"
  printf '    "spirv-headers": "%s",\n    "spirv-cross": "%s"\n  },\n' \
    "$GAIUS_ST_SPIRV_HEADERS_COMMIT" "$GAIUS_ST_SPIRV_CROSS_COMMIT"
  if [[ -n "$module_shaderc_exports" ]]; then
    printf '  "exports": {\n    "shaderc": "%s",\n    "spvc": "%s"\n  },\n' \
      "$module_shaderc_exports" "$module_spvc_exports"
  fi
  printf '  "files": {\n'
  describe gaius-shader-toolchain.js; printf ',\n'
  describe gaius-shaderc.js; printf ',\n'
  describe gaius-shaderc.wasm; printf ',\n'
  describe gaius-spvc.js; printf ',\n'
  describe gaius-spvc.wasm; printf '\n  }\n}\n'
} >"$out/gaius-shader-toolchain.json"

if [[ -n "$dist" ]]; then
  mkdir -p "$dist"
  for artifact in gaius-shader-toolchain.js gaius-shader-toolchain.json \
      gaius-shaderc.js gaius-shaderc.wasm gaius-spvc.js gaius-spvc.wasm; do
    cp "$out/$artifact" "$dist/$artifact"
  done
  echo "Copied the shader toolchain into $dist"
fi
echo "Built the shader toolchain: $out"
