#!/usr/bin/env python3
import os
import select
import socket
import struct
import sys
import time


def string(value):
    data = value.encode() + b'\0'
    return struct.pack('<I', len(data)) + data + b'\0' * (-len(data) % 4)


def parse_string(data):
    length, = struct.unpack_from('<I', data)
    return data[4:4 + length - 1].decode(), 4 + (length + 3 & ~3)


def send(sock, obj, op, data=b''):
    sock.sendall(struct.pack('<II', obj, ((8 + len(data)) << 16) | op) + data)


def main():
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.connect(os.path.join(os.environ['XDG_RUNTIME_DIR'], 'outer-probe'))
    send(sock, 1, 1, struct.pack('<I', 2))
    send(sock, 1, 0, struct.pack('<I', 3))
    buffer = b''
    names = {}
    events = []
    ready = False
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if not select.select([sock], [], [], 1)[0]:
            continue
        data = sock.recv(65536)
        if not data:
            break
        buffer += data
        while len(buffer) >= 8:
            obj, word = struct.unpack_from('<II', buffer)
            size, opcode = word >> 16, word & 0xffff
            if len(buffer) < size:
                break
            payload, buffer = buffer[8:size], buffer[size:]
            if obj == 1 and opcode == 0:
                raise RuntimeError(f'Wayland protocol error: {payload!r}')
            if obj == 2 and opcode == 0:
                name, = struct.unpack_from('<I', payload)
                iface, offset = parse_string(payload[4:])
                version, = struct.unpack_from('<I', payload, 4 + offset)
                names[iface] = (name, version)
            if obj == 3 and opcode == 0 and not ready:
                for iface, ident in [('wl_compositor', 4), ('wl_seat', 5), ('zwp_text_input_manager_v3', 6)]:
                    name, version = names[iface]
                    send(sock, 2, 0, struct.pack('<I', name) + string(iface) + struct.pack('<II', 1, ident))
                send(sock, 4, 0, struct.pack('<I', 7))
                send(sock, 6, 1, struct.pack('<II', 8, 5))
                ready = True
            elif obj == 8 and opcode == 0:
                events.append(('enter',))
                send(sock, 8, 1)
                send(sock, 8, 5, struct.pack('<II', 0, 0))
                send(sock, 8, 7)
            elif obj == 8 and opcode == 1:
                events.append(('leave',))
            elif obj == 8 and opcode == 2:
                text, offset = parse_string(payload)
                begin, end = struct.unpack_from('<ii', payload, offset)
                events.append(('preedit', text, begin, end))
            elif obj == 8 and opcode == 3:
                text, _ = parse_string(payload)
                events.append(('commit', text))
            elif obj == 8 and opcode == 5:
                serial, = struct.unpack('<I', payload)
                events.append(('done', serial))
    expected = [('enter',), ('preedit', '中文', 3, 6), ('done', 1),
                ('preedit', '', 0, 0), ('commit', '中文'), ('done', 1), ('leave',)]
    print('observed:', events)
    if events != expected:
        raise AssertionError(f'expected {expected!r}')
    print('outer text-input-v3 focus/preedit/commit/blur PASS')


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print(exc, file=sys.stderr)
        sys.exit(1)
