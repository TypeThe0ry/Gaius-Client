#!/usr/bin/env python3
"""Generate the Gaius freestanding Wasm hot-path module without LLVM/lld.

This intentionally mirrors port/wasm/hotpath/gaius_hotpath.c for the small
interface consumed by web/dist/index.html.  It keeps local development usable on
macOS systems that have Apple clang but no wasm-ld/ld.lld.
"""

from __future__ import annotations

import argparse
from pathlib import Path


GL_UNSIGNED_BYTE = 0x1401
GL_UNSIGNED_SHORT = 0x1403
GL_UNSIGNED_INT = 0x1405

MAX_INDICES = 1024 * 1024
MAX_REPACK_SOURCE_BYTES = 16 * 1024 * 1024
MAX_REPACK_OUTPUT_BYTES = 16 * 1024 * 1024
MAX_REPACK_LAYOUTS = 64
MAX_BIT_STORAGE_VALUES = 1024 * 1024
MAX_BIT_STORAGE_LONGS = MAX_BIT_STORAGE_VALUES // 2

INPUT_PTR = 0
OUTPUT_PTR = MAX_INDICES * 4
SCRATCH_PTR = OUTPUT_PTR + MAX_INDICES * 4
REPACK_SOURCE_PTR = SCRATCH_PTR + MAX_INDICES * 4
REPACK_OUTPUT_PTR = REPACK_SOURCE_PTR + MAX_REPACK_SOURCE_BYTES
REPACK_LAYOUTS_PTR = REPACK_OUTPUT_PTR + MAX_REPACK_OUTPUT_BYTES
BIT_STORAGE_INPUT_PTR = REPACK_LAYOUTS_PTR + MAX_REPACK_LAYOUTS * 16
BIT_STORAGE_OUTPUT_PTR = BIT_STORAGE_INPUT_PTR + MAX_BIT_STORAGE_LONGS * 8
MEMORY_PAGES = 1024

I32 = 0x7F
I64 = 0x7E
EMPTY = 0x40


def uleb(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def sleb(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        done = (value == 0 and (byte & 0x40) == 0) or (value == -1 and (byte & 0x40) != 0)
        if done:
            out.append(byte)
            return bytes(out)
        out.append(byte | 0x80)


def vec(items: list[bytes]) -> bytes:
    return uleb(len(items)) + b"".join(items)


def name(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return uleb(len(encoded)) + encoded


def section(section_id: int, payload: bytes) -> bytes:
    return bytes([section_id]) + uleb(len(payload)) + payload


def instr(opcode: int, *operands: bytes | int) -> bytes:
    out = bytearray([opcode])
    for operand in operands:
        if isinstance(operand, int):
            out += uleb(operand)
        else:
            out += operand
    return bytes(out)


def i32_const(value: int) -> bytes:
    if value > 0x7FFFFFFF:
        value -= 0x100000000
    return instr(0x41, sleb(value))


def i64_const(value: int) -> bytes:
    if value > 0x7FFFFFFFFFFFFFFF:
        value -= 0x10000000000000000
    return instr(0x42, sleb(value))


def local_get(index: int) -> bytes:
    return instr(0x20, index)


def local_set(index: int) -> bytes:
    return instr(0x21, index)


def local_tee(index: int) -> bytes:
    return instr(0x22, index)


def global_get(index: int) -> bytes:
    return instr(0x23, index)


def global_set(index: int) -> bytes:
    return instr(0x24, index)


def mem(opcode: int, align: int = 0, offset: int = 0) -> bytes:
    return instr(opcode, uleb(align), uleb(offset))


def ret_i32(value: int) -> bytes:
    return i32_const(value) + bytes([0x0F])


def if_return_zero(condition: bytes) -> bytes:
    return condition + bytes([0x04, EMPTY]) + ret_i32(0) + bytes([0x0B])


def const_func(value: int) -> bytes:
    return func_body([], i32_const(value))


def global_getter(index: int) -> bytes:
    return func_body([], global_get(index))


def func_body(local_groups: list[tuple[int, int]], body: bytes) -> bytes:
    local_decl = vec([uleb(count) + bytes([typ]) for count, typ in local_groups if count])
    payload = local_decl + body + bytes([0x0B])
    return uleb(len(payload)) + payload


def shift_indices_body() -> bytes:
    # params: type=0,count=1,baseVertex=2
    # locals: i=3,value=4,min=5,max=6,outType=7,threshold/original=8
    b = bytearray()
    b += if_return_zero(local_get(1) + bytes([0x45]))  # count == 0
    b += if_return_zero(local_get(1) + i32_const(MAX_INDICES) + bytes([0x4B]))  # count > cap
    invalid_type = (
        local_get(0) + i32_const(GL_UNSIGNED_BYTE) + bytes([0x47])
        + local_get(0) + i32_const(GL_UNSIGNED_SHORT) + bytes([0x47]) + bytes([0x71])
        + local_get(0) + i32_const(GL_UNSIGNED_INT) + bytes([0x47]) + bytes([0x71])
    )
    b += if_return_zero(invalid_type)

    b += i32_const(0) + local_set(3)
    b += i32_const(0xFFFFFFFF) + local_set(5)
    b += i32_const(0) + local_set(6)

    # First pass: read, add baseVertex safely, store scratch, track min/max.
    b += bytes([0x02, EMPTY, 0x03, EMPTY])  # block, loop
    b += local_get(3) + local_get(1) + bytes([0x4F, 0x0D]) + uleb(1)  # i >= count

    # Read source index by GL type.
    b += local_get(0) + i32_const(GL_UNSIGNED_BYTE) + bytes([0x46, 0x04, EMPTY])
    b += i32_const(INPUT_PTR) + local_get(3) + bytes([0x6A]) + mem(0x2D, 0, 0) + local_set(4)
    b += bytes([0x05])
    b += local_get(0) + i32_const(GL_UNSIGNED_SHORT) + bytes([0x46, 0x04, EMPTY])
    b += (
        i32_const(INPUT_PTR)
        + local_get(3) + i32_const(2) + bytes([0x6C, 0x6A])
        + mem(0x2F, 1, 0) + local_set(4)
    )
    b += bytes([0x05])
    b += (
        i32_const(INPUT_PTR)
        + local_get(3) + i32_const(4) + bytes([0x6C, 0x6A])
        + mem(0x28, 2, 0) + local_set(4)
    )
    b += bytes([0x0B, 0x0B])

    # Apply baseVertex without i32 unsigned overflow or negative underflow.
    b += local_get(2) + i32_const(0) + bytes([0x48, 0x04, EMPTY])  # base < 0
    b += i32_const(0) + local_get(2) + bytes([0x6B]) + local_set(8)  # threshold = -base
    b += if_return_zero(local_get(4) + local_get(8) + bytes([0x49]))  # value < threshold
    b += local_get(4) + local_get(8) + bytes([0x6B]) + local_set(4)
    b += bytes([0x05])
    b += local_get(4) + local_set(8)  # original
    b += local_get(4) + local_get(2) + bytes([0x6A]) + local_set(4)
    b += if_return_zero(local_get(4) + local_get(8) + bytes([0x49]))  # wrapped
    b += bytes([0x0B])

    b += (
        i32_const(SCRATCH_PTR)
        + local_get(3) + i32_const(4) + bytes([0x6C, 0x6A])
        + local_get(4)
        + mem(0x36, 2, 0)
    )
    b += local_get(4) + local_get(5) + bytes([0x49, 0x04, EMPTY])
    b += local_get(4) + local_set(5) + bytes([0x0B])
    b += local_get(4) + local_get(6) + bytes([0x4B, 0x04, EMPTY])
    b += local_get(4) + local_set(6) + bytes([0x0B])
    b += local_get(3) + i32_const(1) + bytes([0x6A]) + local_set(3)
    b += bytes([0x0C]) + uleb(0)  # continue loop
    b += bytes([0x0B, 0x0B])  # end loop, block

    # Select output index type.
    b += i32_const(GL_UNSIGNED_INT) + local_set(7)
    b += (
        local_get(6) + i32_const(255) + bytes([0x4D])
        + local_get(0) + i32_const(GL_UNSIGNED_BYTE) + bytes([0x46, 0x71])
        + bytes([0x04, EMPTY])
    )
    b += i32_const(GL_UNSIGNED_BYTE) + local_set(7)
    b += bytes([0x05])
    b += (
        local_get(6) + i32_const(65535) + bytes([0x4D])
        + local_get(0) + i32_const(GL_UNSIGNED_INT) + bytes([0x47, 0x71])
        + bytes([0x04, EMPTY])
    )
    b += i32_const(GL_UNSIGNED_SHORT) + local_set(7)
    b += bytes([0x0B, 0x0B])

    # Second pass: write scratch to output in selected type.
    b += i32_const(0) + local_set(3)
    b += bytes([0x02, EMPTY, 0x03, EMPTY])
    b += local_get(3) + local_get(1) + bytes([0x4F, 0x0D]) + uleb(1)
    b += (
        i32_const(SCRATCH_PTR)
        + local_get(3) + i32_const(4) + bytes([0x6C, 0x6A])
        + mem(0x28, 2, 0)
        + local_set(4)
    )
    b += local_get(7) + i32_const(GL_UNSIGNED_BYTE) + bytes([0x46, 0x04, EMPTY])
    b += i32_const(OUTPUT_PTR) + local_get(3) + bytes([0x6A]) + local_get(4) + mem(0x3A, 0, 0)
    b += bytes([0x05])
    b += local_get(7) + i32_const(GL_UNSIGNED_SHORT) + bytes([0x46, 0x04, EMPTY])
    b += (
        i32_const(OUTPUT_PTR)
        + local_get(3) + i32_const(2) + bytes([0x6C, 0x6A])
        + local_get(4)
        + mem(0x3B, 1, 0)
    )
    b += bytes([0x05])
    b += (
        i32_const(OUTPUT_PTR)
        + local_get(3) + i32_const(4) + bytes([0x6C, 0x6A])
        + local_get(4)
        + mem(0x36, 2, 0)
    )
    b += bytes([0x0B, 0x0B])
    b += local_get(3) + i32_const(1) + bytes([0x6A]) + local_set(3)
    b += bytes([0x0C]) + uleb(0)
    b += bytes([0x0B, 0x0B])

    b += local_get(7) + global_set(0)
    b += local_get(7) + i32_const(GL_UNSIGNED_BYTE) + bytes([0x46, 0x04, EMPTY])
    b += local_get(1) + global_set(1)
    b += bytes([0x05])
    b += local_get(7) + i32_const(GL_UNSIGNED_SHORT) + bytes([0x46, 0x04, EMPTY])
    b += local_get(1) + i32_const(2) + bytes([0x6C]) + global_set(1)
    b += bytes([0x05])
    b += local_get(1) + i32_const(4) + bytes([0x6C]) + global_set(1)
    b += bytes([0x0B, 0x0B])
    b += local_get(5) + global_set(2)
    b += local_get(6) + global_set(3)
    b += ret_i32(1)
    return func_body([(6, I32)], bytes(b))


def repack_body() -> bytes:
    # params: sourceBytes=0,vertexCount=1,layoutCount=2,outputStride=3
    # i32 locals 4..14, i64 locals 15..16
    b = bytearray()
    b += if_return_zero(local_get(0) + i32_const(MAX_REPACK_SOURCE_BYTES) + bytes([0x4B]))
    b += if_return_zero(local_get(2) + bytes([0x45]))
    b += if_return_zero(local_get(2) + i32_const(MAX_REPACK_LAYOUTS) + bytes([0x4B]))
    b += if_return_zero(local_get(3) + bytes([0x45]))

    # outputBytes overflow/capacity check: outputStride > MAX / vertexCount.
    b += local_get(1) + bytes([0x45, 0x04, EMPTY])
    b += i32_const(0) + local_set(4)
    b += bytes([0x05])
    b += if_return_zero(
        local_get(3)
        + i32_const(MAX_REPACK_OUTPUT_BYTES)
        + local_get(1)
        + bytes([0x6E, 0x4B])
    )
    b += local_get(1) + local_get(3) + bytes([0x6C]) + local_set(4)
    b += bytes([0x0B])

    b += i32_const(0) + local_set(5)  # vertex
    b += bytes([0x02, EMPTY, 0x03, EMPTY])  # vertex block/loop
    b += local_get(5) + local_get(1) + bytes([0x4F, 0x0D]) + uleb(1)
    b += i32_const(0) + local_set(6)  # layout index
    b += bytes([0x02, EMPTY, 0x03, EMPTY])  # layout block/loop
    b += local_get(6) + local_get(2) + bytes([0x4F, 0x0D]) + uleb(1)
    b += i32_const(REPACK_LAYOUTS_PTR) + local_get(6) + i32_const(16) + bytes([0x6C, 0x6A]) + local_set(7)
    b += local_get(7) + mem(0x28, 2, 0) + local_set(8)
    b += local_get(7) + mem(0x28, 2, 4) + local_set(9)
    b += local_get(7) + mem(0x28, 2, 8) + local_set(10)
    b += local_get(7) + mem(0x28, 2, 12) + local_set(11)
    b += if_return_zero(local_get(10) + bytes([0x45]))
    b += if_return_zero(local_get(9) + bytes([0x45]))

    # src64 = sourceOffset + vertex * sourceStride
    b += (
        local_get(8) + bytes([0xAD])
        + local_get(5) + bytes([0xAD])
        + local_get(9) + bytes([0xAD])
        + bytes([0x7E, 0x7C])
        + local_set(15)
    )
    # dst64 = vertex * outputStride + targetOffset
    b += (
        local_get(5) + bytes([0xAD])
        + local_get(3) + bytes([0xAD])
        + bytes([0x7E])
        + local_get(11) + bytes([0xAD])
        + bytes([0x7C])
        + local_set(16)
    )
    b += if_return_zero(
        local_get(15)
        + local_get(10) + bytes([0xAD, 0x7C])
        + local_get(0) + bytes([0xAD, 0x56])
    )
    b += if_return_zero(
        local_get(16)
        + local_get(10) + bytes([0xAD, 0x7C])
        + local_get(4) + bytes([0xAD, 0x56])
    )
    b += local_get(15) + bytes([0xA7]) + local_set(13)
    b += local_get(16) + bytes([0xA7]) + local_set(14)

    # Byte copy.
    b += i32_const(0) + local_set(12)
    b += bytes([0x02, EMPTY, 0x03, EMPTY])
    b += local_get(12) + local_get(10) + bytes([0x4F, 0x0D]) + uleb(1)
    b += (
        i32_const(REPACK_OUTPUT_PTR)
        + local_get(14) + bytes([0x6A])
        + local_get(12) + bytes([0x6A])
        + i32_const(REPACK_SOURCE_PTR)
        + local_get(13) + bytes([0x6A])
        + local_get(12) + bytes([0x6A])
        + mem(0x2D, 0, 0)
        + mem(0x3A, 0, 0)
    )
    b += local_get(12) + i32_const(1) + bytes([0x6A]) + local_set(12)
    b += bytes([0x0C]) + uleb(0)
    b += bytes([0x0B, 0x0B])

    b += local_get(6) + i32_const(1) + bytes([0x6A]) + local_set(6)
    b += bytes([0x0C]) + uleb(0)
    b += bytes([0x0B, 0x0B])  # layout loop/block
    b += local_get(5) + i32_const(1) + bytes([0x6A]) + local_set(5)
    b += bytes([0x0C]) + uleb(0)
    b += bytes([0x0B, 0x0B])  # vertex loop/block
    b += local_get(4) + global_set(4)
    b += ret_i32(1)
    return func_body([(11, I32), (2, I64)], bytes(b))


def unpack_bit_storage_body() -> bytes:
    # params: size=0,bits=1,valuesPerLong=2
    # i32 locals: longCount=3,out=4,fullCells=5,cell=6,i=7,remaining=8
    # i64 locals: value=9,mask=10
    b = bytearray()
    b += if_return_zero(local_get(0) + bytes([0x45]))
    b += if_return_zero(local_get(0) + i32_const(MAX_BIT_STORAGE_VALUES) + bytes([0x4B]))
    b += if_return_zero(local_get(1) + bytes([0x45]))
    b += if_return_zero(local_get(1) + i32_const(32) + bytes([0x4B]))
    b += if_return_zero(local_get(2) + bytes([0x45]))
    b += if_return_zero(
        local_get(2)
        + i32_const(64)
        + local_get(1)
        + bytes([0x6E, 0x47])
    )

    b += (
        local_get(0)
        + local_get(2)
        + bytes([0x6A])
        + i32_const(1)
        + bytes([0x6B])
        + local_get(2)
        + bytes([0x6E])
        + local_set(3)
    )
    b += if_return_zero(local_get(3) + bytes([0x45]))
    b += if_return_zero(local_get(3) + i32_const(MAX_BIT_STORAGE_LONGS) + bytes([0x4B]))

    b += i64_const(1) + local_get(1) + bytes([0xAD, 0x86]) + i64_const(1) + bytes([0x7D]) + local_set(10)
    b += i32_const(0) + local_set(4)
    b += local_get(0) + local_get(2) + bytes([0x6E]) + local_set(5)

    # Full cells.
    b += i32_const(0) + local_set(6)
    b += bytes([0x02, EMPTY, 0x03, EMPTY])
    b += local_get(6) + local_get(5) + bytes([0x4F, 0x0D]) + uleb(1)
    b += (
        i32_const(BIT_STORAGE_INPUT_PTR)
        + local_get(6) + i32_const(8) + bytes([0x6C, 0x6A])
        + mem(0x29, 3, 0)
        + local_set(9)
    )
    b += i32_const(0) + local_set(7)
    b += bytes([0x02, EMPTY, 0x03, EMPTY])
    b += local_get(7) + local_get(2) + bytes([0x4F, 0x0D]) + uleb(1)
    b += (
        i32_const(BIT_STORAGE_OUTPUT_PTR)
        + local_get(4) + local_get(7) + bytes([0x6A])
        + i32_const(4) + bytes([0x6C, 0x6A])
        + local_get(9) + local_get(10) + bytes([0x83, 0xA7])
        + mem(0x36, 2, 0)
    )
    b += local_get(9) + local_get(1) + bytes([0xAD, 0x88]) + local_set(9)
    b += local_get(7) + i32_const(1) + bytes([0x6A]) + local_set(7)
    b += bytes([0x0C]) + uleb(0)
    b += bytes([0x0B, 0x0B])
    b += local_get(4) + local_get(2) + bytes([0x6A]) + local_set(4)
    b += local_get(6) + i32_const(1) + bytes([0x6A]) + local_set(6)
    b += bytes([0x0C]) + uleb(0)
    b += bytes([0x0B, 0x0B])

    # Tail cell.
    b += local_get(0) + local_get(4) + bytes([0x6B]) + local_set(8)
    b += local_get(8) + bytes([0x04, EMPTY])
    b += (
        i32_const(BIT_STORAGE_INPUT_PTR)
        + local_get(5) + i32_const(8) + bytes([0x6C, 0x6A])
        + mem(0x29, 3, 0)
        + local_set(9)
    )
    b += i32_const(0) + local_set(7)
    b += bytes([0x02, EMPTY, 0x03, EMPTY])
    b += local_get(7) + local_get(8) + bytes([0x4F, 0x0D]) + uleb(1)
    b += (
        i32_const(BIT_STORAGE_OUTPUT_PTR)
        + local_get(4) + local_get(7) + bytes([0x6A])
        + i32_const(4) + bytes([0x6C, 0x6A])
        + local_get(9) + local_get(10) + bytes([0x83, 0xA7])
        + mem(0x36, 2, 0)
    )
    b += local_get(9) + local_get(1) + bytes([0xAD, 0x88]) + local_set(9)
    b += local_get(7) + i32_const(1) + bytes([0x6A]) + local_set(7)
    b += bytes([0x0C]) + uleb(0)
    b += bytes([0x0B, 0x0B, 0x0B])

    b += local_get(0) + global_set(5)
    b += ret_i32(1)
    return func_body([(6, I32), (2, I64)], bytes(b))


def make_module() -> bytes:
    module = bytearray(b"\x00asm\x01\x00\x00\x00")

    type_entries = [
        b"\x60" + vec([]) + vec([bytes([I32])]),
        b"\x60" + vec([bytes([I32]), bytes([I32]), bytes([I32])]) + vec([bytes([I32])]),
        b"\x60" + vec([bytes([I32]), bytes([I32]), bytes([I32]), bytes([I32])]) + vec([bytes([I32])]),
    ]
    module += section(1, vec(type_entries))

    function_types = [0] * 20 + [1] + [2] + [1]
    module += section(3, vec([uleb(t) for t in function_types]))

    # memory: min/max 1024 pages = 64 MiB.
    module += section(5, vec([b"\x01" + uleb(MEMORY_PAGES) + uleb(MEMORY_PAGES)]))

    global_entries = []
    for _ in range(6):
        global_entries.append(bytes([I32, 0x01]) + i32_const(0) + bytes([0x0B]))
    module += section(6, vec(global_entries))

    exports: list[bytes] = [name("memory") + b"\x02" + uleb(0)]
    export_names = [
        "gaius_hotpath_version",
        "gaius_shift_indices_capacity",
        "gaius_shift_indices_input_ptr",
        "gaius_shift_indices_output_ptr",
        "gaius_repack_source_ptr",
        "gaius_repack_output_ptr",
        "gaius_repack_layouts_ptr",
        "gaius_repack_source_capacity",
        "gaius_repack_output_capacity",
        "gaius_repack_layout_capacity",
        "gaius_unpack_bit_storage_input_ptr",
        "gaius_unpack_bit_storage_output_ptr",
        "gaius_unpack_bit_storage_value_capacity",
        "gaius_unpack_bit_storage_long_capacity",
        "gaius_shift_indices_last_type",
        "gaius_shift_indices_last_bytes",
        "gaius_shift_indices_last_min",
        "gaius_shift_indices_last_max",
        "gaius_repack_last_bytes",
        "gaius_unpack_bit_storage_last_values",
        "gaius_shift_indices",
        "gaius_repack_interleaved",
        "gaius_unpack_bit_storage",
    ]
    for index, export_name in enumerate(export_names):
        exports.append(name(export_name) + b"\x00" + uleb(index))
    module += section(7, vec(exports))

    bodies = [
        const_func(3),
        const_func(MAX_INDICES),
        const_func(INPUT_PTR),
        const_func(OUTPUT_PTR),
        const_func(REPACK_SOURCE_PTR),
        const_func(REPACK_OUTPUT_PTR),
        const_func(REPACK_LAYOUTS_PTR),
        const_func(MAX_REPACK_SOURCE_BYTES),
        const_func(MAX_REPACK_OUTPUT_BYTES),
        const_func(MAX_REPACK_LAYOUTS),
        const_func(BIT_STORAGE_INPUT_PTR),
        const_func(BIT_STORAGE_OUTPUT_PTR),
        const_func(MAX_BIT_STORAGE_VALUES),
        const_func(MAX_BIT_STORAGE_LONGS),
        global_getter(0),
        global_getter(1),
        global_getter(2),
        global_getter(3),
        global_getter(4),
        global_getter(5),
        shift_indices_body(),
        repack_body(),
        unpack_bit_storage_body(),
    ]
    module += section(10, vec(bodies))
    return bytes(module)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "web" / "dist" / "gaius-hotpath.wasm",
    )
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    data = make_module()
    args.output.write_bytes(data)
    print(f"Generated Wasm hot-path module: {args.output} ({len(data)} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
