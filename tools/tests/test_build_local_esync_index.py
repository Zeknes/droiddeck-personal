from pathlib import Path
import unittest

BUILD_LOCAL = Path(__file__).resolve().parents[1] / 'build_local.sh'


class EsyncIndexTest(unittest.TestCase):
    def test_revoked_packs_are_read_from_the_verified_bundle(self):
        # The index the bundle was signed with is unpacked, not downloaded again: the release asset URL for
        # it expires, and a fresh download would either fail the build or skip the checksum for it.
        text = BUILD_LOCAL.read_text()
        start = text.index('sync_index=$(mktemp)')
        end = text.index('elif', start)
        section = text[start:end]
        self.assertIn('tar -xOf "${sync_archive}" index.json', section)
        self.assertNotIn('gh release download', section)


if __name__ == '__main__':
    unittest.main()
