"""Exercise the real run_control binary with isolated temporary directories."""
from concurrent.futures import ThreadPoolExecutor
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BUILD_DIR = Path(os.environ.get('MOP_SERVER', ROOT / 'edge/build/mop_server')).resolve().parent
RUN_CONTROL = BUILD_DIR / 'run_control'


class RunControlTempTest(unittest.TestCase):
    def run_control(self, tmpdir):
        env = dict(os.environ, TMPDIR=str(tmpdir))
        return subprocess.run([str(RUN_CONTROL)], env=env, capture_output=True, text=True, timeout=20)

    def test_missing_tmpdir_fails_cleanly(self):
        with tempfile.TemporaryDirectory(prefix='ksi-run-control-') as tmp:
            result = self.run_control(Path(tmp) / 'missing')
            self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
            self.assertIn('engine stand-in writable', result.stderr)

    def test_parallel_runs_preserve_existing_files_and_clean_their_own(self):
        with tempfile.TemporaryDirectory(prefix='ksi-run-control-') as tmp:
            marker = Path(tmp) / 'ksi-edge-model-test.bin'
            marker.write_text('existing data')
            with ThreadPoolExecutor(max_workers=2) as pool:
                results = list(pool.map(self.run_control, [tmp, tmp]))
            for result in results:
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(marker.read_text(), 'existing data')
            self.assertEqual({p.name for p in Path(tmp).iterdir()}, {marker.name})


if __name__ == '__main__':
    unittest.main()
