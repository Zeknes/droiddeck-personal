import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
HEADER = ROOT / 'app/src/main/cpp/waylandcomp/src'


class MobileProbeLogTest(unittest.TestCase):
    def test_production_writer_lifecycle_and_bound(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source = root / 'probe.c'
            source.write_text('''#include "mobile_probe.h"
int main(int argc, char **argv) {
 const char *events[] = {"create", "toplevel", "frame-request", "feedback-request", "commit", "presented", "frame-done", "discarded-replaced", "discarded-undrawn", "discarded-no-output", "discarded-destroy", "destroy"};
 for (unsigned i=0; i<sizeof(events)/sizeof(events[0]); ++i) mobile_probe_write(argv[1], 7, events[i], 0);
 for (int i=0; i<5000; ++i) mobile_probe_write(argv[1], 7, "commit", 0);
 mobile_probe_write(argv[1], 0, "exit", 1);
 return 0;
}''')
            exe = root / 'probe'
            subprocess.run(['cc', '-std=c11', '-D_POSIX_C_SOURCE=200809L', '-I', str(HEADER), str(source), '-o', str(exe)], check=True)
            log = root / 'wayland.log'
            subprocess.run([str(exe), str(log)], check=True)
            lines = pathlib.Path(str(log) + '.mobile-frames.log').read_text().splitlines()
            self.assertEqual(len(lines), 4096)
            events = [line.split(' ')[2] for line in lines]
            self.assertEqual(events[:12], ['create', 'toplevel', 'frame-request', 'feedback-request', 'commit', 'presented', 'frame-done', 'discarded-replaced', 'discarded-undrawn', 'discarded-no-output', 'discarded-destroy', 'destroy'])
            self.assertEqual(events[-1], 'exit')
            self.assertTrue(all(len(line.split(' ')) == 3 for line in lines))


if __name__ == '__main__':
    unittest.main()
