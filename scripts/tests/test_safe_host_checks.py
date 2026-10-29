"""Non-destructive host regression checks; no Core or device access."""
import os
from pathlib import Path
import socket
import signal
import time
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SERVER = Path(os.environ.get('MOP_SERVER', ROOT / 'edge/build/mop_server')).resolve()

def read_children(path):
    try:
        return path.read_text().split()
    except FileNotFoundError as exc:
        raise RuntimeError('loopback process exited before its children could be inspected') from exc

class SafeHostChecks(unittest.TestCase):
    def test_missing_proc_children_has_meaningful_failure(self):
        with tempfile.TemporaryDirectory(prefix='ksi-proc-test-') as td:
            with self.assertRaisesRegex(RuntimeError, 'exited before its children'):
                read_children(Path(td) / 'missing')

    def test_work_dir_refuses_existing_and_symlinks(self):
        with tempfile.TemporaryDirectory(prefix='ksi-safety-test-') as td:
            root = Path(td)
            old = root / 'old'
            old.mkdir()
            marker = old / 'marker'
            marker.write_text('preserve')
            link = root / 'link'
            link.symlink_to(old, target_is_directory=True)
            dangling = root / 'dangling'
            dangling.symlink_to(root / 'absent')
            for script in ('run_aircraft_free_mop_chain.sh', 'run_aircraft_free_evidence_chain.sh'):
                for path in (old, link, dangling):
                    result = subprocess.run(['bash', str(ROOT / 'scripts' / script), '--core-url', 'unused', '--token', 'unused', '--work-dir', str(path)], capture_output=True, text=True, timeout=10)
                    self.assertNotEqual(result.returncode, 0, result.stdout)
                    self.assertIn('work directory', result.stderr)
                    self.assertEqual(marker.read_text(), 'preserve')
                    self.assertTrue(link.is_symlink())
                    self.assertTrue(dangling.is_symlink())

    def test_occupied_port_is_not_contacted(self):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen()
            listener.settimeout(0.3)
            port = str(listener.getsockname()[1])
            env = dict(os.environ, MOP_PORT=port, MOP_PYTHON=sys.executable)
            sentinel = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)',
                                         'mop_server', '--port', port])
            try:
                result = subprocess.run(['bash', str(ROOT / 'scripts/mop_loopback_check.sh')], env=env, capture_output=True, text=True, timeout=15)
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn('bind failed', result.stdout)
                self.assertIsNone(sentinel.poll(), 'unrelated process was terminated')
                with self.assertRaises(TimeoutError):
                    listener.accept()
            finally:
                if sentinel.poll() is None:
                    sentinel.terminate()
                sentinel.wait(timeout=5)

    @unittest.skipUnless(Path('/proc/self/task').exists(), 'Linux procfs required')
    def test_interrupt_reaps_only_own_server(self):
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            port = sock.getsockname()[1]
        env = dict(os.environ, MOP_PORT=str(port), MOP_PYTHON=sys.executable)
        p = subprocess.Popen(['bash', str(ROOT / 'scripts/mop_loopback_check.sh')], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            children = Path(f'/proc/{p.pid}/task/{p.pid}/children')
            server_pid = None
            for _ in range(100):
                if p.poll() is not None:
                    break
                try:
                    child_pids = read_children(children)
                except RuntimeError as exc:
                    self.fail(f'{exc}; loopback exit status: {p.poll()}')
                for pid in child_pids:
                    try:
                        cmd = Path(f'/proc/{pid}/cmdline').read_bytes()
                        if b'mop_server' in cmd:
                            server_pid = int(pid)
                            break
                    except FileNotFoundError:
                        pass
                if server_pid:
                    break
                time.sleep(.02)
            self.assertIsNotNone(server_pid)
            p.send_signal(signal.SIGTERM)
            p.wait(timeout=10)
            self.assertEqual(p.returncode, 143)
            self.assertFalse(Path(f'/proc/{server_pid}').exists())
        finally:
            if p.poll() is None:
                p.terminate()
                p.wait(timeout=10)

    def test_invalid_bind_and_port(self):
        for args in (['--bind-address', 'not-an-address'], ['--port', '65536'], ['--port', 'bad']):
            p = subprocess.run([str(SERVER), *args], capture_output=True, timeout=5)
            self.assertEqual(p.returncode, 2)

    def test_real_loopback_with_injected_python(self):
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            port = sock.getsockname()[1]
        env = dict(os.environ, MOP_PORT=str(port), MOP_PYTHON=sys.executable)
        result = subprocess.run(['bash', str(ROOT / 'scripts/mop_loopback_check.sh')],
                                env=env, capture_output=True, text=True, timeout=45)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('PASS:', result.stdout)

if __name__ == '__main__':
    unittest.main()
