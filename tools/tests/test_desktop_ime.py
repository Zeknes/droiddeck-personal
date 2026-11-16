import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import time
import unittest

IME = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-ime'


def message(obj, opcode, payload=b''):
    return struct.pack('<II', obj, ((8 + len(payload)) << 16) | opcode) + payload


def wl_string(value):
    data = value.encode() + b'\0'
    return struct.pack('<I', len(data)) + data + b'\0' * (-len(data) % 4)


class DesktopImeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.session = Path(self.temp.name)
        self.server, client = socket.socketpair(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(self.server.close)
        env = dict(os.environ, BL_LAUNCH_DIR=str(self.session), WAYLAND_SOCKET=str(client.fileno()), DROIDDECK_MOBILE_IME='1')
        self.proc = subprocess.Popen(['python3', str(IME)], env=env, pass_fds=[client.fileno()],
                                     stderr=subprocess.PIPE, text=True)
        client.close()
        self.addCleanup(self.stop)
        self.server.settimeout(5)
        self.assertEqual(self.read(12), message(1, 1, struct.pack('<I', 2)))
        self.server.sendall(message(2, 0, struct.pack('<I', 7) + wl_string('zwp_input_method_v1') + struct.pack('<I', 1)))
        bind = self.read(8 + 4 + len(wl_string('zwp_input_method_v1')) + 8)
        self.assertEqual(bind, message(2, 0, struct.pack('<I', 7) + wl_string('zwp_input_method_v1') + struct.pack('<II', 1, 3)))

    def stop(self):
        if self.proc.poll() is None:
            self.proc.kill()
        self.proc.wait(5)
        self.proc.stderr.close()

    def read(self, size):
        data = b''
        while len(data) < size:
            chunk = self.server.recv(size - len(data))
            if not chunk:
                break
            data += chunk
        return data

    def state(self, seq, timeout=5):
        path = self.session / 'agent/ime.json'
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                snapshot = json.loads(path.read_text())
            except (OSError, ValueError):
                snapshot = None
            if snapshot and snapshot['seq'] >= seq:
                return snapshot
            time.sleep(0.02)
        self.fail('ime.json never reached seq %d' % seq)

    def test_text_the_keyboard_cannot_type_is_committed_through_the_input_method(self):
        self.server.sendall(message(3, 0, struct.pack('<I', 100)))
        self.assertTrue(self.state(1)['textField'])
        self.server.sendall(message(100, 4, struct.pack('<I', 77)))
        (self.session / 'agent/ime-text.json').write_text(json.dumps({'text': '中文', 'seq': 5}))
        expected = message(100, 1, struct.pack('<I', 77) + wl_string('中文'))
        self.assertEqual(self.read(len(expected)), expected)
        (self.session / 'agent/ime-text.json').write_text(json.dumps({'text': '中文', 'seq': 5}))
        self.server.settimeout(0.4)
        with self.assertRaises(socket.timeout):
            self.server.recv(8)

    def test_text_waits_for_the_serial_the_field_commits(self):
        self.server.sendall(message(3, 0, struct.pack('<I', 100)))
        self.assertTrue(self.state(1)['textField'])
        (self.session / 'agent/ime-text.json').write_text(json.dumps({'text': 'x', 'seq': 1}))
        self.server.settimeout(0.4)
        with self.assertRaises(socket.timeout):
            self.server.recv(8)
        self.server.settimeout(5)
        self.server.sendall(message(100, 4, struct.pack('<I', 9)))
        expected = message(100, 1, struct.pack('<I', 9) + wl_string('x'))
        self.assertEqual(self.read(len(expected)), expected)

    def test_focus_records_the_mobile_task_switcher_state_without_text(self):
        script = self.session / 'gdbus'
        script.write_text('#!/bin/sh\ncase "$*" in *isTaskSwitcherVisible*) echo "(true,)" ;; *) echo "(<[\x27mobiletaskswitcher\x27]>,)" ;; esac\n')
        script.chmod(0o755)
        self.proc.terminate()
        self.proc.wait(5)
        self.proc.stderr.close()
        self.server.close()
        self.server, client = socket.socketpair(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(self.server.close)
        env = dict(os.environ, BL_LAUNCH_DIR=str(self.session), WAYLAND_SOCKET=str(client.fileno()),
                   DROIDDECK_IME_GDBUS=str(script), DROIDDECK_MOBILE_IME='1')
        self.proc = subprocess.Popen(['python3', str(IME)], env=env, pass_fds=[client.fileno()], stderr=subprocess.PIPE, text=True)
        client.close()
        self.server.settimeout(5)
        self.read(12)
        self.server.sendall(message(2, 0, struct.pack('<I', 7) + wl_string('zwp_input_method_v1') + struct.pack('<I', 1)))
        self.read(8 + 4 + len(wl_string('zwp_input_method_v1')) + 8)
        self.server.sendall(message(3, 0, struct.pack('<I', 100)))
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            snapshot = self.state(1)
            if snapshot.get('focusSource') == 'mobiletaskswitcher': break
            time.sleep(0.02)
        self.assertTrue(snapshot['textField'])
        self.assertEqual(snapshot['autoShow'], False)
        self.assertEqual(snapshot['focusSource'], 'mobiletaskswitcher')

    def test_pending_query_does_not_delay_field_type_or_confirm_before_two_checks(self):
        import runpy
        from unittest.mock import patch
        module = runpy.run_path(str(IME))
        import threading
        first = threading.Event()
        second = threading.Event()
        release = threading.Event()
        checks = []
        def query():
            checks.append(True)
            (first if len(checks) == 1 else second).set()
            release.wait(2)
            return 'switcher-inactive'
        with patch.dict(os.environ, {'DROIDDECK_MOBILE_IME': '1'}), patch.dict(module['focus_source'].__globals__, {'focus_source': query}):
            report = module['Report'](str(self.session))
            report.write(True, 64, 8)
            self.assertTrue(first.wait(1))
            path = self.session / 'ime.json'
            snapshot = json.loads(path.read_text())
            self.assertEqual((snapshot['purpose'], snapshot['hint'], snapshot['autoShow']), (8, 64, False))
            self.assertEqual(snapshot['focusSource'], 'pending')
            release.set()
            self.assertTrue(second.wait(1))
            deadline = time.monotonic() + 1
            while time.monotonic() < deadline:
                snapshot = json.loads(path.read_text())
                if snapshot.get('focusSource') == 'switcher-inactive': break
                time.sleep(0.01)
            self.assertEqual((snapshot['purpose'], snapshot['hint'], snapshot['autoShow']), (8, 64, True))

    def test_focus_source_parser_distinguishes_unknown_and_normal(self):
        import runpy
        from unittest.mock import patch
        module = runpy.run_path(str(IME))
        class Result:
            def __init__(self, value): self.stdout = value
        for output, expected in [('(<@as []>,)', 'switcher-inactive'), ("(<['fade']>,)", 'switcher-inactive'),
                                 ("(<['mobiletaskswitcher']>,)", 'mobiletaskswitcher'),
                                 ('garbage', 'unknown')]:
            with patch.object(module['subprocess'], 'run', return_value=Result(output)):
                self.assertEqual(module['focus_source'](), expected)

    def test_slow_focus_query_cannot_publish_after_blur(self):
        script = self.session / 'slow-gdbus'
        script.write_text('#!/bin/sh\nsleep 0.3\necho "(<@as []>,)"\n')
        script.chmod(0o755)
        self.proc.terminate()
        self.proc.wait(5)
        self.proc.stderr.close()
        self.server.close()
        self.server, client = socket.socketpair(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(self.server.close)
        env = dict(os.environ, BL_LAUNCH_DIR=str(self.session), WAYLAND_SOCKET=str(client.fileno()), DROIDDECK_IME_GDBUS=str(script), DROIDDECK_MOBILE_IME='1')
        self.proc = subprocess.Popen(['python3', str(IME)], env=env, pass_fds=[client.fileno()], stderr=subprocess.PIPE, text=True)
        client.close()
        self.server.settimeout(5)
        self.read(12)
        self.server.sendall(message(2, 0, struct.pack('<I', 7) + wl_string('zwp_input_method_v1') + struct.pack('<I', 1)))
        self.read(8 + 4 + len(wl_string('zwp_input_method_v1')) + 8)
        self.server.sendall(message(3, 0, struct.pack('<I', 100)))
        time.sleep(0.05)
        self.server.sendall(message(3, 1, struct.pack('<I', 100)))
        self.assertEqual(self.read(8), message(100, 0))
        self.assertFalse(self.state(2)['textField'])
        time.sleep(0.5)
        self.assertFalse(self.state(2)['textField'])

    def test_non_mobile_focus_retains_legacy_immediate_show(self):
        import runpy
        from unittest.mock import patch
        module = runpy.run_path(str(IME))
        with patch.dict(os.environ, {'DROIDDECK_MOBILE_IME': '0'}):
            report = module['Report'](str(self.session))
            report.write(True, 0, 0)
            snapshot = json.loads((self.session / 'ime.json').read_text())
            self.assertEqual(snapshot['focusSource'], 'legacy')
            self.assertTrue(snapshot['autoShow'])

    def test_changed_effect_during_query_is_unknown(self):
        import runpy
        from unittest.mock import patch
        module = runpy.run_path(str(IME))
        with patch.dict(os.environ, {'DROIDDECK_MOBILE_IME': '1'}):
            with patch.dict(module['focus_source'].__globals__, {'focus_source': iter(['switcher-inactive', 'mobiletaskswitcher']).__next__}):
                report = module['Report'](str(self.session))
                report.write(True, 0, 0)
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline:
                    snapshot = json.loads((self.session / 'ime.json').read_text())
                    if snapshot['focusSource'] != 'pending': break
                    time.sleep(0.02)
                self.assertEqual(snapshot['focusSource'], 'unknown')
                self.assertFalse(snapshot['autoShow'])

    def test_superseded_focus_query_cannot_reopen_a_newer_switcher_field(self):
        import runpy
        import threading
        from unittest.mock import patch
        module = runpy.run_path(str(IME))
        started = threading.Event()
        release = threading.Event()
        calls = []
        def query():
            calls.append(True)
            if len(calls) == 1:
                started.set()
                release.wait(2)
                return 'switcher-inactive'
            return 'mobiletaskswitcher'
        with patch.dict(os.environ, {'DROIDDECK_MOBILE_IME': '1'}), patch.dict(module['focus_source'].__globals__, {'focus_source': query}):
            report = module['Report'](str(self.session))
            report.write(True, 0, 0)
            self.assertTrue(started.wait(1))
            report.write(False)
            report.write(True, 256, 0)
            release.set()
            deadline = time.monotonic() + 2
            path = self.session / 'ime.json'
            while time.monotonic() < deadline:
                snapshot = json.loads(path.read_text())
                if snapshot['focusSource'] != 'pending': break
                time.sleep(0.01)
            self.assertEqual(snapshot['seq'], 3)
            self.assertEqual(snapshot['focusSource'], 'mobiletaskswitcher')
            self.assertFalse(snapshot['autoShow'])

    def test_unknown_focus_query_is_fail_closed_without_losing_password_hints(self):
        import runpy
        from unittest.mock import patch
        module = runpy.run_path(str(IME))
        with patch.dict(os.environ, {'DROIDDECK_MOBILE_IME': '1'}), patch.dict(module['focus_source'].__globals__, {'focus_source': lambda: 'unknown'}):
            report = module['Report'](str(self.session))
            report.write(True, 64, 8)
            deadline = time.monotonic() + 2
            path = self.session / 'ime.json'
            while time.monotonic() < deadline:
                snapshot = json.loads(path.read_text())
                if snapshot['focusSource'] != 'pending': break
                time.sleep(0.01)
            self.assertEqual(snapshot['focusSource'], 'unknown')
            self.assertFalse(snapshot['autoShow'])
            self.assertEqual((snapshot['textField'], snapshot['hint'], snapshot['purpose']), (True, 64, 8))

    def test_focus_and_blur_are_reported(self):
        self.server.sendall(message(3, 0, struct.pack('<I', 100)))
        self.assertTrue(self.state(1)['textField'])
        self.server.sendall(message(100, 2, struct.pack('<II', 0, 9)))
        snapshot = self.state(2)
        self.assertEqual((snapshot['textField'], snapshot['purpose']), (True, 9))
        self.server.sendall(message(3, 1, struct.pack('<I', 100)))
        self.assertEqual(self.read(8), message(100, 0))
        self.assertFalse(self.state(3)['textField'])
        self.assertEqual(oct((self.session / 'agent/ime.json').stat().st_mode & 0o777), '0o600')

    def test_mobile_repeated_focused_field_commit_is_reported(self):
        self.server.sendall(message(3, 0, struct.pack('<I', 100)))
        self.assertTrue(self.state(1)['textField'])
        self.server.sendall(message(100, 4, struct.pack('<I', 1)))
        self.assertTrue(self.state(2, timeout=0.5)['textField'])
        self.server.sendall(message(100, 4, struct.pack('<I', 2)))
        self.assertTrue(self.state(3, timeout=0.5)['textField'])

    def test_hint_only_changes_are_reported(self):
        self.server.sendall(message(3, 0, struct.pack('<I', 100)))
        self.assertTrue(self.state(1)['textField'])
        self.server.sendall(message(100, 2, struct.pack('<II', 64, 0)))
        snapshot = self.state(2, timeout=0.5)
        self.assertEqual((snapshot['textField'], snapshot['hint'], snapshot['purpose']), (True, 64, 0))
        self.server.sendall(message(100, 2, struct.pack('<II', 0, 0)))
        self.assertEqual(self.state(3)['hint'], 0)

    def test_switching_fields_does_not_flicker(self):
        self.server.sendall(message(3, 0, struct.pack('<I', 100)))
        self.assertTrue(self.state(1)['textField'])
        self.server.sendall(message(3, 1, struct.pack('<I', 100)) + message(3, 0, struct.pack('<I', 101)))
        self.assertEqual(self.read(8), message(100, 0))
        time.sleep(0.4)
        snapshot = self.state(1)
        self.assertEqual((snapshot['textField'], snapshot['hint'], snapshot['purpose'], snapshot['seq']), (True, 0, 0, 1))

    def test_compositor_exit_clears_the_field(self):
        self.server.sendall(message(3, 0, struct.pack('<I', 100)))
        self.assertTrue(self.state(1)['textField'])
        self.server.close()
        self.assertEqual(self.proc.wait(5), 0)
        self.assertFalse(self.state(2)['textField'])


    def test_a_protocol_error_clears_the_field(self):
        self.server.sendall(message(3, 0, struct.pack('<I', 100)))
        self.assertTrue(self.state(1)['textField'])
        self.server.sendall(message(1, 0, struct.pack('<II', 3, 1) + wl_string('bad request')))
        self.assertEqual(self.proc.wait(5), 1)
        self.assertFalse(self.state(2)['textField'])

if __name__ == '__main__':
    unittest.main()
