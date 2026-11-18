import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / 'tools/linuxfs/overlay/usr/local/bin/droiddeck-driver-manager-deps'


class DriverManagerDepsTest(unittest.TestCase):
    def run_helper(self, status, install_status='0'):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            bindir = root / 'bin'
            bindir.mkdir()
            commands = {
                'flock': '#!/bin/sh\nexit 0\n',
                'dpkg-query': '#!/bin/sh\nprintf "%s\\n" "$TEST_STATUS"\n',
                'droiddeck-root': '#!/bin/sh\nprintf "%s\\n" "$*" >> "$HOME/calls"\ncase "$*" in *install*) exit "$INSTALL_STATUS";; esac\n',
            }
            for name, contents in commands.items():
                path = bindir / name
                path.write_text(contents)
                path.chmod(0o755)
            env = dict(os.environ, HOME=folder, PATH=str(bindir) + ':' + os.environ['PATH'],
                       TEST_STATUS=status, INSTALL_STATUS=install_status)
            result = subprocess.run(['bash', str(HELPER)], env=env, capture_output=True, text=True)
            calls = (root / 'calls').read_text().splitlines() if (root / 'calls').exists() else []
            return result, calls

    def test_installed_is_noop(self):
        result, calls = self.run_helper('installed\ninstalled')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(calls, [])

    def test_missing_installs_only_required_packages(self):
        result, calls = self.run_helper('installed')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(calls, ['/usr/bin/apt-get install -y --no-install-recommends pyqt6-dev-tools ubuntu-drivers-common'])

    def test_failed_install_updates_then_retries_and_reports_failure(self):
        result, calls = self.run_helper('not-installed', '1')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(calls), 3)
        self.assertEqual(calls[1], '/usr/bin/apt-get update')
        self.assertEqual(calls[0], calls[2])

    def test_pack_and_staged_helper_are_wired(self):
        packages = (ROOT / 'tools/ubuntu/packages-desktop.txt').read_text().splitlines()
        self.assertTrue({'pyqt6-dev-tools', 'ubuntu-drivers-common'}.issubset(packages))
        session = (ROOT / 'app/src/main/java/com/droiddeck/launcher/session/SessionFiles.kt').read_text()
        self.assertIn('"usr/local/bin/droiddeck-driver-manager-deps" to "usr/local/bin/droiddeck-driver-manager-deps"', session)
        desktop = (ROOT / 'tools/linuxfs/desktop/droiddeck-desktop').read_text()
        self.assertIn('/usr/local/bin/droiddeck-driver-manager-deps &', desktop)


if __name__ == '__main__':
    unittest.main()
