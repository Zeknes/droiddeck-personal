import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).resolve().parents[1] / 'gnome-desktop/input-bridge/droiddeck_ime_state.py'
spec = importlib.util.spec_from_file_location('droiddeck_ime_state', path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class FocusGateTest(unittest.TestCase):
    def setUp(self):
        self.cleared = 0
        self.gate = module.FocusGate(self.clear)

    def clear(self):
        self.cleared += 1

    def ready(self, epoch=1, purpose=0, hints=0):
        self.gate.attach()
        self.gate.inner_focus(True)
        self.assertTrue(self.gate.shell_state((epoch, purpose, hints, True)))
        generation = self.gate.generation
        self.assertTrue(self.gate.focus(generation))
        self.assertTrue(self.gate.accepts(generation))
        return generation

    def test_requires_both_focuses_and_shell_confirmation(self):
        self.assertFalse(self.gate.focus(self.gate.generation))
        self.gate.attach()
        self.assertFalse(self.gate.focus(self.gate.generation))
        self.gate.inner_focus(True)
        self.assertFalse(self.gate.focus(self.gate.generation))
        self.assertTrue(self.gate.shell_state((1, 0, 0, True)))
        self.assertTrue(self.gate.focus(self.gate.generation))
        self.assertFalse(self.gate.accepts(self.gate.generation - 1))

    def test_shell_failure_keeps_the_gate_closed(self):
        generation = self.ready()
        self.assertFalse(self.gate.shell_state(None))
        self.assertFalse(self.gate.accepts(generation))
        self.assertEqual(self.gate.generation, generation + 1)

    def test_password_purpose_and_hints_are_rejected(self):
        for purpose, hints in ((8, 0), (0, 64), (0, 128), (0, 0x1800)):
            gate = module.FocusGate(self.clear)
            gate.attach()
            gate.inner_focus(True)
            self.assertFalse(gate.shell_state((3, purpose, hints, True)))
            self.assertTrue(gate.shell_ok)
            self.assertFalse(gate.ready())
            self.assertFalse(gate.focus(gate.generation))

    def test_ibus_content_type_can_only_close_the_gate(self):
        generation = self.ready()
        self.gate.note_ibus_content_type(8, 0x1800)
        self.assertFalse(self.gate.accepts(generation))
        self.assertEqual(self.gate.generation, generation + 1)
        self.gate.note_ibus_content_type(0, 0)
        self.assertFalse(self.gate.accepts(self.gate.generation))

    def test_blur_clears_preedit_and_rejects_the_old_generation(self):
        generation = self.ready()
        self.gate.preedit = True
        self.assertTrue(self.gate.blur(generation))
        self.assertEqual(self.cleared, 1)
        self.assertFalse(self.gate.accepts(generation))
        self.assertFalse(self.gate.blur(generation))

    def test_focus_transfer_requires_fresh_shell_confirmation(self):
        generation = self.ready()
        self.gate.preedit = True
        self.gate.inner_focus(False)
        self.gate.inner_focus(True)
        self.assertEqual(self.cleared, 1)
        self.assertFalse(self.gate.accepts(generation))
        self.assertFalse(self.gate.focus(self.gate.generation))
        self.assertTrue(self.gate.shell_state((2, 0, 0, True)))
        self.assertTrue(self.gate.focus(self.gate.generation))

    def test_shell_epoch_change_closes_the_gate(self):
        generation = self.ready()
        self.assertFalse(self.gate.shell_state((2, 0, 0, True)))
        self.assertFalse(self.gate.accepts(generation))
        self.assertFalse(self.gate.inner)
        self.assertFalse(self.gate.shell_state((2, 0, 0, True)))
        self.assertFalse(self.gate.accepts(self.gate.generation))

    def test_disconnect_invalidates_everything(self):
        generation = self.ready()
        self.gate.preedit = True
        self.assertTrue(self.gate.detach())
        self.assertEqual(self.cleared, 1)
        self.assertFalse(self.gate.accepts(generation))
        self.assertFalse(self.gate.focus(self.gate.generation))
        self.assertTrue(self.gate.attach())

    def test_sensitive_transition_clears_preedit(self):
        self.ready()
        self.gate.preedit = True
        self.assertFalse(self.gate.shell_state((1, 8, 0, True)))
        self.assertEqual(self.cleared, 1)
        self.assertTrue(self.gate.sensitive)

    def test_unfocused_shell_focus_closes_the_gate(self):
        generation = self.ready()
        self.assertFalse(self.gate.shell_state((1, 0, 0, False)))
        self.assertFalse(self.gate.accepts(generation))


if __name__ == '__main__':
    unittest.main()
