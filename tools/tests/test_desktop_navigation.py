from pathlib import Path
import importlib.machinery
import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from unittest.mock import patch

BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'
WRAPPER = BIN / 'droiddeck-mobile-shell'
SESSION = {
    'WAYLAND_DISPLAY': 'wayland-1', 'XDG_RUNTIME_DIR': '/run/droiddeck',
    'QT_QUICK_BACKEND': 'software', 'LIBGL_ALWAYS_SOFTWARE': '1', 'GALLIUM_DRIVER': 'llvmpipe',
    'MESA_LOADER_DRIVER_OVERRIDE': 'zink', 'QT_ENABLE_GLYPH_CACHE_WORKAROUND': '1', 'KEEP': 'yes',
}
# Runs the real wrapper the way the desktop does, in a fresh interpreter with the recorded plasmashell call.
HARNESS = '''
import ast, json, runpy, sys
calls = []
def call(command, env=None):
    calls.append((command, env))
    return 0
path, argv = sys.argv[1], ast.literal_eval(sys.argv[2])
wrapper = runpy.run_path(path)
sys.argv = ['droiddeck-mobile-shell'] + argv
sys.modules['subprocess'].call = call
rc = wrapper['main']()
sys.stdout.write(json.dumps({'rc': rc, 'calls': calls}))
'''


class DesktopNavigationTest(unittest.TestCase):
    def test_guest_actions_are_allowlisted_and_do_not_execute_arbitrary_commands(self):
        loader = importlib.machinery.SourceFileLoader('nav_agent', str(BIN / 'droiddeck-agent'))
        spec = importlib.util.spec_from_loader(loader.name, loader)
        module = importlib.util.module_from_spec(spec)
        loader.exec_module(module)
        with patch.object(module.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '', '')) as run:
            self.assertEqual({'action': 'home'}, module.handle(Path('/tmp'), {'kind': 'desktop-navigation', 'action': 'home'}))
            self.assertEqual(['/usr/local/bin/droiddeck-desktop-navigate', 'home'], run.call_args.args[0])
            with self.assertRaises(module.AgentError):
                module.handle(Path('/tmp'), {'kind': 'desktop-navigation', 'action': 'kill'})

    def shell(self, gpu, argv=('-p', 'org.kde.plasma.mobileshell'), conf=''):
        with tempfile.TemporaryDirectory() as home:
            if conf:
                path = Path(home) / '.config/QtProject.conf'
                path.parent.mkdir(parents=True)
                path.write_text(conf)
            env = {'HOME': home, 'DROIDDECK_GPU_NAME': gpu, **SESSION}
            run = subprocess.run([os.sys.executable, '-c', HARNESS, str(WRAPPER), repr(list(argv))],
                                 env=env, capture_output=True, text=True, timeout=60)
            self.assertEqual(run.returncode, 0, run.stderr)
            result = json.loads(run.stdout)
            qt = Path(home) / '.config/QtProject.conf'
            self.assertEqual(len(result['calls']), 1)
            return result['calls'][0][0], result['calls'][0][1] or {}, qt.read_text() if qt.exists() else None

    def test_the_shell_draws_with_the_software_opengl_rhi_instead_of_a_software_scene_graph(self):
        command, env, _ = self.shell('Adreno (TM) 840')
        self.assertEqual(command[:3], ['/usr/bin/plasmashell', '-p', 'org.kde.plasma.mobileshell'])
        self.assertEqual(env.get('QT_QUICK_BACKEND'), 'rhi')
        self.assertEqual((env.get('QSG_RHI_BACKEND'), env.get('QT_OPENGL')), ('opengl', 'software'))
        self.assertEqual(env.get('KEEP'), 'yes')

    def test_the_software_scene_graph_setting_is_taken_out_for_the_shell(self):
        setting = '[QtQuickRendererSettings]\nSceneGraphBackend=software\n'
        _, _, conf = self.shell('Adreno (TM) 840', conf=setting + '[Other]\nKey=1\n')
        self.assertNotIn('SceneGraphBackend=software', conf)
        self.assertIn('[Other]\nKey=1', conf)
        _, _, conf = self.shell('Adreno (TM) 740', conf=setting)
        self.assertIn('SceneGraphBackend=software', conf)

    def test_only_the_mobile_shell_gets_its_renderer(self):
        _, env, _ = self.shell('Adreno (TM) 840')
        self.assertEqual(env.get('QT_QUICK_BACKEND'), 'rhi')
        _, env, _ = self.shell('Adreno (TM) 840', argv=('--feedback', '-p', 'org.kde.plasma.mobileshell'))
        self.assertEqual(env.get('QT_QUICK_BACKEND'), 'rhi')
        # A second shell would share the session's DBus and Wayland display and take KWin down again.
        for argv in (('-p', 'org.kde.plasma.desktop'), ('--replace', '-p', 'org.kde.plasma.mobileshell'), ('-d',)):
            _, env, _ = self.shell('Adreno (TM) 840', argv=argv)
            self.assertEqual(env.get('QT_QUICK_BACKEND'), 'software')
            self.assertNotIn('QT_OPENGL', env)

    def test_the_rewritten_qt_settings_stay_readable_for_kwin(self):
        # KWin starts as the session user, not root: a file rewritten with root's umask would hide the Qt
        # settings from it, and a scene graph setting written back would land in the shell's next start.
        setting = '[QtQuickRendererSettings]\nSceneGraphBackend=software\n'
        with tempfile.TemporaryDirectory() as home:
            path = Path(home) / '.config/QtProject.conf'
            path.parent.mkdir(parents=True)
            path.write_text(setting)
            path.chmod(0o600)
            env = {'HOME': home, 'DROIDDECK_GPU_NAME': 'Adreno (TM) 840', **SESSION}
            run = subprocess.run([os.sys.executable, '-c', HARNESS, str(WRAPPER), repr(['-p', 'org.kde.plasma.mobileshell'])],
                                 env=env, capture_output=True, text=True, timeout=60)
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertEqual(0o644, path.stat().st_mode & 0o777)
            self.assertNotIn('SceneGraphBackend=software', path.read_text())

    def test_line_command_arguments_are_kept(self):
        command, _, _ = self.shell('Adreno (TM) 840', argv=('-n', '-p', 'org.kde.plasma.mobileshell'))
        self.assertEqual(command, ['/usr/bin/plasmashell', '-n', '-p', 'org.kde.plasma.mobileshell'])
