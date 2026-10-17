import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from release import verify_public_apk


class PublicReleaseTest(unittest.TestCase):
    def test_private_bootstrap_is_rejected_before_external_commands(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'sample.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('assets/bootstrap.json', json.dumps({'packageId': 'private-learning-data'}))
            with patch('release.subprocess.run') as command:
                with self.assertRaisesRegex(ValueError, 'empty bootstrap'):
                    verify_public_apk(apk, 'aapt', 'apksigner', '0' * 64)
                command.assert_not_called()

    def test_wrong_certificate_never_becomes_release_metadata(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'sample.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('assets/bootstrap.json', '{}')
            with patch('release.subprocess.run') as command:
                command.return_value.stdout = 'Signer #1 certificate SHA-256 digest: ' + 'b' * 64
                with self.assertRaisesRegex(ValueError, 'signer'):
                    verify_public_apk(apk, 'aapt', 'apksigner', 'a' * 64)
                self.assertEqual(1, command.call_count)


if __name__ == '__main__':
    unittest.main()
