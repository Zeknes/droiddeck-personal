import json
import os
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools/linuxfs/overlay/usr/local/bin/droiddeck-performance'


class PerformanceTest(unittest.TestCase):
    def test_cpu_report_is_reproducible_bounded_and_explicit_about_gpu(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'result.json'
            run = subprocess.run(['python3', str(SCRIPT), '--cpu', '--seconds', '1', '--threads', '2',
                                  '--output', str(output)], capture_output=True, text=True, timeout=15)
            self.assertEqual(0, run.returncode, run.stderr)
            data = json.loads(output.read_text())
            self.assertEqual('sha256-1MiB', data['cpu']['workload'])
            self.assertGreater(data['cpu']['single']['bytes_per_second'], 0)
            self.assertEqual(2, data['cpu']['multi']['workers'])
            self.assertNotIn('gpu', data)
            self.assertTrue(output.with_suffix('.html').is_file())

    def test_rejects_unbounded_load(self):
        run = subprocess.run(['python3', str(SCRIPT), '--cpu', '--seconds', '120'], capture_output=True)
        self.assertNotEqual(0, run.returncode)

    def test_frame_summary_excludes_zero_pending_and_huge_timestamps(self):
        module = runpy.run_path(str(ROOT / 'tools/display_probe.py'))
        result = module['latency_summary']('16666666\n0 1000000000 0\n0 1016666666 0\n0 1033333332 0\n0 9223372036854775807 0\n0 0 0\n')
        self.assertEqual(3, result['frames'])
        self.assertAlmostEqual(60, result['fps'], places=3)
        self.assertEqual(0, result['intervals_over_1_5x'])

    def test_thermal_guard_is_conservative_and_does_not_override_limits(self):
        module = runpy.run_path(str(SCRIPT))
        self.assertTrue(module['too_hot']({'cpu0-thermal': 105}))
        self.assertTrue(module['too_hot']({'battery': 45}))
        self.assertFalse(module['too_hot']({'cpu': 55, 'battery': 37}))

    def test_installer_preserves_existing_user_data_and_has_desktop_entry(self):
        with tempfile.TemporaryDirectory() as tmp:
            env = dict(os.environ, HOME=tmp)
            user = Path(tmp)/'.local/share/applications'; user.mkdir(parents=True)
            (user/'other.desktop').write_text('user content')
            for _ in range(2):
                subprocess.run(['python3',str(SCRIPT),'--install'],env=env,check=True,capture_output=True)
            self.assertEqual('user content',(user/'other.desktop').read_text())
            self.assertIn('droiddeck-performance --quick', (user/'droiddeck-performance.desktop').read_text())
