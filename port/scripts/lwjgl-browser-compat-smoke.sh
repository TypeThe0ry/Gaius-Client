#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/port/scripts/version-profile.sh"
gaius_load_version_profile "$ROOT"
gaius_select_java_home
overlay_directory="$(gaius_overlay_directory "$ROOT")"

MEMORY_PATH="$(gaius_library_path "org.lwjgl:lwjgl" "unsafe")"
OPENGL_PATH="$(gaius_library_path "org.lwjgl:lwjgl-opengl")"
# 26.3 ships lwjgl-sdl instead of lwjgl-glfw; the GLFW checks need the module.
GLFW_PATH="$(gaius_library_path_optional "org.lwjgl:lwjgl-glfw")"
STB_PATH="$(gaius_library_path "org.lwjgl:lwjgl-stb")"
MEMORY_JAR="$overlay_directory/libraries/$MEMORY_PATH"
OPENGL_JAR="$overlay_directory/libraries/$OPENGL_PATH"
GLFW_JAR="${GLFW_PATH:+$overlay_directory/libraries/$GLFW_PATH}"
STB_JAR="$overlay_directory/libraries/$STB_PATH"
VMA_PATH="$(gaius_library_path "org.lwjgl:lwjgl-vma" 2>/dev/null || true)"
VULKAN_PATH="$(gaius_library_path "org.lwjgl:lwjgl-vulkan" 2>/dev/null || true)"
VMA_JAR="${VMA_PATH:+$overlay_directory/libraries/$VMA_PATH}"
VULKAN_JAR="${VULKAN_PATH:+$overlay_directory/libraries/$VULKAN_PATH}"

die() {
    printf 'lwjgl browser compatibility smoke failed: %s\n' "$1" >&2
    exit 1
}

# Git Bash on Windows can expose javap output with CRLF line endings.  Keep
# method headers/bytecode stable for awk/rg on both Windows and Unix hosts.
javap_dump() {
    local javap_bin="javap"
    if [[ -n "${GAIUS_JAVA_HOME:-}" && -x "$GAIUS_JAVA_HOME/bin/javap" ]]; then
        javap_bin="$GAIUS_JAVA_HOME/bin/javap"
    fi
    "$javap_bin" "$@" | tr -d '\r'
}

[[ -f "$MEMORY_JAR" ]] || die "missing $MEMORY_JAR; run build-overlays.sh first"
[[ -f "$OPENGL_JAR" ]] || die "missing $OPENGL_JAR; run build-overlays.sh first"
if [[ -n "$GLFW_JAR" ]]; then
    [[ -f "$GLFW_JAR" ]] || die "missing $GLFW_JAR; run build-overlays.sh first"
fi
[[ -f "$STB_JAR" ]] || die "missing $STB_JAR; run build-overlays.sh first"
if [[ -n "$VMA_JAR" ]]; then
    [[ -f "$VMA_JAR" ]] || die "missing $VMA_JAR; run build-overlays.sh first"
fi
if [[ -n "$VULKAN_JAR" ]]; then
    [[ -f "$VULKAN_JAR" ]] || die "missing $VULKAN_JAR; run build-overlays.sh first"
fi

method_block() {
    local dump="$1"
    local needle="$2"
    printf '%s\n' "$dump" | awk -v needle="$needle" '
        /^  (public|protected|private|static) / {
            if (active) active = 0
            if (index($0, needle) != 0) active = 1
        }
        active { print }
    '
}

assert_delegate() {
    local label="$1"
    local dump="$2"
    local signature="$3"
    local delegate="$4"
    local block
    block="$(method_block "$dump" "$signature")"
    [[ -n "$block" ]] || die "$label method not found: $signature"
    printf '%s\n' "$block" | rg -q "$delegate" \
        || die "$label does not delegate to $delegate: $signature"
    if printf '%s\n' "$block" | rg -q \
            'GL\.getICD|JNINativeInterface|JNI\.call|sun[/\.]misc[/\.]Unsafe\.'; then
        die "$label still contains native/Unsafe dispatch: $signature"
    fi
}

QUERY_METHODS=0
for owner in GL15 GL15C GL32 GL32C; do
    if dump="$(javap_dump -classpath "$OPENGL_JAR" -p -c "org.lwjgl.opengl.$owner" 2>/dev/null)"; then
        headers="$(printf '%s\n' "$dump" | rg \
            '^  (public|protected|private|static).* (n?glGenQueries)\(' || true)"
        if [[ -n "$headers" ]]; then
            while IFS= read -r header; do
                [[ -n "$header" ]] || continue
                QUERY_METHODS=$((QUERY_METHODS + 1))
                assert_delegate "$owner query generation" "$dump" "$header" 'BrowserOpenGL'
            done <<< "$headers"
        fi
    fi
done
[[ "$QUERY_METHODS" -gt 0 ]] || die "no GL query-generation overloads found"

DRAW_BUFFER_METHODS=0
for owner in GL20 GL20C; do
    dump="$(javap_dump -classpath "$OPENGL_JAR" -p -c "org.lwjgl.opengl.$owner")"
    headers="$(printf '%s\n' "$dump" | rg \
        '^  (public|protected|private|static).* (n?glDrawBuffers)\(' || true)"
    while IFS= read -r header; do
        [[ -n "$header" ]] || continue
        DRAW_BUFFER_METHODS=$((DRAW_BUFFER_METHODS + 1))
        assert_delegate "$owner draw buffers" "$dump" "$header" 'BrowserOpenGL'
    done <<< "$headers"
done
[[ "$DRAW_BUFFER_METHODS" -ge 8 ]] || die "not all GL20 draw-buffer overloads were patched"

CLEAR_BUFFER_METHODS=0
for owner in GL30 GL30C; do
    dump="$(javap_dump -classpath "$OPENGL_JAR" -p -c "org.lwjgl.opengl.$owner")"
    headers="$(printf '%s\n' "$dump" | rg \
        '^  (public|protected|private|static).* (n?glClearBufferfv)\(' || true)"
    while IFS= read -r header; do
        [[ -n "$header" ]] || continue
        CLEAR_BUFFER_METHODS=$((CLEAR_BUFFER_METHODS + 1))
        assert_delegate "$owner clear buffer" "$dump" "$header" 'BrowserOpenGL'
    done <<< "$headers"
done
[[ "$CLEAR_BUFFER_METHODS" -ge 6 ]] || die "not all GL30 clear-buffer overloads were patched"

DELETE_QUERY_METHODS=0
for owner in GL15 GL15C GL32 GL32C; do
    dump="$(javap_dump -classpath "$OPENGL_JAR" -p -c "org.lwjgl.opengl.$owner" 2>/dev/null || true)"
    headers="$(printf '%s\n' "$dump" | rg \
        '^  (public|protected|private|static).* (n?glDeleteQueries)\(' || true)"
    while IFS= read -r header; do
        [[ -n "$header" ]] || continue
        DELETE_QUERY_METHODS=$((DELETE_QUERY_METHODS + 1))
        block="$(method_block "$dump" "$header")"
        printf '%s\n' "$block" | rg -q 'return' \
            || die "$owner query deletion is not a no-op: $header"
        if printf '%s\n' "$block" | rg -q 'GLCapabilities|JNI|invoke'; then
            die "$owner query deletion still reaches native state: $header"
        fi
    done <<< "$headers"
done
[[ "$DELETE_QUERY_METHODS" -ge 8 ]] || die "not all declared query-delete overloads were patched"

MEMORY_DUMP="$(javap_dump -classpath "$MEMORY_JAR" -p -c org.lwjgl.system.MemoryUtil)"
MEMORY_HEADERS="$(printf '%s\n' "$MEMORY_DUMP" | rg \
    '^  (public|protected|private|static).* (memFree|memAlignedFree|memAddress0|memAddressSafe|memAddress|memDuplicate|memSlice|wrapBuffer|write8Safe|slice|duplicate)\(' \
    | rg -v 'CustomBuffer|org\.lwjgl\.system\.Pointer' || true)"
[[ -n "$MEMORY_HEADERS" ]] || die "no MemoryUtil browser-sensitive methods found"
while IFS= read -r header; do
    [[ -n "$header" ]] || continue
    assert_delegate "MemoryUtil browser memory" "$MEMORY_DUMP" "$header" 'BrowserMemory'
done <<< "$MEMORY_HEADERS"

# APIUtil has two deliberately different null contracts in the browser:
# required FunctionProvider lookups use the synthetic non-zero address, while
# optional SharedLibrary lookups must return zero when there is no native
# library at all.  The latter used to dereference null during 26.2 GLFW
# initialization (notably GetPreeditCursorRectangle).
API_UTIL_DUMP="$(javap_dump -classpath "$MEMORY_JAR" -p -c org.lwjgl.system.APIUtil)"
API_REQUIRED_BLOCK="$(method_block "$API_UTIL_DUMP" \
    'long apiGetFunctionAddress(org.lwjgl.system.FunctionProvider, java.lang.String)')"
printf '%s\n' "$API_REQUIRED_BLOCK" | rg -q 'lconst_1' \
    || die "APIUtil required lookup lost its browser fallback address"
API_OPTIONAL_BLOCK="$(method_block "$API_UTIL_DUMP" \
    'long apiGetFunctionAddressOptional(org.lwjgl.system.SharedLibrary, java.lang.String)')"
[[ -n "$API_OPTIONAL_BLOCK" ]] || die "APIUtil optional lookup method not found"
printf '%s\n' "$API_OPTIONAL_BLOCK" | rg -q 'ifnonnull' \
    || die "APIUtil optional lookup has no null-library guard"
printf '%s\n' "$API_OPTIONAL_BLOCK" | rg -q 'lconst_0' \
    || die "APIUtil optional lookup has no zero-address null fallback"
printf '%s\n' "$API_OPTIONAL_BLOCK" | rg -q \
    'org[/\.]lwjgl[/\.]system[/\.]SharedLibrary[./]getFunctionAddress' \
    || die "APIUtil optional lookup no longer delegates non-null libraries"

# GLFW 3.4.1 exposes IME/preedit wrappers that have no browser implementation.
# Older 3.3.x profiles do not declare these methods; assert only when a method
# exists so the same smoke remains valid for both supported profiles.
# Without lwjgl-glfw (26.3) GLFW_DUMP stays empty and assert_ime_noop finds
# no method to check.
GLFW_DUMP=""
if [[ -n "$GLFW_JAR" ]]; then
    GLFW_DUMP="$(javap_dump -classpath "$GLFW_JAR" -p -c org.lwjgl.glfw.GLFW)"
else
    printf 'lwjgl browser compatibility smoke: no lwjgl-glfw module; GLFW IME checks do not apply\n'
fi
assert_ime_noop() {
    local label="$1"
    local signature="$2"
    local return_kind="$3"
    local block
    block="$(method_block "$GLFW_DUMP" "$signature")"
    [[ -n "$block" ]] || return 0
    if printf '%s\n' "$block" | rg -q \
            'nglfw|JNI\.|Checks\.|GLFW\$Functions|MemoryUtil\.'; then
        die "$label still reaches native GLFW dispatch: $signature"
    fi
    if [[ "$return_kind" == "null" ]]; then
        printf '%s\n' "$block" | rg -q 'aconst_null' \
            || die "$label does not return null: $signature"
        printf '%s\n' "$block" | rg -q 'areturn' \
            || die "$label does not return a reference: $signature"
    else
        printf '%s\n' "$block" | rg -q 'return' \
            || die "$label is not a no-op: $signature"
    fi
}

assert_ime_noop "GLFW preedit rectangle (IntBuffer)" \
    'void glfwGetPreeditCursorRectangle(long, java.nio.IntBuffer, java.nio.IntBuffer, java.nio.IntBuffer, java.nio.IntBuffer)' void
assert_ime_noop "GLFW preedit rectangle (arrays)" \
    'void glfwGetPreeditCursorRectangle(long, int[], int[], int[], int[])' void
assert_ime_noop "GLFW set preedit rectangle" \
    'void glfwSetPreeditCursorRectangle(long, int, int, int, int)' void
assert_ime_noop "GLFW reset preedit text" \
    'void glfwResetPreeditText(long)' void
assert_ime_noop "GLFW get preedit candidate" \
    'java.nio.IntBuffer glfwGetPreeditCandidate(long, int)' null
assert_ime_noop "GLFW preedit callback" \
    'org.lwjgl.glfw.GLFWPreeditCallback glfwSetPreeditCallback(long, org.lwjgl.glfw.GLFWPreeditCallbackI)' null
assert_ime_noop "GLFW IME status callback" \
    'org.lwjgl.glfw.GLFWIMEStatusCallback glfwSetIMEStatusCallback(long, org.lwjgl.glfw.GLFWIMEStatusCallbackI)' null
assert_ime_noop "GLFW preedit candidate callback" \
    'org.lwjgl.glfw.GLFWPreeditCandidateCallback glfwSetPreeditCandidateCallback(long, org.lwjgl.glfw.GLFWPreeditCandidateCallbackI)' null

assert_delegate \
    "MemoryUtil allocator" \
    "$MEMORY_DUMP" \
    'org.lwjgl.system.MemoryUtil$MemoryAllocator getAllocator(boolean)' \
    'BrowserMemoryAllocator.instance'

# MemoryUtilTunables was introduced after the 3.3.x LWJGL used by the
# 1.21.11 profile.  Keep the common smoke valid for both supported LWJGL
# lines while retaining the stronger browser delegation checks when the class
# is present (3.4.x/26.2).
if MEMORY_TUNABLES_DUMP="$(javap_dump -classpath "$MEMORY_JAR" -p -c org.lwjgl.system.MemoryUtilTunables 2>/dev/null)"; then
    assert_delegate \
        "MemoryUtilTunables large memset" \
        "$MEMORY_TUNABLES_DUMP" \
        'void memset(long, int, long)' \
        'BrowserMemory.set'
    assert_delegate \
        "MemoryUtilTunables large memcpy" \
        "$MEMORY_TUNABLES_DUMP" \
        'void memcpy(long, long, long)' \
        'BrowserMemory.copy'
else
    printf 'lwjgl browser compatibility smoke: MemoryUtilTunables not present; skipping 3.4.x-only checks\n'
fi

LIBC_STRING_DUMP="$(javap_dump -classpath "$MEMORY_JAR" -p -c org.lwjgl.system.libc.LibCString)"
assert_delegate \
    "LibCString memset" \
    "$LIBC_STRING_DUMP" \
    'long nmemset(long, int, long)' \
    'BrowserMemory.cMemset'
assert_delegate \
    "LibCString memcpy" \
    "$LIBC_STRING_DUMP" \
    'long nmemcpy(long, long, long)' \
    'BrowserMemory.cMemcpy'
assert_delegate \
    "LibCString memmove" \
    "$LIBC_STRING_DUMP" \
    'long nmemmove(long, long, long)' \
    'BrowserMemory.cMemmove'

CALLBACK_I_DUMP="$(javap_dump -classpath "$MEMORY_JAR" -p -c org.lwjgl.system.CallbackI)"
CALLBACK_ADDRESS="$(method_block "$CALLBACK_I_DUMP" 'long address()')"
printf '%s\n' "$CALLBACK_ADDRESS" | rg -q 'lconst_1' \
    || die "CallbackI.address does not use the browser callback handle"
if printf '%s\n' "$CALLBACK_ADDRESS" | rg -q 'getDescriptor|Upcalls'; then
    die "CallbackI.address still creates a native libffi upcall"
fi

for callback in \
    'org.lwjgl.system.MemoryManage$DebugAllocator$CallbackPP' \
    org.lwjgl.glfw.GLFWPreeditCallbackI \
    org.lwjgl.stb.STBIWriteCallbackI; do
    case "$callback" in
        org.lwjgl.system.*) jar="$MEMORY_JAR" ;;
        org.lwjgl.glfw.*)
            jar="$GLFW_JAR"
            if [[ -z "$jar" ]]; then
                printf 'lwjgl browser compatibility smoke: no lwjgl-glfw module for %s\n' "$callback"
                continue
            fi
            ;;
        *) jar="$STB_JAR" ;;
    esac
    # Callback helper classes are not identical across the supported LWJGL
    # lines (3.4.x adds DebugAllocator callback wrappers).  Only inspect a
    # class when the selected profile actually ships it.
    if callback_dump="$(javap_dump -classpath "$jar" -p -c "$callback" 2>/dev/null)"; then
        if printf '%s\n' "$callback_dump" | rg -q 'MethodHandles\.lookup'; then
            die "$callback still initializes a native libffi descriptor"
        fi
    else
        printf 'lwjgl browser compatibility smoke: %s not present; skipping optional callback check\n' "$callback"
    fi
done

# Even private/package-private 3.3.x alignment helpers are patched. Keeping
# desktop Unsafe bytecode in a supposedly browser-safe overlay makes future
# reachability changes fragile, so do not exempt dead-code assumptions here.
if printf '%s\n' "$MEMORY_DUMP" | rg -n \
        'getstatic .*UNSAFE|sun[/\.]misc[/\.]Unsafe\.|JNINativeInterface'; then
    die "MemoryUtil bytecode still contains reachable Unsafe/JNI implementation"
fi

if [[ -n "$VMA_JAR" ]]; then
    for owner in org.lwjgl.util.vma.Vma org.lwjgl.util.vma.LibVma; do
        dump="$(javap_dump -classpath "$VMA_JAR" -p -c "$owner")"
        if rg -q '^  .* native .*\(' <<< "$dump"; then
            die "$owner still declares desktop-native methods"
        fi
        rg -q 'UnsupportedOperationException' <<< "$dump" \
            || die "$owner native entry points do not fail explicitly in the browser"
    done
fi

if [[ -n "$VULKAN_JAR" ]]; then
    dump="$(javap_dump -classpath "$VULKAN_JAR" -p -c org.lwjgl.vulkan.VK10)"
    if rg -q '^  .* native .*\(' <<< "$dump"; then
        die "org.lwjgl.vulkan.VK10 still declares desktop-native methods"
    fi
    rg -q 'UnsupportedOperationException' <<< "$dump" \
        || die "Vulkan native entry points do not fail explicitly in the browser"
fi

# LWJGL 3.4.2+ (26.3) routes MemoryUtil and MemoryStack through the
# MemoryUtil.BACKEND field.  LwjglMemoryPatcher must install
# BrowserMemoryBackend there, write only fields that exist, and replace the
# reflective backend and allocator lookups.  LWJGL 3.4.1 (26.2) has no
# MemoryBackend; its checks are the delegation checks above.
MEMORY_SHAPE="Unsafe"
# (Here-strings, not printf | rg -q: rg exits at the first match, and printf
# of a large dump then fails with SIGPIPE under pipefail.)
if rg -qF 'static final org.lwjgl.system.MemoryBackend BACKEND;' <<<"$MEMORY_DUMP"; then
    MEMORY_SHAPE="MemoryBackend"
    CLINIT_BLOCK="$(method_block "$MEMORY_DUMP" 'static {};')"
    [[ -n "$CLINIT_BLOCK" ]] || die "MemoryUtil.<clinit> not found"
    assigned="$(printf '%s\n' "$CLINIT_BLOCK" | rg 'putstatic' \
        | sed -e 's/.*Field //' -e 's/:.*//' | LC_ALL=C sort | tr '\n' ' ')"
    [[ "$assigned" == "ARRAY_TLC_BYTE ARRAY_TLC_CHAR ARRAY_TLC_SIZE BACKEND CACHE_LINE_SIZE PAGE_SIZE UTF16 " ]] \
        || die "MemoryUtil.<clinit> assigns [$assigned] instead of the MemoryBackend field set"
    for field in $assigned; do
        rg -q "^  .*static final .* ${field};" <<<"$MEMORY_DUMP" \
            || die "MemoryUtil.<clinit> writes $field, which MemoryUtil does not declare"
    done
    printf '%s\n' "$CLINIT_BLOCK" | rg -A1 -F 'BrowserMemoryBackend.INSTANCE' \
        | rg -qF 'Field BACKEND:Lorg/lwjgl/system/MemoryBackend;' \
        || die "MemoryUtil.BACKEND is not assigned BrowserMemoryBackend.INSTANCE"
    if printf '%s\n' "$CLINIT_BLOCK" | rg -q 'Library.initialize|getPageSize|getCacheLineSize|createBackend|apiLog'; then
        die "MemoryUtil.<clinit> still initializes natives or logs the desktop backend"
    fi
    CREATE_BACKEND_BLOCK="$(method_block "$MEMORY_DUMP" 'org.lwjgl.system.MemoryBackend createBackend()')"
    printf '%s\n' "$CREATE_BACKEND_BLOCK" | rg -qF 'BrowserMemoryBackend.INSTANCE' \
        || die "MemoryUtil.createBackend() does not return BrowserMemoryBackend.INSTANCE"
    if printf '%s\n' "$CREATE_BACKEND_BLOCK" | rg -q 'forName|newInstance|getDeclaredConstructor|Configuration'; then
        die "MemoryUtil.createBackend() still selects a desktop backend by reflection"
    fi
    # Word-at-a-time NUL scans would read past the end of an exactly sized
    # virtual region.
    assert_delegate "MemoryUtil NUL scan" "$MEMORY_DUMP" 'int strlenNT1(long, int)' 'BrowserMemory.lengthNt1'
    assert_delegate "MemoryUtil NUL scan" "$MEMORY_DUMP" 'int strlenNT2(long, int)' 'BrowserMemory.lengthNt2'
    LAZY_INIT_DUMP="$(javap_dump -classpath "$MEMORY_JAR" -p -c 'org.lwjgl.system.MemoryUtil$LazyInit')"
    LAZY_INIT_BLOCK="$(method_block "$LAZY_INIT_DUMP" 'static {};')"
    printf '%s\n' "$LAZY_INIT_BLOCK" | rg -qF 'BrowserMemoryAllocator.instance' \
        || die 'MemoryUtil$LazyInit does not use BrowserMemoryAllocator'
    if printf '%s\n' "$LAZY_INIT_BLOCK" | rg -q 'MemoryManage|Configuration'; then
        die 'MemoryUtil$LazyInit still selects a native allocator'
    fi
    BACKEND_DUMP="$(javap_dump -classpath "$MEMORY_JAR" -p org.lwjgl.system.BrowserMemoryBackend)" \
        || die "BrowserMemoryBackend is missing from $MEMORY_JAR"
    printf '%s\n' "$BACKEND_DUMP" \
        | rg -qF 'public final class org.lwjgl.system.BrowserMemoryBackend implements org.lwjgl.system.MemoryBackend' \
        || die "BrowserMemoryBackend does not implement MemoryBackend"
    printf '%s\n' "$BACKEND_DUMP" \
        | rg -qF 'public static final org.lwjgl.system.BrowserMemoryBackend INSTANCE;' \
        || die "BrowserMemoryBackend.INSTANCE is missing"
fi

# Platform.<clinit> must replace exactly the os.name lookup with "Linux":
# LWJGL 3.4.3 reads java.version first, and replacing that value instead
# makes Platform fail to initialize.
PLATFORM_DUMP="$(javap_dump -classpath "$MEMORY_JAR" -p -c org.lwjgl.system.Platform)"
PLATFORM_CLINIT="$(method_block "$PLATFORM_DUMP" 'static {};')"
printf '%s\n' "$PLATFORM_CLINIT" | awk '
    state == 1 { state = ($2 == "pop") ? 2 : 0; next }
    state == 2 { if (index($0, "// String Linux")) replaced[property] = 1; state = 0 }
    index($0, "java/lang/System.getProperty") { property = last; state = 1; next }
    index($0, " ldc") && index($0, "// String ") { value = $0; sub(/.*String /, "", value); last = value }
    END {
        if (!("os.name" in replaced)) exit 1
        for (key in replaced) if (key != "os.name") exit 1
    }
' || die "Platform.<clinit> does not replace exactly the os.name lookup with Linux"

# jtracy 1.14 (26.3) adds profiler section categories; the browser client must
# never reach their TracyBindings natives.
JTRACY_PATH="$(gaius_library_path "com.mojang:jtracy")"
JTRACY_JAR="$overlay_directory/libraries/$JTRACY_PATH"
[[ -f "$JTRACY_JAR" ]] || die "missing $JTRACY_JAR; run build-overlays.sh first"
TRACY_DUMP="$(javap_dump -classpath "$JTRACY_JAR" -p -c com.mojang.jtracy.TracyClient)"
TRACY_AVAILABLE="$(method_block "$TRACY_DUMP" 'boolean isAvailable()')"
printf '%s\n' "$TRACY_AVAILABLE" | rg -q 'iconst_0' || die "TracyClient.isAvailable() does not return false"
SECTION_BLOCK="$(method_block "$TRACY_DUMP" 'com.mojang.jtracy.SectionCategory createSectionCategory(java.lang.String)')"
if [[ -n "$SECTION_BLOCK" ]]; then
    printf '%s\n' "$SECTION_BLOCK" | rg -qF 'SectionCategory.UNAVAILABLE' \
        || die "TracyClient.createSectionCategory does not return SectionCategory.UNAVAILABLE"
    if printf '%s\n' "$SECTION_BLOCK" | rg -qF 'TracyBindings'; then
        die "TracyClient.createSectionCategory still reaches TracyBindings natives"
    fi
fi

printf 'lwjgl browser compatibility smoke passed: %s MemoryUtil, %s query generators, %s query deletes, %s draw-buffer and %s clear-buffer methods\n' \
    "$MEMORY_SHAPE" "$QUERY_METHODS" "$DELETE_QUERY_METHODS" "$DRAW_BUFFER_METHODS" "$CLEAR_BUFFER_METHODS"
