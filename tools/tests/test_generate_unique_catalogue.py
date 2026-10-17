"""Public definition fixtures only; no client login or player data is required."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class GeneratorTest(unittest.TestCase):
    def test_revision_provenance_and_deterministic_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cache = root / 'cache.json'
            cache.write_text(json.dumps({
                'kind': 'candidate-cache-definitions-not-verified-eligibility',
                'runeLiteVersion': '9.1.0', 'gameRevision': 999,
                'missingCandidateMetrics': [], 'pages': [
                    {'structId': 1, 'name': 'Test boss', 'itemEnumId': 2, 'itemIds': [3]}
                ]}), encoding='utf-8')
            items = root / 'ItemID.java'
            items.write_text('\t/**\n\t * Test unique\n\t */\n\tpublic static final int TEST_UNIQUE = 3;\n', encoding='utf-8')
            candidates = root / 'candidates.json'
            candidates.write_text(json.dumps({'entries': [{'metricId': 'TEST_BOSS', 'candidatePageStructId': 1}]}), encoding='utf-8')
            output = root / 'result.json'
            command = [sys.executable, str(Path(__file__).resolve().parents[1] / 'generate_unique_catalogue.py'),
                       '--cache-export', str(cache), '--item-definitions', str(items), '--candidates', str(candidates),
                       '--output', str(output), '--source-runelite-revision', 'a' * 40,
                       '--source-cs2-revision', 'b' * 40, '--catalogue-revision', '7']
            subprocess.run(command, check=True, capture_output=True)
            first = output.read_bytes()
            document = json.loads(first)
            self.assertEqual('game-999-uniques-7', document['version'])
            self.assertEqual('9.1.0', document['runeLiteVersion'])
            self.assertEqual('a' * 40, document['sourceRuneLiteRevision'])
            self.assertEqual('b' * 40, document['sourceCs2Revision'])
            self.assertEqual(hashlib.sha256(cache.read_bytes()).hexdigest(), document['cacheDefinitionSha256'])
            subprocess.run(command, check=True, capture_output=True)
            self.assertEqual(first, output.read_bytes())
            subprocess.run(command[:-1] + ['8'], check=True, capture_output=True)
            self.assertEqual('game-999-uniques-8', json.loads(output.read_bytes())['version'])
            before = output.read_bytes()
            cache_data = json.loads(cache.read_bytes())
            cache_data['missingCandidateMetrics'] = ['NEW_BOSS']
            cache.write_text(json.dumps(cache_data), encoding='utf-8')
            self.assertNotEqual(0, subprocess.run(command, capture_output=True).returncode)
            self.assertEqual(before, output.read_bytes())


if __name__ == '__main__':
    unittest.main()
