import os
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/desktop/droiddeck-desktop-gpu"
PREFIX = "env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 ELECTRON_DISABLE_SANDBOX=1 "
FLAGS = " --no-sandbox --ozone-platform=x11"


def load():
    source = SCRIPT.read_text()
    assert source.rstrip().endswith("main()")
    scope = {"__name__": "droiddeck_desktop_gpu"}
    exec(compile(source.rstrip()[:-len("main()")], str(SCRIPT), "exec"), scope)
    return scope


class DesktopChromiumAppsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.gpu = load()
        self.electron = self.app("lib/chat/chat", sandbox=True)
        self.plain = self.app("lib/editor/editor", sandbox=False)
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        (bin_dir / "chat").symlink_to(self.electron)
        old_path = os.environ.get("PATH", "")
        os.environ["PATH"] = "%s:%s" % (bin_dir, old_path)
        self.addCleanup(os.environ.__setitem__, "PATH", old_path)

    def app(self, relative, sandbox):
        path = self.root / relative
        path.parent.mkdir(parents=True)
        path.write_text("#!/bin/sh\n")
        path.chmod(0o755)
        if sandbox:
            (path.parent / "chrome-sandbox").write_text("")
        return path

    def entry(self, exec_line, categories="Network;", actions=""):
        text = "[Desktop Entry]\nType=Application\nName=App\nExec=%s\nCategories=%s\n" % (exec_line, categories)
        if actions:
            text += "Actions=new;\n\n[Desktop Action new]\nName=New\nExec=%s\n" % actions
        return text

    def exec_lines(self, text):
        return [l[5:] for l in text.splitlines() if l.startswith("Exec=")]

    def test_an_electron_app_starts_without_the_sandbox_on_x11(self):
        out = self.gpu["wrap"](self.entry("%s %%U" % self.electron, actions="%s --new" % self.electron))
        self.assertEqual(self.exec_lines(out), [PREFIX + str(self.electron) + FLAGS + " %U",
                                                PREFIX + str(self.electron) + FLAGS + " --new"])
        self.assertIn("X-DroidDeck-GPU=1", out)
        self.assertIn("Actions=new;", out)
        self.assertNotIn("droiddeck-window", out)

    def test_a_command_on_the_path_is_followed_to_its_install_folder(self):
        out = self.gpu["wrap"](self.entry("chat %U"))
        self.assertEqual(self.exec_lines(out), [PREFIX + "chat" + FLAGS + " %U"])

    def test_a_quoted_program_keeps_its_quotes(self):
        out = self.gpu["wrap"](self.entry('"%s" %%U' % self.electron))
        self.assertEqual(self.exec_lines(out), [PREFIX + '"%s"' % self.electron + FLAGS + " %U"])

    def test_only_settings_get_the_adreno_840_renderer_compatibility_entry(self):
        from unittest.mock import patch
        with patch.dict(os.environ, {'DROIDDECK_GPU_NAME': 'Adreno (TM) 840'}):
            out = self.gpu['wrap'](self.entry('systemsettings', categories='Settings;'))
            self.assertEqual(['/usr/local/bin/droiddeck-settings /usr/bin/systemsettings'], self.exec_lines(out))
            out = self.gpu['wrap'](self.entry('plasma-settings -x all %u', categories='Settings;'))
            self.assertEqual(['/usr/local/bin/droiddeck-settings /usr/bin/plasma-settings -x all %u'], self.exec_lines(out))
            self.assertIsNone(self.gpu['wrap'](self.entry('dolphin %U', categories='System;')))
        with patch.dict(os.environ, {'DROIDDECK_GPU_NAME': 'Adreno (TM) 740'}):
            self.assertIsNone(self.gpu['wrap'](self.entry('systemsettings', categories='Settings;')))

    def test_settings_entry_migration_is_idempotent_and_preserves_user_entries(self):
        from unittest.mock import patch
        system = self.root / 'applications'
        user = self.root / 'user-applications'
        system.mkdir()
        user.mkdir()
        for name in ('systemsettings', 'plasma-settings'):
            (system / (name + '.desktop')).write_text(self.entry(name + ' --test %u', categories='Settings;'))
        custom = self.entry('custom-settings')
        (system / 'custom.desktop').write_text(self.entry('systemsettings'))
        (user / 'custom.desktop').write_text(custom)
        self.gpu.update(SYSTEM_DIR=str(system), LOCAL_DIR='', FLATPAK_DIR='', USER_DIR=str(user))
        with patch.dict(os.environ, {'DROIDDECK_GPU_NAME': 'Adreno (TM) 840'}):
            self.gpu['main']()
            first = {p.name: p.read_bytes() for p in user.iterdir()}
            self.gpu['main']()
            self.assertEqual(first, {p.name: p.read_bytes() for p in user.iterdir()})
            for name in ('systemsettings', 'plasma-settings'):
                self.assertEqual(['/usr/local/bin/droiddeck-settings /usr/bin/' + name + ' --test %u'],
                                 self.exec_lines((user / (name + '.desktop')).read_text()))
        self.assertEqual(custom, (user / 'custom.desktop').read_text())
        with patch.dict(os.environ, {'DROIDDECK_GPU_NAME': 'Adreno (TM) 740'}):
            self.gpu['main']()
        self.assertEqual(['custom.desktop'], [p.name for p in user.iterdir()])

    def test_settings_wrapper_scopes_environment_and_preserves_arguments(self):
        import runpy
        from unittest.mock import patch
        wrapper = SCRIPT.parent.parent / 'overlay/usr/local/bin/droiddeck-settings'
        main = runpy.run_path(str(wrapper))['main']
        inherited = {'DROIDDECK_GPU_NAME': 'Adreno (TM) 840', 'GALLIUM_DRIVER': 'zink',
                     'MESA_LOADER_DRIVER_OVERRIDE': 'zink', 'LIBGL_KOPPER_DRI2': 'true',
                     'mesa_glthread': 'true', 'WAYLAND_DISPLAY': 'wayland-0', 'UNCHANGED': 'yes'}
        for program in ('systemsettings', 'plasma-settings'):
            argv = ['/usr/bin/' + program, '-x', 'all', 'argument with spaces']
            with self.subTest(program=program), patch.dict(os.environ, inherited, clear=True), \
                    patch('sys.argv', [str(wrapper)] + argv), patch('os.execve') as execute:
                main()
                target, args, env = execute.call_args.args
                expected = '/usr/local/lib/droiddeck-settings/plasma-settings' if program == 'plasma-settings' else argv[0]
                self.assertEqual(expected, target)
                self.assertEqual([expected, *argv[1:]], args)
                self.assertEqual('rhi', env['QT_QUICK_BACKEND'])
                self.assertEqual('opengl', env['QSG_RHI_BACKEND'])
                self.assertEqual('software', env['QT_OPENGL'])
                self.assertEqual('1', env['LIBGL_ALWAYS_SOFTWARE'])
                self.assertEqual('llvmpipe', env['GALLIUM_DRIVER'])
                self.assertEqual('basic', env['QSG_RENDER_LOOP'])
                for key in ('MESA_LOADER_DRIVER_OVERRIDE', 'LIBGL_KOPPER_DRI2', 'mesa_glthread'):
                    self.assertNotIn(key, env)
                self.assertEqual('yes', env['UNCHANGED'])
                self.assertEqual('wayland-0', env['WAYLAND_DISPLAY'])
                self.assertEqual(inherited, dict(os.environ))
        for gpu in ('Adreno (TM) 740', 'Mali-G720', ''):
            env = dict(inherited, DROIDDECK_GPU_NAME=gpu)
            for program in ('systemsettings', 'plasma-settings'):
                target = '/usr/bin/' + program
                with self.subTest(gpu=gpu, program=program), patch.dict(os.environ, env, clear=True), \
                        patch('sys.argv', [str(wrapper), target]), patch('os.execve') as execute:
                    main()
                    self.assertEqual((target, [target], env), execute.call_args.args)
        for program in ('systemsettings', 'plasma-settings'):
            path = wrapper.parent / program
            self.assertEqual('#!/bin/sh\nexec /usr/local/bin/droiddeck-settings /usr/bin/' + program + ' "$@"\n', path.read_text())
            self.assertTrue(path.stat().st_mode & 0o111)
        for argv in ([], ['/usr/bin/dolphin']):
            with patch('sys.argv', [str(wrapper)] + argv), patch('os.execve') as execute:
                self.assertEqual(64, main())
                execute.assert_not_called()
        self.assertTrue(wrapper.stat().st_mode & 0o111)

    def test_other_apps_are_left_alone(self):
        self.assertIsNone(self.gpu["wrap"](self.entry("%s %%F" % self.plain)))
        self.assertIsNone(self.gpu["wrap"](self.entry("missing-program %F")))

    def test_an_electron_game_goes_to_the_gpu_and_keeps_a_window_action(self):
        out = self.gpu["wrap"](self.entry(str(self.electron), categories="Game;"))
        fixed = PREFIX + str(self.electron) + FLAGS
        self.assertEqual(self.exec_lines(out), ["droiddeck-gpu " + fixed, fixed])

    def test_with_the_gpu_off_electron_apps_are_still_fixed(self):
        out = self.gpu["wrap"](self.entry(str(self.electron), categories="Game;"), False)
        self.assertEqual(self.exec_lines(out), [PREFIX + str(self.electron) + FLAGS])
        self.assertIsNone(self.gpu["wrap"](self.entry(str(self.plain), categories="Game;"), False))

    def test_games_that_are_not_chromium_are_unchanged(self):
        out = self.gpu["wrap"](self.entry(str(self.plain), categories="Game;"))
        self.assertEqual(self.exec_lines(out), ["droiddeck-gpu " + str(self.plain), str(self.plain)])


if __name__ == "__main__":
    unittest.main()
