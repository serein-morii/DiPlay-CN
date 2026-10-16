import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import sync_gitee_experiment as mirror


class ExperimentMirrorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.sha = "123456789abc" + "0" * 28
        self.tag = "experiment-turn-camera-123456789abc"
        self.apk = self.directory / f"DiPlay-cn-{self.tag}.apk"
        self.apk.write_bytes(b"test package, not an APK")
        self.apk.with_suffix(".apk.sha256").write_text(
            f"{hashlib.sha256(self.apk.read_bytes()).hexdigest()}  {self.apk.name}\n")
        (self.directory / "release-notes.md").write_text(f"实验性\nSource commit: `{self.sha}`\n")

    def test_only_sha_bound_experiments_are_accepted(self):
        self.assertEqual(self.apk, mirror.validate(self.tag, self.sha, self.directory)[0])
        for tag, sha in [("v0.2.20-cn.1", self.sha), (self.tag, "f" * 40), (self.tag, "main")]:
            with self.assertRaises(ValueError):
                mirror.validate(tag, sha, self.directory)

    def test_corrupt_local_package_is_rejected(self):
        self.apk.write_bytes(b"different")
        with self.assertRaises(ValueError):
            mirror.validate(self.tag, self.sha, self.directory)

    def test_existing_bytes_must_match_without_overwrite(self):
        release = {"assets": [{"name": self.apk.name, "browser_download_url": "https://gitee.com/test.apk"}]}
        with patch.object(mirror.request, "urlopen", return_value=io.BytesIO(self.apk.read_bytes())):
            self.assertTrue(mirror.verified_asset(release, self.apk))
        with patch.object(mirror.request, "urlopen", return_value=io.BytesIO(b"bad")):
            with self.assertRaises(ValueError):
                mirror.verified_asset(release, self.apk)
        release["assets"][0]["browser_download_url"] = "https://example.com/test.apk"
        with self.assertRaises(ValueError):
            mirror.verified_asset(release, self.apk)

    def test_missing_remote_source_prevents_release_creation(self):
        with patch.object(mirror, "api", return_value=None) as api:
            with self.assertRaises(ValueError):
                mirror.sync(self.directory, self.tag, self.sha, "test-token")
            self.assertEqual(1, api.call_count)

    def test_wrong_existing_tag_prevents_upload(self):
        with patch.object(mirror, "api", side_effect=[{"sha": self.sha}, {"id": 1}, {"sha": "f" * 40}]) as api:
            with self.assertRaises(ValueError):
                mirror.sync(self.directory, self.tag, self.sha, "test-token")
            self.assertEqual(3, api.call_count)


if __name__ == "__main__":
    unittest.main()
