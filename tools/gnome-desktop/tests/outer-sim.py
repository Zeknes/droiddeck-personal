#!/usr/bin/python3
"""Simulates the outer compositor: sends UTF-8 preedit/commit to the bridge and
checks what the real GTK entry received."""
import json
import os
import select
import socket
import sys
import time

SOCKET = os.environ['DROIDDECK_IME_SOCKET']
APP_LOG = os.environ['DROIDDECK_APP_LOG']
APP_SOCKET = os.environ['DROIDDECK_APP_SOCKET']


class Bridge:
    def __init__(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.connect(SOCKET)
        self.buffer = b''
        self.last = None

    def send(self, **message):
        self.sock.sendall((json.dumps(message, ensure_ascii=False) + '\n').encode())

    def state(self, predicate, timeout=10):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            ready, _, _ = select.select([self.sock], [], [], 0.2)
            if ready:
                data = self.sock.recv(8192)
                if not data:
                    raise RuntimeError('bridge closed')
                self.buffer += data
                while b'\n' in self.buffer:
                    line, self.buffer = self.buffer.split(b'\n', 1)
                    state = json.loads(line.decode())
                    assert state['type'] == 'state', state
                    self.last = state
                    print('STATE', state, flush=True)
                    if predicate(state):
                        return state
        raise RuntimeError(f'timeout waiting for state; last={self.last}')

    def close(self):
        self.sock.close()


def app_text():
    try:
        with open(APP_LOG) as handle:
            return handle.read()
    except FileNotFoundError:
        return ''


def wait_app(fragment, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if fragment in app_text():
            return
        time.sleep(0.05)
    raise RuntimeError(f'app log never contained {fragment!r}:\n{app_text()}')


def app_command(command):
    control = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    control.connect(APP_SOCKET)
    control.sendall(command.encode())
    control.close()


def main():
    bridge = Bridge()
    state = bridge.state(lambda s: s['inner'] and s['shell'] and not s['sensitive'])
    generation = state['generation']
    bridge.send(type='focus', generation=generation)
    bridge.state(lambda s: s['outer'] and s['ready'])

    bridge.send(type='preedit', generation=generation, text='中文', cursor=2)
    time.sleep(0.3)
    bridge.send(type='commit', generation=generation, text='中文')
    wait_app("APP_TEXT normal '中文'")

    bridge.send(type='commit', generation=generation - 1, text='旧代次')
    time.sleep(0.5)
    assert '旧代次' not in app_text(), app_text()

    app_command('secret')
    sensitive = bridge.state(lambda s: s['sensitive'] and not s['ready'])
    stale_generation = sensitive['generation']
    bridge.send(type='focus', generation=stale_generation)
    bridge.send(type='commit', generation=generation, text='旧字段')
    bridge.send(type='commit', generation=stale_generation, text='密码泄露')
    time.sleep(0.7)
    assert '旧字段' not in app_text() and '密码泄露' not in app_text(), app_text()
    app_command('dump')
    wait_app('APP_DUMP normal=\'中文\' secret=\'\'')

    app_command('normal')
    app_command('reset')
    state = bridge.state(lambda s: s['inner'] and s['shell'] and not s['sensitive'])
    generation = state['generation']
    bridge.send(type='focus', generation=generation)
    bridge.state(lambda s: s['outer'] and s['ready'])
    bridge.send(type='preedit', generation=generation, text='中文😀', cursor=3)
    time.sleep(0.3)
    bridge.send(type='commit', generation=generation, text='中文😀')
    wait_app("APP_TEXT normal '中文😀'")

    bridge.send(type='blur', generation=generation)
    bridge.state(lambda s: not s['outer'])
    bridge.send(type='commit', generation=generation, text='失焦后')
    time.sleep(0.5)
    assert '失焦后' not in app_text(), app_text()

    bridge.close()
    time.sleep(0.3)
    bridge = Bridge()
    state = bridge.state(lambda s: s['inner'] and s['shell'] and not s['sensitive'])
    assert not state['outer'] and state['generation'] > generation, state
    generation = state['generation']
    app_command('reset')
    bridge.send(type='focus', generation=generation)
    bridge.state(lambda s: s['outer'] and s['ready'])
    bridge.send(type='commit', generation=generation, text='重连')
    wait_app("APP_TEXT normal '重连'")

    print('OUTER_TO_SHELL_TO_GTK PASS', flush=True)


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print(f'FAIL: {exc}', file=sys.stderr)
        sys.exit(1)
