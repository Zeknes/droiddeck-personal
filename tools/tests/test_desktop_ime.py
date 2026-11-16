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
        env = dict(os.environ, BL_LAUNCH_DIR=str(self.session), WAYLAND_SOCKET=str(client.fileno()))
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
                   DROIDDECK_IME_GDBUS=str(script))
        self.proc = subprocess.Popen(['python3', str(IME)], env=env, pass_fds=[client.fileno()], stderr=subprocess.PIPE, text=True)
        client.close()
        self.server.settimeout(5)
        self.read(12)
        self.server.sendall(message(2, 0, struct.pack('<I', 7) + wl_string('zwp_input_method_v1') + struct.pack('<I', 1)))
        self.read(8 + 4 + len(wl_string('zwp_input_method_v1')) + 8)
        self.server.sendall(message(3, 0, struct.pack('<I', 100)))
        self.assertTrue(self.state(1)['textField'])
        import select
        output = b''
        deadline = time.monotonic() + 3
        while output.count(b'\n') < 2 and time.monotonic() < deadline:
            if select.select([self.proc.stderr], [], [], 0.2)[0]:
                output += os.read(self.proc.stderr.fileno(), 4096)
        lines = output.decode().splitlines()
        self.assertEqual(len(lines), 2, lines)
        self.assertIn('text field focused', lines[0])
        line = lines[1]
        self.assertIn('mobile_task_switcher=true', line)
        self.assertIn('mobile_effect=true', line)
        self.assertNotIn('password', line)

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
        self.assertEqual(self.state(1), {'textField': True, 'hint': 0, 'purpose': 0, 'seq': 1})

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
