import copy
import unittest
from unittest.mock import patch

import update_feed


def release(build=425, tag="v0.3.1"):
    name = f"CPI-Kostubet-Edition-0.3.1-build{build}.apk"
    return {"tag_name": tag, "draft": False, "assets": [{"name": name, "size": 33000000,
            "browser_download_url": f"https://github.com/{update_feed.REPO}/releases/download/{tag}/{name}",
            "digest": "sha256:" + "ab" * 32}]}


class UpdateFeedTest(unittest.TestCase):
    def test_latest_build_wins_not_last_edited_release(self):
        self.assertEqual(update_feed.select([release(426, "v0.3.1-build426"), release()])["build"], 426)

    def test_ignores_drafts_bad_checksums_versions_and_foreign_urls(self):
        invalid = []
        for key, value in (("digest", "sha256:bad"), ("size", 0),
                           ("browser_download_url", "https://example.com/update.apk"),
                           ("name", "CPI-Kostubet-Edition-0.3.0-build426.apk")):
            candidate = release(426, "v0.3.1-build426")
            candidate["assets"][0][key] = value
            invalid.append(candidate)
        draft = release(427, "v0.3.1-build427")
        draft["draft"] = True
        invalid += [draft, release(426, "v0.3.1-build999")]
        self.assertEqual(update_feed.select(invalid + [release()])["build"], 425)
        with self.assertRaises(ValueError):
            update_feed.select(invalid)

    def test_publisher_preserves_feed_when_rerun_or_older_release_edited(self):
        manifest = update_feed.select([release()])
        import base64
        old = {"sha": "expected-sha", "content": base64.b64encode(update_feed.encode(manifest)).decode()}
        with patch("sys.argv", ["update_feed.py", "--publish"]), patch.object(update_feed, "api", side_effect=[[release()], old]) as api:
            update_feed.main()
            self.assertEqual(api.call_count, 2)
        newer = copy.deepcopy(manifest)
        newer["build"] = 500
        old["content"] = base64.b64encode(update_feed.encode(newer)).decode()
        with patch("sys.argv", ["update_feed.py", "--publish"]), patch.object(update_feed, "api", side_effect=[[release()], old]) as api:
            with self.assertRaises(ValueError):
                update_feed.main()
            self.assertEqual(api.call_count, 2)


if __name__ == "__main__":
    unittest.main()
