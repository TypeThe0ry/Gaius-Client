"""Summarize CDP soak evidence without third-party packages or downloads."""
import argparse
from array import array
import csv
import json
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from xml.sax.saxutils import escape


class _JsonStream:
    """Read CDP's flat arrays with bounded input buffers, never a whole JSON DOM."""

    def __init__(self, stream, chunk_size=1024 * 1024):
        self.stream, self.chunk_size = stream, chunk_size
        self.buffer, self.pos, self.eof = '', 0, False
        self.decoder = json.JSONDecoder()

    def fill(self):
        chunk = self.stream.read(self.chunk_size)
        self.buffer = self.buffer[self.pos:] + chunk
        self.pos = 0
        self.eof = not chunk

    def peek(self):
        while True:
            while self.pos < len(self.buffer) and self.buffer[self.pos].isspace():
                self.pos += 1
            if self.pos < len(self.buffer):
                return self.buffer[self.pos]
            if self.eof:
                raise ValueError('truncated heap snapshot')
            self.fill()

    def expect(self, char):
        if self.peek() != char:
            raise ValueError(f'heap snapshot: expected {char!r}')
        self.pos += 1

    def value(self):
        self.peek()
        while True:
            try:
                value, end = self.decoder.raw_decode(self.buffer, self.pos)
                if isinstance(value, (int, float)) and end == len(self.buffer) and not self.eof:
                    self.fill()
                    continue
                self.pos = end
                return value
            except json.JSONDecodeError:
                if self.eof:
                    raise ValueError('invalid or truncated heap snapshot JSON') from None
                self.fill()

    def integers(self):
        """Split only complete comma-delimited tokens in each input chunk."""
        self.expect('[')
        any_value = False
        while True:
            close = self.buffer.find(']', self.pos)
            if close >= 0:
                end, finished = close, True
            else:
                end, finished = self.buffer.rfind(',', self.pos), False
                if end < self.pos:
                    if self.eof:
                        raise ValueError('truncated heap snapshot numeric array')
                    self.fill()
                    continue
            segment = self.buffer[self.pos:end]
            self.pos = end + 1
            if segment.strip():
                for token in segment.split(','):
                    token = token.strip()
                    if not token or not token.isascii() or not token.isdecimal():
                        raise ValueError('heap snapshot numeric arrays require unsigned integers')
                    any_value = True
                    yield int(token)
            elif not finished or any_value:
                raise ValueError('invalid empty heap snapshot numeric value')
            if finished:
                return
            self.fill()

    def skip(self):
        """Skip unused arrays/objects, respecting quoted and escaped delimiters."""
        first = self.peek()
        if first not in '[{':
            self.value()
            return
        depth, quoted, escaped = 0, False, False
        while True:
            # Regex scans ordinary text/numeric arrays in C; Python sees delimiters only.
            for match in re.finditer(r'["\\{}\[\]]', self.buffer, re.ASCII):
                if match.start() < self.pos:
                    continue
                c = match.group()
                if escaped:
                    escaped = False
                self.pos = match.end()
                if quoted:
                    if c == '\\':
                        if self.pos < len(self.buffer):
                            self.pos += 1
                        else:
                            escaped = True
                    elif c == '"':
                        quoted = False
                elif c == '"':
                    quoted = True
                elif c in '[{':
                    depth += 1
                elif c in ']}':
                    depth -= 1
                    if depth == 0:
                        return
            self.pos = len(self.buffer)
            if self.eof:
                raise ValueError('truncated heap snapshot value')
            self.fill()
            if escaped and self.buffer:
                self.pos = 1
                escaped = False

    def strings(self):
        self.expect('[')
        if self.peek() == ']':
            self.pos += 1
            return
        while True:
            value = self.value()
            if not isinstance(value, str):
                raise ValueError('heap snapshot string table contains a non-string')
            yield value
            if self.peek() == ']':
                self.pos += 1
                return
            self.expect(',')

    def finish(self):
        while True:
            if self.buffer[self.pos:].strip():
                raise ValueError('unexpected data after heap snapshot')
            if self.eof:
                return
            self.pos = len(self.buffer)
            self.fill()


def _append_index(values, value):
    # String/array indices normally fit uint32; preserve unusually large indices.
    if values.typecode == 'I' and value >= 2**32:
        values = array('Q', values)
    values.append(value)
    return values


@dataclass
class _Snapshot:
    meta: dict
    ids: array
    names: array
    sizes: array
    offsets: array
    edge_kinds: array
    edge_names: array
    edge_nodes: array
    strings: list
    counts: Counter
    totals: Counter


def snapshot(path, graph=True, chunk_size=1024 * 1024):
    """Keep only graph columns needed for retention; discard baseline edges."""
    ids, names, sizes, offsets = array('Q'), array('I'), array('Q'), array('Q', [0])
    edge_kinds, edge_names, edge_nodes = array('B'), array('I'), array('I')
    strings, header, seen = [], None, set()
    expected_edges = 0
    with path.open(encoding='utf-8') as stream:
        reader = _JsonStream(stream, chunk_size)
        reader.expect('{')
        while reader.peek() != '}':
            key = reader.value()
            if key in seen:
                raise ValueError(f'duplicate heap snapshot field: {key}')
            seen.add(key)
            reader.expect(':')
            if key == 'snapshot':
                header = reader.value()
                nf, ef = header['meta']['node_fields'], header['meta']['edge_fields']
            elif key == 'nodes':
                if header is None:
                    raise ValueError('heap snapshot metadata must precede nodes')
                positions = {nf.index(k): k for k in ('id', 'name', 'self_size', 'edge_count')}
                count = 0
                for count, value in enumerate(reader.integers(), 1):
                    field = positions.get((count - 1) % len(nf))
                    if field == 'id':
                        ids.append(value)
                    elif field == 'name':
                        names = _append_index(names, value)
                    elif field == 'self_size':
                        sizes.append(value)
                    elif field == 'edge_count':
                        expected_edges += value
                        if graph:
                            offsets.append(expected_edges)
                if count % len(nf) or len(ids) != header['node_count']:
                    raise ValueError('heap snapshot node count does not match metadata')
            elif key == 'edges' and graph:
                if header is None or 'nodes' not in seen:
                    raise ValueError('heap snapshot metadata and nodes must precede edges')
                positions = {ef.index(k): k for k in ('type', 'name_or_index', 'to_node')}
                count = 0
                for count, value in enumerate(reader.integers(), 1):
                    field = positions.get((count - 1) % len(ef))
                    if field == 'type':
                        edge_kinds.append(value)
                    elif field == 'name_or_index':
                        edge_names = _append_index(edge_names, value)
                    elif field == 'to_node':
                        if value % len(nf) or value // len(nf) >= len(ids):
                            raise ValueError('heap snapshot edge destination is invalid')
                        edge_nodes = _append_index(edge_nodes, value // len(nf))
                if count % len(ef) or len(edge_nodes) != header['edge_count'] or offsets[-1] != len(edge_nodes):
                    raise ValueError('heap snapshot edge count does not match metadata')
            elif key == 'strings':
                strings.extend(reader.strings())
            else:
                reader.skip()
            if reader.peek() == '}':
                break
            reader.expect(',')
        reader.expect('}')
        reader.finish()
    if not {'snapshot', 'nodes', 'edges', 'strings'} <= seen:
        raise ValueError('heap snapshot is missing a required field')
    if not ids:
        raise ValueError('heap snapshot has no root node')
    if expected_edges != header['edge_count']:
        raise ValueError('heap snapshot edge count does not match metadata')
    counts, totals = Counter(), Counter()
    for name_index, size in zip(names, sizes):
        name = strings[name_index]
        counts[name] += 1
        totals[name] += size
    if not graph:
        # Baseline comparisons need IDs and type totals only. Release name/size
        # columns and the string-index table before allocating the ID hash table.
        names, sizes, offsets, strings = array('I'), array('Q'), array('Q'), []
    return _Snapshot(header['meta'], ids, names, sizes, offsets, edge_kinds,
                     edge_names, edge_nodes, strings, counts, totals)


class _CompactIds:
    """Unsigned ID set with a fixed compact table, avoiding Python int/set objects."""

    def __init__(self, values):
        capacity = 1 << max(1, (len(values) * 2 - 1).bit_length())
        self.table = array('Q', [0]) * capacity
        self.mask, self.shift = capacity - 1, 64 - (capacity.bit_length() - 1)
        self.has_zero = False
        for value in values:
            if value == 0:
                self.has_zero = True
                continue
            slot = self._slot(value)
            while self.table[slot] not in (0, value):
                slot = (slot + 1) & self.mask
            self.table[slot] = value

    def _slot(self, value):
        return ((value * 11400714819323198485) & (2**64 - 1)) >> self.shift

    def __contains__(self, value):
        if value == 0:
            return self.has_zero
        slot = self._slot(value)
        while self.table[slot]:
            if self.table[slot] == value:
                return True
            slot = (slot + 1) & self.mask
        return False


def compare_snapshots(before, after, output):
    baseline = snapshot(before, graph=False)
    c0, s0 = baseline.counts, baseline.totals
    baseline_ids = _CompactIds(baseline.ids)
    del baseline
    data = snapshot(after)
    c1, s1 = data.counts, data.totals
    growth = sorted(((n, c1[n]-c0[n], s1[n]-s0[n], c1[n], s1[n]) for n in c1), key=lambda x: x[2], reverse=True)
    with output.with_suffix('.csv').open('w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(['type', 'countDelta', 'selfBytesDelta', 'finalCount', 'finalSelfBytes'])
        w.writerows(growth[:100])
    # Root-to-object strong retaining paths for the largest growing named types.
    edge_types = data.meta['edge_types'][data.meta['edge_fields'].index('type')]
    node_type = 'I' if len(data.ids) < 2**32 - 1 else 'Q'
    edge_type = 'I' if len(data.edge_nodes) < 2**32 - 1 else 'Q'
    unseen = 2**(array(node_type).itemsize * 8) - 1
    parents = array(node_type, [unseen]) * len(data.ids)
    parent_edges = array(edge_type, [0]) * len(data.ids)
    parents[0] = 0
    q, head = array(node_type, [0]), 0
    targets = {n for n, dc, ds, _, _ in growth[:12] if ds > 0 and dc > 0}
    found = {}
    while head < len(q) and len(found) < len(targets):
        node = q[head]
        head += 1
        name = data.strings[data.names[node]]
        if name in targets and name not in found and data.ids[node] not in baseline_ids:
            chain, cur = [], node
            while len(chain) < 32:
                via = None
                if cur != 0:
                    edge = parent_edges[cur]
                    kind = edge_types[data.edge_kinds[edge]]
                    label = data.edge_names[edge]
                    label = str(label) if kind in ('element', 'hidden') else data.strings[label]
                    via = kind + ':' + label
                chain.append({'type': data.strings[data.names[cur]], 'selfBytes': data.sizes[cur], 'via': via})
                if cur == 0:
                    break
                cur = parents[cur]
            found[name] = list(reversed(chain))
        for pos in range(data.offsets[node], data.offsets[node+1]):
            kind = edge_types[data.edge_kinds[pos]]
            if kind == 'weak':
                continue
            dest = data.edge_nodes[pos]
            if parents[dest] == unseen:
                parents[dest] = node
                parent_edges[dest] = pos
                q.append(dest)
    output.with_suffix('.json').write_text(json.dumps({'before':str(before),'after':str(after),'growth':growth[:30],'retainingPaths':found},ensure_ascii=False,indent=2),encoding='utf-8')
    return growth[:8]


def trend(values):
    values = [(t, v) for t, v in values if isinstance(v, (int, float))]
    if len(values) < 4:
        return {'available':False}
    rises = sum(b[1] > a[1] for a, b in zip(values, values[1:]))
    first, last = values[0][1], values[-1][1]
    minimum = min(v for _, v in values)
    return {'available':True,'start':first,'end':last,'min':minimum,'max':max(v for _,v in values),
            'delta':last-first,'risingFraction':rises/(len(values)-1),
            'suspectedMonotonicGrowth':rises/(len(values)-1)>=0.8 and last-first>max(16*1024**2,first*0.2)}


def chart(rows, path, title):
    width, height = 1100, 700
    panels = [('jsHeapBytes','JS heap MiB',1024**2),('embedderHeapBytes','Embedder heap MiB',1024**2),('loadedChunks','Loaded chunks',1),('fps','Game FPS',1)]
    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}"><rect width="100%" height="100%" fill="#111827"/><text x="30" y="30" fill="white" font-size="20">{escape(title)}</text>']
    for i,(key,label,scale) in enumerate(panels):
        x,y=70+(i%2)*540,90+(i//2)*305
        points=[]
        for r in rows:
            try: points.append((float(r['seconds'])/60,float(r[key])/scale))
            except (ValueError,KeyError): pass
        if not points: continue
        xmax=max(t for t,v in points) or 1
        ymax=max(v for t,v in points)*1.1 or 1
        coords=' '.join(f'{x+t/xmax*440:.1f},{y+220-v/ymax*220:.1f}' for t,v in points)
        svg.extend([f'<text x="{x}" y="{y-20}" fill="white" font-size="16">{label}</text>',f'<path d="M{x} {y} V{y+220} H{x+440}" fill="none" stroke="#9ca3af"/>',f'<polyline points="{coords}" fill="none" stroke="#38bdf8" stroke-width="2"/>',f'<text x="{x}" y="{y+245}" fill="white">0 — {xmax:.1f} min; max {ymax/1.1:.1f}</text>'])
    svg.append('</svg>')
    path.write_text(''.join(svg),encoding='utf-8')


def main():
    p=argparse.ArgumentParser()
    p.add_argument('output',type=Path)
    args=p.parse_args()
    summary=[]
    for run in sorted(args.output.iterdir()):
        if not run.is_dir() or (run/'PILOT.txt').exists() or not (run/'soak-samples.jsonl').exists(): continue
        samples=[json.loads(l) for l in (run/'soak-samples.jsonl').read_text().splitlines()]
        report=json.loads((run/'report.json').read_text()) if (run/'report.json').exists() else {}
        row={'name':run.name,'samples':len(samples),'durationSeconds':samples[-1]['seconds'] if samples else 0,
             'harnessVerdict':report.get('verdict','RUNNING'),'failedChecks':report.get('failed',[]),
             'jsHeap':trend([(s['seconds'],s['heap'].get('usedSize')) for s in samples]),
             'embedderHeap':trend([(s['seconds'],s['heap'].get('embedderHeapUsedSize')) for s in samples]),
             'wasmLinear':trend([(s['seconds'],s['memory'].get('wasmLinearBytes')) for s in samples]),
             'workers':{}}
        for target in {w['url'] for s in samples for w in s['workers']}:
            row['workers'][target]={k:trend([(s['seconds'],sum(w.get('heap',{}).get(k,0) for w in s['workers'] if w['url']==target)) for s in samples]) for k in ('usedSize','embedderHeapUsedSize','backingStorageSize')}
        row['probe']=samples[-1]['memory'].get('probe') if samples else None
        row['actions']=json.loads((run/'soak-actions.json').read_text()) if (run/'soak-actions.json').exists() else []
        row['consoleErrors']=samples[-1]['consoleErrors'] if samples else None
        row['uncaughtErrors']=samples[-1]['uncaughtErrors'] if samples else None
        with (run/'soak.csv').open(newline='') as f: chart(list(csv.DictReader(f)),run/'soak.svg',run.name)
        row['snapshots']={}
        for before in run.glob('baseline-*.heapsnapshot'):
            after=run/('final-'+before.name.removeprefix('baseline-'))
            if after.exists():
                try:row['snapshots'][before.stem]=compare_snapshots(before,after,run/('retaining-'+before.stem.removeprefix('baseline-')))
                except Exception as e:row['snapshots'][before.stem]={'error':str(e)}
        summary.append(row)
        print(json.dumps({k:v for k,v in row.items() if k not in ('actions','workers','snapshots')},ensure_ascii=False),flush=True)
    (args.output/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')


if __name__=='__main__':
    main()
