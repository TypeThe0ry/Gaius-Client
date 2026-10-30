"""Small CDP fixtures exercise streaming boundaries and final-only retaining paths."""
import importlib.util
import json
import sys
import tempfile
import unittest
from array import array
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('soak_report', Path(__file__).with_name('soak-report.py'))
REPORT = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = REPORT
SPEC.loader.exec_module(REPORT)


def fixture(final=False, reordered=False):
    nf = ['type', 'name', 'id', 'self_size', 'edge_count', 'trace_node_id', 'detachedness']
    ef = ['type', 'name_or_index', 'to_node']
    if reordered:
        nf = ['id', 'name', 'edge_count', 'self_size', 'detachedness', 'type', 'trace_node_id']
        ef = ['to_node', 'type', 'name_or_index']
    strings = ['', 'Root', 'Leak', 'Holder "[]\\\n\u6d77\u9e25', 'old', 'holder', 'new', 'weak']
    nodes = [(0, 1, 0, 3 if final else 2), (1, 3, 4, 0), (2, 5, 8, 1 if final else 0)]
    # The first reachable Leak already existed; the weak-only new Leak is larger.
    # The correct path must reach the other new object through Holder's "new" edge.
    if final:
        nodes += [(1, 9, 1000, 0), (1, 2**53 + 7, 4, 0)]
        edges = [(2, 4, 1), (6, 7, 3), (2, 5, 2), (2, 6, 4)]
    else:
        edges = [(2, 4, 1), (2, 5, 2)]
    raw_nodes, raw_edges = [], []
    for typ, node_id, size, count in nodes:
        row = {'type': 3, 'name': typ + 1, 'id': node_id, 'self_size': size,
               'edge_count': count, 'trace_node_id': 0, 'detachedness': 0}
        raw_nodes.extend(row[f] for f in nf)
    for typ, name, dest in edges:
        row = {'type': typ, 'name_or_index': name, 'to_node': dest * len(nf)}
        raw_edges.extend(row[f] for f in ef)
    return {'snapshot': {'meta': {'node_fields': nf, 'edge_fields': ef,
                                 'edge_types': [['context', 'element', 'property', 'internal',
                                                 'hidden', 'shortcut', 'weak'] if f == 'type'
                                                else 'number' for f in ef]},
                         'node_count': len(nodes), 'edge_count': len(edges)},
            'nodes': raw_nodes, 'edges': raw_edges,
            'trace_tree': ['escaped \\" \" ] [ { }', ['\\', '"', {'a': ['x', 2]}]],
            'unknown': 1234567, 'strings': strings}


class StreamingSnapshots(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def write(self, name, data, **kwargs):
        path = self.root / name
        path.write_text(json.dumps(data, ensure_ascii=False, **kwargs), encoding='utf-8')
        return path

    def test_small_chunks_and_reordered_fields(self):
        for reordered in (False, True):
            path = self.write('snapshot.json', fixture(True, reordered), separators=(',', ':'))
            for chunk in (1, 2, 3, 7, 11, 31, 1024):
                with self.subTest(reordered=reordered, chunk=chunk):
                    data = REPORT.snapshot(path, chunk_size=chunk)
                    self.assertEqual(list(data.ids), [1, 3, 5, 9, 2**53 + 7])
                    self.assertEqual(data.counts['Leak'], 3)
                    self.assertEqual(data.totals['Leak'], 1008)
                    self.assertEqual(list(data.offsets), [0, 3, 3, 4, 4, 4])
                    self.assertEqual(list(data.edge_nodes), [1, 3, 2, 4])
                    self.assertIsInstance(data.ids, array)
                    self.assertEqual(data.edge_kinds.itemsize, 1)
                    self.assertEqual(data.names.itemsize, 4)

    def test_baseline_does_not_retain_edges(self):
        path = self.write('snapshot.json', fixture())
        data = REPORT.snapshot(path, graph=False, chunk_size=3)
        self.assertEqual(len(data.edge_nodes), 0)
        self.assertEqual(len(data.edge_names), 0)
        self.assertEqual(len(data.names), 0)
        self.assertEqual(len(data.sizes), 0)
        self.assertEqual(len(data.strings), 0)
        self.assertEqual(data.counts['Leak'], 1)

    def test_final_only_strong_path_and_output_schema(self):
        before = self.write('before.json', fixture())
        after = self.write('after.json', fixture(True, True))
        output = self.root / 'retaining'
        growth = REPORT.compare_snapshots(before, after, output)
        self.assertEqual(growth[0], ('Leak', 2, 1004, 3, 1008))
        data = json.loads(output.with_suffix('.json').read_text())
        self.assertEqual(set(data), {'before', 'after', 'growth', 'retainingPaths'})
        path = data['retainingPaths']['Leak']
        self.assertEqual([p['via'] for p in path], [None, 'property:holder', 'property:new'])
        self.assertEqual(path[-1], {'type': 'Leak', 'selfBytes': 4, 'via': 'property:new'})
        self.assertEqual(output.with_suffix('.csv').read_text().splitlines()[0],
                         'type,countDelta,selfBytesDelta,finalCount,finalSelfBytes')

    def test_compact_ids_zero_large_ids_and_collisions(self):
        values = array('Q', [0, 1, 2**53 + 7, 2**64 - 1, 2**63, 19])
        ids = REPORT._CompactIds(values)
        for value in values:
            self.assertIn(value, ids)
        for value in (2, 3, 2**53 + 6, 2**64 - 2):
            self.assertNotIn(value, ids)
        self.assertEqual(ids.table.itemsize, 8)

    def test_large_element_index_preserved_in_path(self):
        before = self.write('before.json', fixture())
        final = fixture(True)
        final['edges'][-3:] = [1, 2**32 + 9, 4 * 7]
        after = self.write('after.json', final)
        parsed = REPORT.snapshot(after)
        self.assertEqual(parsed.edge_names.typecode, 'Q')
        output = self.root / 'retaining'
        REPORT.compare_snapshots(before, after, output)
        data = json.loads(output.with_suffix('.json').read_text())
        self.assertEqual(data['retainingPaths']['Leak'][-1]['via'], 'element:4294967305')

    def test_corrupt_or_truncated_snapshots_fail(self):
        original = json.dumps(fixture(True))
        for content in (original[:-1], original[:original.index('"edges"') + 20], original + 'trailing'):
            path = self.root / 'bad.json'
            path.write_text(content)
            with self.subTest(content=content[-20:]):
                with self.assertRaises(ValueError):
                    REPORT.snapshot(path, chunk_size=7)
        bad = fixture(True)
        bad['edges'][2] = 999999
        with self.assertRaisesRegex(ValueError, 'destination'):
            REPORT.snapshot(self.write('bad-edge.json', bad))
        bad = fixture(True)
        bad['snapshot']['edge_count'] += 1
        with self.assertRaisesRegex(ValueError, 'edge count'):
            REPORT.snapshot(self.write('bad-count.json', bad))


if __name__ == '__main__':
    unittest.main()
