"""Publish a small, quota-free Android update manifest from verified release metadata."""
import argparse
import base64
import json
import os
import re
import urllib.error
import urllib.request

REPO = "Kostubet/CPI-Kostubet-Edition"
PATH = "updates/latest.json"
TAG = re.compile(r"v(\d+\.\d+\.\d+)(?:-build(\d+))?")
APK = re.compile(r"CPI-Kostubet-Edition-(\d+\.\d+\.\d+)-build(\d+)\.apk")


def select(releases):
    best = None
    for release in releases:
        tag = release.get("tag_name", "")
        match = TAG.fullmatch(tag)
        if release.get("draft") or not match:
            continue
        version, tagged_build = match.groups()
        for asset in release.get("assets", []):
            name = asset.get("name", "")
            file_match = APK.fullmatch(name)
            if not file_match:
                continue
            file_version, file_build = file_match.groups()
            build = int(file_build)
            digest = asset.get("digest") or ""
            size = asset.get("size", 0)
            url = f"https://github.com/{REPO}/releases/download/{tag}/{name}"
            if (file_version != version or (tagged_build and tagged_build != file_build)
                    or not 0 < build <= 2147483647 or not 0 < size <= 128 * 1024 * 1024
                    or asset.get("browser_download_url") != url
                    or not re.fullmatch(r"sha256:[0-9a-fA-F]{64}", digest)):
                continue
            if best is None or build > best["build"]:
                best = {"format": 1, "tag": tag, "version": version, "build": build,
                        "apk": {"name": name, "url": url, "size": size, "sha256": digest[7:].lower()}}
    if best is None:
        raise ValueError("No published CPI APK with a valid SHA-256 digest")
    return best


def encode(manifest):
    return (json.dumps(manifest, indent=2) + "\n").encode()


def api(path, payload=None):
    token = os.environ["GH_TOKEN"]
    request = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/{path}",
        data=None if payload is None else json.dumps(payload).encode(),
        headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
                 "User-Agent": "CPI-update-feed", "Content-Type": "application/json"},
        method="GET" if payload is None else "PUT")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publish", action="store_true", help="Commit the manifest to main")
    args = parser.parse_args()
    releases = []
    for page in range(1, 11):
        batch = api(f"releases?per_page=100&page={page}")
        releases.extend(batch)
        if len(batch) < 100:
            break
    manifest = select(releases)
    content = encode(manifest)
    if not args.publish:
        print(content.decode(), end="")
        return
    # Read the expected file SHA immediately before writing. A race fails safely,
    # and editing an older release never rolls the feed back to an older build.
    try:
        current = api(f"contents/{PATH}?ref=main")
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        current = None
    payload = {"message": f"Update release feed to build {manifest['build']}", "branch": "main",
               "content": base64.b64encode(content).decode()}
    if current:
        old = base64.b64decode(current["content"])
        if old == content:
            print(f"Feed already advertises build {manifest['build']}")
            return
        if json.loads(old).get("build", 0) > manifest["build"]:
            raise ValueError("Refusing to downgrade the published update feed")
        payload["sha"] = current["sha"]
    api(f"contents/{PATH}", payload)
    print(f"Published update feed for build {manifest['build']}")


if __name__ == "__main__":
    main()
