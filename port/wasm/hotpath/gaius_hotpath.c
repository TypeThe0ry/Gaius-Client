// Freestanding WebAssembly hot-path helpers for the browser client.
//
// The Java/TeaVM client remains the source of truth.  This module only handles
// bulk array transforms where crossing the JS <-> Wasm boundary once per batch
// is cheaper than running per-element work in JavaScript.

typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
typedef int i32;
typedef long long i64;
typedef unsigned long long u64;

enum {
    GL_UNSIGNED_BYTE = 0x1401,
    GL_UNSIGNED_SHORT = 0x1403,
    GL_UNSIGNED_INT = 0x1405,
    MAX_INDICES = 1024 * 1024,
    MAX_REPACK_SOURCE_BYTES = 16 * 1024 * 1024,
    MAX_REPACK_OUTPUT_BYTES = 16 * 1024 * 1024,
    MAX_REPACK_LAYOUTS = 64,
    MAX_BIT_STORAGE_VALUES = 1024 * 1024,
    MAX_BIT_STORAGE_LONGS = MAX_BIT_STORAGE_VALUES / 2
};

typedef struct {
    u32 source_offset;
    u32 source_stride;
    u32 bytes;
    u32 target_offset;
} RepackLayout;

__attribute__((aligned(16))) static u8 index_input[MAX_INDICES * 4];
__attribute__((aligned(16))) static u8 index_output[MAX_INDICES * 4];
__attribute__((aligned(16))) static u32 index_scratch[MAX_INDICES];
__attribute__((aligned(16))) static u8 repack_source[MAX_REPACK_SOURCE_BYTES];
__attribute__((aligned(16))) static u8 repack_output[MAX_REPACK_OUTPUT_BYTES];
__attribute__((aligned(16))) static RepackLayout repack_layouts[MAX_REPACK_LAYOUTS];
__attribute__((aligned(16))) static u64 bit_storage_input[MAX_BIT_STORAGE_LONGS];
__attribute__((aligned(16))) static i32 bit_storage_output[MAX_BIT_STORAGE_VALUES];

static u32 last_output_type;
static u32 last_output_bytes;
static u32 last_min_index;
static u32 last_max_index;
static u32 last_repack_bytes;
static u32 last_bit_storage_values;

__attribute__((used, visibility("default")))
u32 gaius_hotpath_version(void) {
    return 3;
}

__attribute__((used, visibility("default")))
u32 gaius_shift_indices_capacity(void) {
    return MAX_INDICES;
}

__attribute__((used, visibility("default")))
u32 gaius_shift_indices_input_ptr(void) {
    return (u32)(unsigned long)index_input;
}

__attribute__((used, visibility("default")))
u32 gaius_shift_indices_output_ptr(void) {
    return (u32)(unsigned long)index_output;
}

__attribute__((used, visibility("default")))
u32 gaius_repack_source_ptr(void) {
    return (u32)(unsigned long)repack_source;
}

__attribute__((used, visibility("default")))
u32 gaius_repack_output_ptr(void) {
    return (u32)(unsigned long)repack_output;
}

__attribute__((used, visibility("default")))
u32 gaius_repack_layouts_ptr(void) {
    return (u32)(unsigned long)repack_layouts;
}

__attribute__((used, visibility("default")))
u32 gaius_repack_source_capacity(void) {
    return MAX_REPACK_SOURCE_BYTES;
}

__attribute__((used, visibility("default")))
u32 gaius_repack_output_capacity(void) {
    return MAX_REPACK_OUTPUT_BYTES;
}

__attribute__((used, visibility("default")))
u32 gaius_repack_layout_capacity(void) {
    return MAX_REPACK_LAYOUTS;
}

__attribute__((used, visibility("default")))
u32 gaius_unpack_bit_storage_input_ptr(void) {
    return (u32)(unsigned long)bit_storage_input;
}

__attribute__((used, visibility("default")))
u32 gaius_unpack_bit_storage_output_ptr(void) {
    return (u32)(unsigned long)bit_storage_output;
}

__attribute__((used, visibility("default")))
u32 gaius_unpack_bit_storage_value_capacity(void) {
    return MAX_BIT_STORAGE_VALUES;
}

__attribute__((used, visibility("default")))
u32 gaius_unpack_bit_storage_long_capacity(void) {
    return MAX_BIT_STORAGE_LONGS;
}

__attribute__((used, visibility("default")))
u32 gaius_shift_indices_last_type(void) {
    return last_output_type;
}

__attribute__((used, visibility("default")))
u32 gaius_shift_indices_last_bytes(void) {
    return last_output_bytes;
}

__attribute__((used, visibility("default")))
u32 gaius_shift_indices_last_min(void) {
    return last_min_index;
}

__attribute__((used, visibility("default")))
u32 gaius_shift_indices_last_max(void) {
    return last_max_index;
}

__attribute__((used, visibility("default")))
u32 gaius_repack_last_bytes(void) {
    return last_repack_bytes;
}

__attribute__((used, visibility("default")))
u32 gaius_unpack_bit_storage_last_values(void) {
    return last_bit_storage_values;
}

static u32 read_index(u32 type, u32 index) {
    if (type == GL_UNSIGNED_BYTE) {
        return ((u8 *)index_input)[index];
    }
    if (type == GL_UNSIGNED_SHORT) {
        return ((u16 *)index_input)[index];
    }
    return ((u32 *)index_input)[index];
}

static void copy_bytes(u8 *target, const u8 *source, u32 count) {
    switch (count) {
        case 1:
            target[0] = source[0];
            return;
        case 2:
            target[0] = source[0];
            target[1] = source[1];
            return;
        case 3:
            target[0] = source[0];
            target[1] = source[1];
            target[2] = source[2];
            return;
        case 4:
            target[0] = source[0];
            target[1] = source[1];
            target[2] = source[2];
            target[3] = source[3];
            return;
        case 8:
            for (u32 i = 0; i < 8; i++) {
                target[i] = source[i];
            }
            return;
        case 12:
            for (u32 i = 0; i < 12; i++) {
                target[i] = source[i];
            }
            return;
        case 16:
            for (u32 i = 0; i < 16; i++) {
                target[i] = source[i];
            }
            return;
        default:
            for (u32 i = 0; i < count; i++) {
                target[i] = source[i];
            }
            return;
    }
}

__attribute__((used, visibility("default")))
i32 gaius_shift_indices(u32 type, u32 count, i32 base_vertex) {
    if (count == 0 || count > MAX_INDICES) {
        return 0;
    }
    if (type != GL_UNSIGNED_BYTE && type != GL_UNSIGNED_SHORT && type != GL_UNSIGNED_INT) {
        return 0;
    }

    u32 min_index = 0xffffffffu;
    u32 max_index = 0;
    for (u32 i = 0; i < count; i++) {
        i64 shifted = (i64)(u64)read_index(type, i) + (i64)base_vertex;
        if (shifted < 0 || shifted > 0xffffffffll) {
            return 0;
        }
        u32 value = (u32)shifted;
        index_scratch[i] = value;
        if (value < min_index) {
            min_index = value;
        }
        if (value > max_index) {
            max_index = value;
        }
    }

    u32 output_type = GL_UNSIGNED_INT;
    if (max_index <= 255u && type == GL_UNSIGNED_BYTE) {
        output_type = GL_UNSIGNED_BYTE;
    } else if (max_index <= 65535u && type != GL_UNSIGNED_INT) {
        output_type = GL_UNSIGNED_SHORT;
    }

    if (output_type == GL_UNSIGNED_BYTE) {
        u8 *out = (u8 *)index_output;
        for (u32 i = 0; i < count; i++) {
            out[i] = (u8)index_scratch[i];
        }
        last_output_bytes = count;
    } else if (output_type == GL_UNSIGNED_SHORT) {
        u16 *out = (u16 *)index_output;
        for (u32 i = 0; i < count; i++) {
            out[i] = (u16)index_scratch[i];
        }
        last_output_bytes = count * 2u;
    } else {
        u32 *out = (u32 *)index_output;
        for (u32 i = 0; i < count; i++) {
            out[i] = index_scratch[i];
        }
        last_output_bytes = count * 4u;
    }

    last_output_type = output_type;
    last_min_index = min_index;
    last_max_index = max_index;
    return 1;
}

__attribute__((used, visibility("default")))
i32 gaius_repack_interleaved(u32 source_bytes, u32 vertex_count, u32 layout_count, u32 output_stride) {
    if (source_bytes > MAX_REPACK_SOURCE_BYTES
            || layout_count == 0
            || layout_count > MAX_REPACK_LAYOUTS
            || output_stride == 0) {
        return 0;
    }
    u64 output_bytes_64 = (u64)vertex_count * (u64)output_stride;
    if (output_bytes_64 > MAX_REPACK_OUTPUT_BYTES) {
        return 0;
    }
    u32 output_bytes = (u32)output_bytes_64;

    for (u32 vertex = 0; vertex < vertex_count; vertex++) {
        for (u32 layout_index = 0; layout_index < layout_count; layout_index++) {
            RepackLayout layout = repack_layouts[layout_index];
            if (layout.bytes == 0 || layout.source_stride == 0) {
                return 0;
            }
            u64 source_offset = (u64)layout.source_offset + (u64)vertex * (u64)layout.source_stride;
            u64 target_offset = (u64)vertex * (u64)output_stride + (u64)layout.target_offset;
            if (source_offset + layout.bytes > source_bytes
                    || target_offset + layout.bytes > output_bytes) {
                return 0;
            }
            copy_bytes(
                    repack_output + (u32)target_offset,
                    repack_source + (u32)source_offset,
                    layout.bytes);
        }
    }

    last_repack_bytes = output_bytes;
    return 1;
}

__attribute__((used, visibility("default")))
i32 gaius_unpack_bit_storage(u32 size, u32 bits, u32 values_per_long) {
    if (size == 0 || size > MAX_BIT_STORAGE_VALUES
            || bits == 0 || bits > 32
            || values_per_long == 0
            || values_per_long != (64u / bits)) {
        return 0;
    }

    u32 long_count = (size + values_per_long - 1u) / values_per_long;
    if (long_count == 0 || long_count > MAX_BIT_STORAGE_LONGS) {
        return 0;
    }

    u64 mask = (1ull << bits) - 1ull;
    u32 out = 0;
    u32 full_cells = size / values_per_long;
    for (u32 cell = 0; cell < full_cells; cell++) {
        u64 value = bit_storage_input[cell];
        for (u32 i = 0; i < values_per_long; i++) {
            bit_storage_output[out + i] = (i32)(value & mask);
            value >>= bits;
        }
        out += values_per_long;
    }

    u32 remaining = size - out;
    if (remaining > 0) {
        u64 value = bit_storage_input[full_cells];
        for (u32 i = 0; i < remaining; i++) {
            bit_storage_output[out + i] = (i32)(value & mask);
            value >>= bits;
        }
    }

    last_bit_storage_values = size;
    return 1;
}
