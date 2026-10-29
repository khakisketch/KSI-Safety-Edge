"""Exercise scrub's build-failure path in an isolated fixture, never Gradle/APKs."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

class ScrubLogTest(unittest.TestCase):
    def test_build_failure_preserves_prior_log_and_uses_unique_logs(self):
        temp_root = '/tmp/opencode' if Path('/tmp/opencode').is_dir() and os.access('/tmp/opencode', os.W_OK) else None
        with tempfile.TemporaryDirectory(prefix='ksi-scrub-test-', dir=temp_root) as tmp:
            root = Path(tmp)
            (root / 'scripts').mkdir()
            logs = root / 'logs'
            logs.mkdir()
            script = (ROOT / 'scripts/release_scrub_check.sh').read_text()
            # Relocate only the legacy scratch root; run the real script logic.
            script = script.replace('/tmp/opencode', str(logs))
            (root / 'scripts/release_scrub_check.sh').write_text(script)
            app = root / 'android-rc/rc-app'
            (app / 'src/main/res/xml').mkdir(parents=True)
            (app / 'src/debug/res/xml').mkdir(parents=True)
            (app / 'src/main/java/ksi/safetyedge/rc').mkdir(parents=True)
            (app / 'build.gradle.kts').write_text('versionCode = 1\nversionName = "1.0"\n')
            (app / 'src/main/res/xml/network_security_config.xml').write_text('cleartextTrafficPermitted="false"')
            (app / 'src/debug/res/xml/network_security_config.xml').write_text('cleartextTrafficPermitted="true"')
            (app / 'src/main/AndroidManifest.xml').write_text('android:networkSecurityConfig="@xml/network_security_config"')
            (app / 'src/main/java/ksi/safetyedge/rc/AppConfig.kt').write_text('getString("core_url", "")')
            gradle = root / 'android-rc/gradlew'
            gradle.write_text('#!/bin/sh\necho isolated-build-failure\nexit 9\n')
            gradle.chmod(0o755)
            original = logs / 'ksi-release-bench-build.log'
            original.write_text('existing user data\n')
            env = dict(os.environ, SCRUB_FLAVOR='bench', SKIP_APK_BUILD='0')
            outputs = []
            for _ in range(2):
                result = subprocess.run(['bash', str(root / 'scripts/release_scrub_check.sh')], env=env, capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn('[FAIL] A1: release build failed', result.stdout)
                self.assertEqual(original.read_text(), 'existing user data\n')
                outputs.append(result.stdout)
            created = set(logs.iterdir()) - {original}
            self.assertEqual(len(created), 2)
            for log in created:
                self.assertEqual(log.read_text(), 'isolated-build-failure\n')
                self.assertEqual(sum(str(log) in output for output in outputs), 1)

if __name__ == '__main__':
    unittest.main()
