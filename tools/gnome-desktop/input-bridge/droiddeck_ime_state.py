"""Focus/sensitivity gate for the DroidDeck GNOME input bridge.

Two independent focus owners must agree before host text reaches the inner
session: the outer compositor (Android keyboard attached to a text field) and
the inner GNOME focus (IBus engine focus plus the Shell's post-commit content
type).  Every change invalidates the previous generation so messages queued
for an older focus cannot be replayed into a newer one.
"""

PASSWORD = 8
SENSITIVE_HINTS = 0x18C0  # Clutter sensitive-data/hidden-text; IBus private/hidden-text


def is_sensitive(purpose, hints):
    return purpose == PASSWORD or bool(int(hints) & SENSITIVE_HINTS)


class FocusGate:
    def __init__(self, clear_preedit):
        self._clear_preedit = clear_preedit
        self.generation = 0
        self.connected = False
        self.inner = False
        self.outer = False
        self.sensitive = True
        self.shell_ok = False
        self.shell_epoch = None
        self.preedit = False

    def _invalidate(self, keep_epoch=False):
        if self.preedit:
            self._clear_preedit()
            self.preedit = False
        self.outer = False
        if not keep_epoch:
            self.shell_ok = False
            self.shell_epoch = None
        self.generation += 1

    def attach(self):
        if self.connected:
            return False
        self.connected = True
        self._invalidate()
        return True

    def detach(self):
        if not self.connected:
            return False
        self.connected = False
        self.inner = False
        self.sensitive = True
        self._invalidate()
        return True

    def inner_focus(self, focused):
        focused = bool(focused)
        if self.inner == focused:
            return False
        self.inner = focused
        self.sensitive = True
        self._invalidate()
        return True

    def shell_state(self, state):
        """Apply (epoch, purpose, hints, focused) read after Mutter committed it.

        None means the Shell could not confirm the current focus; the gate then
        stays closed instead of assuming a normal field.
        """
        if state is None:
            changed = self.shell_ok or not self.sensitive
            self.shell_ok = False
            self.sensitive = True
            if changed:
                self._invalidate(keep_epoch=True)
            return False
        epoch, purpose, hints, focused = state
        if not focused or not self.inner:
            changed = self.shell_ok or not self.sensitive
            self.shell_ok = False
            self.sensitive = True
            if changed:
                self._invalidate(keep_epoch=True)
            return False
        self.shell_ok = True
        if self.shell_epoch is None:
            self.shell_epoch = epoch
        elif epoch != self.shell_epoch:
            self.inner = False
            self.sensitive = True
            self.shell_epoch = epoch
            self._invalidate(keep_epoch=True)
            return False
        sensitive = is_sensitive(purpose, hints)
        if sensitive != self.sensitive:
            self.sensitive = sensitive
            self._invalidate(keep_epoch=True)
        return self.inner and not self.sensitive

    def note_ibus_content_type(self, purpose, hints):
        if is_sensitive(purpose, hints) and not self.sensitive:
            self.sensitive = True
            self._invalidate(keep_epoch=True)
            return True
        return False

    def focus(self, generation):
        if (generation != self.generation or not self.connected or not self.inner
                or self.sensitive or not self.shell_ok):
            return False
        if self.outer:
            return True
        self.outer = True
        return True

    def blur(self, generation):
        if generation != self.generation:
            return False
        self._invalidate()
        return True

    def accepts(self, generation):
        return (generation == self.generation and self.connected and self.inner
                and self.outer and self.shell_ok and not self.sensitive)

    def reset(self):
        self.inner = False
        self.sensitive = True
        self._invalidate()

    def ready(self):
        return (self.connected and self.inner and self.outer and self.shell_ok
                and not self.sensitive)
