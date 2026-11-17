from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / 'linuxfs/sftp-prctl.c'


@unittest.skipUnless(shutil.which('cc'), 'C compiler required')
class SftpPrctlTest(unittest.TestCase):
    def test_only_normalizes_the_proot_simulated_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            text = SOURCE.read_text().replace('#include <sys/prctl.h>', '#define PR_SET_DUMPABLE 4')
            text = text.replace('int prctl(int option, ...)', 'int wrapped_prctl(int option, ...)')
            text = text.replace('dlsym(RTLD_NEXT, "prctl")', 'fake_prctl')
            text = 'static int result;\nstatic int fake_prctl(int o,unsigned long a,unsigned long b,unsigned long c,unsigned long d){return result;}\n' + text
            text += '\nint main(void){result=4;if(wrapped_prctl(4,0L,0L,0L,0L)!=0)return 1;result=-1;if(wrapped_prctl(4,0L,0L,0L,0L)!=-1)return 2;result=4;if(wrapped_prctl(3,0L,0L,0L,0L)!=4)return 3;return 0;}\n'
            (root/'probe.c').write_text(text)
            subprocess.run(['cc',str(root/'probe.c'),'-o',str(root/'probe')],check=True,capture_output=True)
            subprocess.run([str(root/'probe')],check=True)
