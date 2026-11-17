from pathlib import Path
import importlib.machinery
import importlib.util
import os
import runpy
import subprocess
import tempfile
import unittest
from unittest.mock import patch

BIN=Path(__file__).resolve().parents[1]/'linuxfs/overlay/usr/local/bin'


class DesktopNavigationTest(unittest.TestCase):
    def test_guest_actions_are_allowlisted_and_do_not_execute_arbitrary_commands(self):
        loader=importlib.machinery.SourceFileLoader('nav_agent',str(BIN/'droiddeck-agent'))
        spec=importlib.util.spec_from_loader(loader.name,loader);module=importlib.util.module_from_spec(spec);loader.exec_module(module)
        with patch.object(module.subprocess,'run',return_value=subprocess.CompletedProcess([],0,'','')) as run:
            self.assertEqual({'action':'home'},module.handle(Path('/tmp'),{'kind':'desktop-navigation','action':'home'}))
            self.assertEqual(['/usr/local/bin/droiddeck-desktop-navigate','home'],run.call_args.args[0])
            with self.assertRaises(module.AgentError):module.handle(Path('/tmp'),{'kind':'desktop-navigation','action':'kill'})

    def test_shell_uses_software_qt_only_without_changing_global_driver(self):
        module=runpy.run_path(str(BIN/'droiddeck-mobile-shell'))
        with patch('subprocess.call',return_value=0) as call, patch('sys.argv',['droiddeck-mobile-shell','-p','org.kde.plasma.mobileshell']),patch.dict(os.environ,{'GALLIUM_DRIVER':'zink','MESA_LOADER_DRIVER_OVERRIDE':'zink'}):
            self.assertEqual(0,module['main']())
            self.assertEqual('software',call.call_args.kwargs['env']['QT_QUICK_BACKEND'])
            self.assertEqual('llvmpipe',call.call_args.kwargs['env']['GALLIUM_DRIVER'])
            self.assertNotIn('MESA_LOADER_DRIVER_OVERRIDE',call.call_args.kwargs['env'])
            self.assertEqual('zink',os.environ['GALLIUM_DRIVER'])

    def shell(self, gpu):
        module=runpy.run_path(str(BIN/'droiddeck-mobile-shell'))
        with tempfile.TemporaryDirectory() as home, patch('subprocess.call',return_value=0) as call,\
                patch('sys.argv',['droiddeck-mobile-shell']), patch.dict(os.environ,{'HOME':home,'DROIDDECK_GPU_NAME':gpu},clear=True):
            module['main']()
            conf=Path(home)/'.config/QtProject.conf'
            return call.call_args.kwargs['env'], conf.read_text() if conf.exists() else None

    def test_adreno_840_shell_has_no_paint_device_on_the_gpu(self):
        env,conf=self.shell('Adreno (TM) 840')
        self.assertIn('SceneGraphBackend=software',conf)
        self.assertNotIn('QT_OPENGL',env)

    def test_other_gpus_keep_the_shell_on_its_configured_backend(self):
        env,conf=self.shell('Adreno (TM) 740')
        self.assertIsNone(conf)
        self.assertNotIn('QT_OPENGL',env)
