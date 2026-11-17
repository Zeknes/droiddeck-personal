from pathlib import Path
import os
import runpy
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1]/'linuxfs/overlay/usr/local/bin/droiddeck-maintenance'


class MaintenanceTest(unittest.TestCase):
    def test_start_is_opt_in(self):
        module=runpy.run_path(str(SCRIPT))
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ,HOME=tmp), patch('subprocess.run') as run:
            module['start']()
            run.assert_not_called()

    def test_private_key_is_rejected_before_writes(self):
        module=runpy.run_path(str(SCRIPT))
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ,HOME=tmp):
            with self.assertRaises(ValueError):module['configure']('-----BEGIN OPENSSH PRIVATE KEY-----')
            self.assertFalse((Path(tmp)/'.ssh').exists())

    def test_preserves_authorized_keys_and_host_key_on_repeat(self):
        module=runpy.run_path(str(SCRIPT))
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ,HOME=tmp):
            home=Path(tmp); (home/'.ssh').mkdir(); auth=home/'.ssh/authorized_keys';auth.write_text('existing-key\n')
            host=home/'.local/share/droiddeck/ssh/host_ed25519';host.parent.mkdir(parents=True);host.write_text('existing-host')
            real_is_file=Path.is_file
            def is_file(path):return True if str(path)=='/usr/local/lib/droiddeck/sftp-prctl.so' else real_is_file(path)
            with patch('shutil.which',return_value='/usr/sbin/sshd'), patch.object(Path,'is_file',is_file), patch('subprocess.run'), patch.dict(module['configure'].__globals__,start=lambda:None):
                module['configure']('ssh-ed25519 fixture test')
                module['configure']('ssh-ed25519 fixture test')
            self.assertEqual('existing-host',host.read_text())
            self.assertEqual('existing-key\nssh-ed25519 fixture test\n',auth.read_text())
            config=(home/'.config/droiddeck/sshd_config').read_text()
            self.assertIn('ListenAddress 127.0.0.1',config)
            self.assertIn('PasswordAuthentication no',config)
            self.assertIn('AuthenticationMethods publickey',config)
