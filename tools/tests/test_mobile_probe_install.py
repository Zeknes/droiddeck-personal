import os
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools/linuxfs/desktop/droiddeck-desktop'


class MobileInstallTest(unittest.TestCase):
    def test_install_retry_and_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            bindir = root / 'bin'
            bindir.mkdir()
            mobile = root / 'mobile'
            mobile.mkdir()
            (mobile / 'x.deb').write_text('fixture')
            (mobile / 'SHA256SUMS').write_text('')
            def cmd(name, body):
                p = bindir / name
                p.write_text('#!/bin/bash\n' + body + '\n')
                p.chmod(0o755)
            cmd('sha256sum', 'test ! -e "$TEST_DIR/bad-hash"')
            cmd('droiddeck-root', 'echo "$*" >> "$TEST_DIR/calls"; test ! -e "$TEST_DIR/dpkg-fail"')
            cmd('dpkg-query', 'test ! -e "$TEST_DIR/missing" || exit 1; printf "installed\\n%.0s" {1..6}')
            cmd('startplasmamobile', 'exit 0')
            text = SCRIPT.read_text().split('if [ ! -f /var/lib/droiddeck/mobile-installed ]; then', 1)[1].split('\nfind "$XDG_RUNTIME_DIR"', 1)[0]
            script = 'set -u -o pipefail\nif [ ! -f /var/lib/droiddeck/mobile-installed ]; then' + text + '\n'
            script = script.replace('/var/lib/droiddeck/mobile-debs', str(mobile)).replace('/var/lib/droiddeck/mobile-installed', str(root / 'installed'))
            env = dict(os.environ, PATH=str(bindir) + ':' + os.environ['PATH'], TEST_DIR=str(root))
            def run(): return subprocess.run(['bash', '-c', script], env=env, capture_output=True).returncode
            (root / 'bad-hash').touch()
            self.assertNotEqual(run(), 0)
            self.assertFalse((root / 'installed').exists())
            (root / 'bad-hash').unlink()
            (root / 'dpkg-fail').touch()
            self.assertNotEqual(run(), 0)
            self.assertFalse((root / 'installed').exists())
            (root / 'dpkg-fail').unlink()
            (root / 'missing').touch()
            self.assertNotEqual(run(), 0)
            self.assertFalse((root / 'installed').exists())
            (root / 'missing').unlink()
            self.assertEqual(run(), 0)
            self.assertTrue((root / 'installed').exists())
            before = (root / 'calls').read_text()
            self.assertEqual(run(), 0)
            self.assertEqual(before, (root / 'calls').read_text())


if __name__ == '__main__':
    unittest.main()
