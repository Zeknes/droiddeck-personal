import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'com.droiddeck.launcher'


class SignApkRenamesTest(unittest.TestCase):
    def test_pure_brand_has_an_independent_install_identity_and_stable_signer(self):
        manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
        gradle = (ROOT / 'app/build.gradle').read_text()
        self.assertIn('android:label="@string/app_name"', manifest)
        self.assertIn('applicationId "com.zeknes.droiddeck.pure"', gradle)
        self.assertIn('versionCode 36', gradle)
        self.assertIn('versionName "0.7.7"', gradle)
        self.assertIn('shell pm path com.zeknes.droiddeck.pure', (ROOT / 'tools/deploy_local.sh').read_text())
        self.assertIn('pkg=${pkg:-com.zeknes.droiddeck.pure}', (ROOT / 'tools/agent/dd-lab-build').read_text())
        variants = (ROOT / 'tools/release/variants.txt').read_text().split()
        self.assertEqual(['standard', 'com.zeknes.droiddeck.pure', '-'], variants)
        self.assertEqual('2e78e732f7ce5dea5225a7266a3b6bb5c5d55930d0f1db38433d1b54d699d531',
                         (ROOT / 'keystore/release-signer.sha256').read_text().strip())
        for path in (ROOT / 'app/src/main/res').glob('values*/strings.xml'):
            self.assertIn('<string name="app_name">DroidDeck Pure</string>', path.read_text(), str(path))

    def test_variants_rename_every_authority_and_task_named_after_the_package(self):
        manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
        names = set()
        for attribute in ('authorities', 'taskAffinity'):
            for value in re.findall(r'android:%s="([^"]*)"' % attribute, manifest):
                self.assertNotIn(';', value, 'sign-apk.sh renames whole values, so give each authority its own provider')
                name = value.strip().replace('${applicationId}', PACKAGE)
                if name.startswith(PACKAGE + '.'):
                    names.add(name[len(PACKAGE):])
        self.assertIn('.agent', names)
        script = (ROOT / 'tools/release/sign-apk.sh').read_text()
        renamed = set(re.findall(r'"\$P(\.[A-Za-z0-9_.-]+)=\$pkg\1"', script))
        self.assertEqual(set(), names - renamed, 'add these to the axml_rename call in tools/release/sign-apk.sh')


if __name__ == '__main__':
    unittest.main()
