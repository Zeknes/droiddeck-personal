import gi
import os
import socket

gi.require_version('Gtk', '4.0')
from gi.repository import GLib, Gtk

window = Gtk.Window(title='Inner entry')
box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
normal = Gtk.Entry()
secret = Gtk.Entry()
secret.set_input_purpose(Gtk.InputPurpose.PASSWORD)
secret.set_visibility(False)
box.append(normal)
box.append(secret)
window.set_child(box)


def report(name, entry):
    print(f'APP_TEXT {name} {entry.get_text()!r}', flush=True)


normal.connect('changed', lambda widget: report('normal', widget))
secret.connect('changed', lambda widget: report('secret', widget))
current = normal


def focused_entry():
    widget = window.get_focus()
    while widget is not None and not isinstance(widget, Gtk.Entry):
        widget = widget.get_parent()
    return widget


def focus_current():
    if hasattr(window, 'set_focus'):
        window.set_focus(current)
    current.grab_focus()


for name, entry in (('normal', normal), ('secret', secret)):
    entry.connect('notify::has-focus', lambda widget, pspec, name=name: print(
        f'APP_ENTRY_FOCUS {name} {widget.has_focus()}', flush=True))


def on_active(widget, pspec):
    print(f'APP_WINDOW_ACTIVE {widget.is_active()}', flush=True)
    if widget.is_active():
        focus_current()


window.connect('notify::is-active', on_active)

server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
path = os.environ['DROIDDECK_APP_SOCKET']
try:
    os.unlink(path)
except FileNotFoundError:
    pass
server.bind(path)
server.listen(1)
server.setblocking(False)


def control(fd, condition):
    global current
    connection, _ = server.accept()
    data = connection.recv(64).decode().strip()
    connection.close()
    if data == 'normal':
        current = normal
        normal.grab_focus()
        print(f'APP_FOCUS normal {focused_entry() is normal}', flush=True)
    elif data == 'secret':
        current = secret
        secret.grab_focus()
        print(f'APP_FOCUS secret {focused_entry() is secret}', flush=True)
    elif data == 'reset':
        normal.set_text('')
        current = normal
        normal.grab_focus()
        print('APP_RESET', flush=True)
    elif data == 'dump':
        print(f'APP_DUMP normal={normal.get_text()!r} secret={secret.get_text()!r}', flush=True)
    elif data == 'quit':
        loop.quit()
    return True


GLib.io_add_watch(server.fileno(), GLib.IO_IN, control)
loop = GLib.MainLoop()
window.present()
focus_current()
print('APP_READY', flush=True)
GLib.idle_add(lambda: (focus_current(), False)[1])
GLib.timeout_add(500, lambda: (print(
    f'APP_STATE active={window.is_active()} focus={window.get_focus()}', flush=True), True)[1])
GLib.timeout_add_seconds(60, lambda: loop.quit() or False)
loop.run()
