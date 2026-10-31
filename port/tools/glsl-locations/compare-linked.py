#!/usr/bin/env python3
"""Compares two shaderxlat-link corpora pipeline by pipeline (run.sh).

    compare-linked.py [--debug-apart] A_DIR B_DIR

For every pipeline: link status, bind group entries, lenient fixes and the linked SPIR-V of both
stages. By default the modules must be byte-identical; with --debug-apart they may differ in the
debug instructions GlslLocations.Result.restore puts back (OpSource text, OpSourceContinued,
OpSourceExtension, OpModuleProcessed). Also counts the pipelines whose modules are identical
without any debug instruction (what gaius-shaderxlat's translate reads after its strip pass).
Exit status 1 on any difference beyond what the mode allows.
"""
import json
import struct
import sys
from pathlib import Path

SOURCE_DEBUG = {2, 3, 4, 330}  # OpSourceContinued, OpSource, OpSourceExtension, OpModuleProcessed
ALL_DEBUG = SOURCE_DEBUG | {5, 6, 7, 8, 317}  # + OpName, OpMemberName, OpString, OpLine, OpNoLine


def without(data, ops):
    words = struct.unpack('<%dI' % (len(data) // 4), data)
    kept, i = list(words[:5]), 5
    while i < len(words):
        count, op = words[i] >> 16, words[i] & 0xFFFF
        if op not in ops:
            kept.extend(words[i:i + count])
        i += count
    return tuple(kept)


def main():
    args = sys.argv[1:]
    debug_apart = '--debug-apart' in args
    a_dir, b_dir = [Path(a) for a in args if not a.startswith('--')]
    a = json.loads((a_dir / 'corpus.json').read_text(encoding='utf-8'))
    b = json.loads((b_dir / 'corpus.json').read_text(encoding='utf-8'))
    by_location = {p['location']: p for p in b['pipelines']}
    total = identical = identical_apart = identical_stripped = 0
    problems = []
    for p in a['pipelines']:
        total += 1
        q = by_location.get(p['location'])
        if q is None:
            problems.append('missing in B: ' + p['location'])
            continue
        if p.get('error') != q.get('error'):
            problems.append('link status differs: %s A=%s B=%s' % (p['location'], p.get('error'), q.get('error')))
            continue
        if p.get('error') is not None:
            continue
        if p.get('uniforms') != q.get('uniforms') or p.get('frontend') != q.get('frontend'):
            problems.append('entries or fixes differ: ' + p['location'])
            continue
        same = same_apart = same_stripped = True
        for sa, sb in zip(p['shaders'], q['shaders']):
            da = (a_dir / 'spirv' / (sa['spirv'] + '.spv')).read_bytes()
            db = (b_dir / 'spirv' / (sb['spirv'] + '.spv')).read_bytes()
            same &= da == db
            same_apart &= without(da, SOURCE_DEBUG) == without(db, SOURCE_DEBUG)
            same_stripped &= without(da, ALL_DEBUG) == without(db, ALL_DEBUG)
        identical += same
        identical_apart += same_apart
        identical_stripped += same_stripped
        if not (same_apart if debug_apart else same):
            problems.append('linked modules differ: ' + p['location'])
    print('pipelines=%d byte-identical=%d identical-apart-from-source-debug=%d identical-stripped=%d problems=%d'
          % (total, identical, identical_apart, identical_stripped, len(problems)))
    print('A: %s' % json.dumps(a['summary'], sort_keys=True))
    print('B: %s' % json.dumps(b['summary'], sort_keys=True))
    for problem in problems[:40]:
        print('  ' + problem)
    sys.exit(1 if problems else 0)


if __name__ == '__main__':
    main()
