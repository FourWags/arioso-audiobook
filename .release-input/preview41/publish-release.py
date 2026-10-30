#!/usr/bin/env python3
"""Publish the already verified Preview 41 files from a GitHub Actions draft."""

import hashlib
import json
import mimetypes
import os
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent
REPO = "FourWags/arioso-audiobook"
TAG = "v0.1.0-preview.41"
TOKEN = os.environ["GH_TOKEN"]
EXPECTED_COMMIT = os.environ["GITHUB_SHA"]
API = "https://api.github.com/repos/" + REPO
ASSETS = (
    "arioso-preview.apk",
    "arioso-preview.apk.idsig",
    "arioso-source.zip",
    "arioso-signing-cert.pem",
    "CHECKS.txt",
    "RELEASE-NOTES.md",
    "RELEASE-SOURCE.json",
    "SHA256SUMS",
    "SOURCE-SHA256SUMS",
    "source-manifest.json",
)


def request(method, url, data=None, content_type="application/vnd.github+json"):
    headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": "Bearer " + TOKEN,
        "User-Agent": "FourWags-Arioso-Release",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if data is not None:
        headers["Content-Type"] = content_type
    with urlopen(Request(url, data=data, headers=headers, method=method), timeout=120) as response:
        return json.load(response)


def require_hashes():
    expected = {}
    for line in (ROOT / "SHA256SUMS").read_text().splitlines():
        digest, name = line.split("  ", 1)
        expected[name] = digest
    for name, digest in expected.items():
        actual = hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
        if actual != digest:
            raise RuntimeError("Checksum mismatch: " + name)
    if not set(ASSETS).issubset(set(expected) | {"SHA256SUMS"}):
        raise RuntimeError("Release asset missing from checksum manifest")
    if expected["arioso-preview.apk"] != "291e5b914e3afdd74f73096a930c4def2611d6497898c08481ce3948cc1c2061":
        raise RuntimeError("Unexpected APK identity")
    return expected


def main():
    expected = require_hashes()
    notes = (ROOT / "GITHUB-NOTES.md").read_text()
    try:
        release = request("GET", API + "/releases/tags/" + TAG)
    except HTTPError as error:
        if error.code != 404:
            raise
        release = request("POST", API + "/releases", json.dumps({
            "tag_name": TAG,
            "target_commitish": EXPECTED_COMMIT,
            "name": "Preview 0.1.0-preview.41 — Library Rail",
            "body": notes,
            "draft": True,
            "prerelease": False,
        }).encode())
    if not release["draft"]:
        raise RuntimeError("Release already published; refusing to replace assets")
    upload_url = release["upload_url"].split("{", 1)[0]
    existing = {asset["name"]: asset for asset in release["assets"]}
    for name in ASSETS:
        payload = (ROOT / name).read_bytes()
        if name in existing:
            asset = existing[name]
            digest = asset.get("digest")
            if asset["size"] != len(payload) or (digest and digest != "sha256:" + hashlib.sha256(payload).hexdigest()):
                raise RuntimeError("Existing asset differs: " + name)
            continue
        content_type = mimetypes.guess_type(name)[0] or "application/octet-stream"
        uploaded = request("POST", upload_url + "?name=" + quote(name), payload, content_type)
        if uploaded["size"] != len(payload):
            raise RuntimeError("Uploaded size differs: " + name)
        digest = uploaded.get("digest")
        if digest and digest != "sha256:" + hashlib.sha256(payload).hexdigest():
            raise RuntimeError("Uploaded digest differs: " + name)
    release = request("PATCH", API + "/releases/" + str(release["id"]), json.dumps({
        "body": notes,
        "draft": False,
        "prerelease": False,
        "make_latest": "true",
    }).encode())
    if release["draft"] or release["prerelease"] or release["tag_name"] != TAG:
        raise RuntimeError("Release did not publish as intended")
    print("Published", release["html_url"], "with", len(ASSETS), "assets")


if __name__ == "__main__":
    main()
