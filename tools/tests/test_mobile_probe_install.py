import os
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools/linuxfs/desktop/droiddeck-desktop'


class MobileInstallTest(unittest.TestCase):
    def test_only_plasma_mobile_is_launched_and_other_session_entries_are_disabled(self):
        text = SCRIPT.read_text()
        self.assertIn('dbus-run-session -- startplasmamobile', text)
        self.assertNotIn('dbus-run-session -- startplasma-wayland', text)
        self.assertNotIn('gnome-shell', text)
        self.assertIn('disabled-desktop-sessions${entry}', text)
        self.assertIn("grep -Eq '^Exec=.*startplasmamobile", text)
        bottom = (ROOT / 'tools/linuxfs/overlay/usr/local/bin/droiddeck-bottom-desktop').read_text()
        self.assertIn('exec /usr/local/bin/droiddeck-desktop', bottom)
        session = (ROOT / 'tools/linuxfs/overlay/usr/local/bin/droiddeck-session').read_text()
        self.assertIn('desktop)\n    exec /usr/local/bin/droiddeck-desktop', session)
        self.assertNotIn('exec pcmanfm', session)

    def test_other_session_entries_are_preserved_but_not_offered(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            sessions = root / 'usr/share/wayland-sessions'
            sessions.mkdir(parents=True)
            (sessions / 'mobile.desktop').write_text('[Desktop Entry]\nExec=startplasmamobile\n')
            (sessions / 'plasma.desktop').write_text('[Desktop Entry]\nExec=startplasma-wayland\n')
            (sessions / 'gnome.desktop').write_text('[Desktop Entry]\nExec=gnome-session\n')
            block = SCRIPT.read_text().split('for sessions in ', 1)[1].split('\nfind "$XDG_RUNTIME_DIR"', 1)[0]
            block = 'for sessions in ' + block
            for path in ['/usr/share/wayland-sessions', '/usr/share/xsessions', '/var/lib/droiddeck/disabled-desktop-sessions']:
                block = block.replace(path, str(root) + path)
            subprocess.run(['bash', '-c', block], check=True)
            self.assertEqual(['mobile.desktop'], [p.name for p in sessions.glob('*.desktop')])
            self.assertEqual(2, len(list((root / 'var/lib/droiddeck/disabled-desktop-sessions').rglob('*.desktop'))))

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
