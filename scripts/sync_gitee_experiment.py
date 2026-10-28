#!/usr/bin/env python3
"""Attach already-built experimental APK bytes to the user's Gitee mirror.

No build, stable release edit, replacement upload, token logging, or token-in-URL.
Re-running is only allowed for identical files at the identical source commit.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from urllib import error, parse, request
import uuid

API = "https://gitee.com/api/v5/repos/oneeyear/DiPlay-CN"


def validate(tag, sha, directory):
    if not re.fullmatch(r"experiment-turn-camera-[0-9a-f]{12}", tag):
        raise ValueError("Invalid experiment tag")
    if not re.fullmatch(r"[0-9a-f]{40}", sha) or not sha.startswith(tag.rsplit("-", 1)[1]):
        raise ValueError("Experiment tag must identify SOURCE_SHA")
    apk = directory / f"DiPlay-cn-{tag}.apk"
    checksum = apk.with_suffix(".apk.sha256")
    actual = hashlib.sha256(apk.read_bytes()).hexdigest()
    if checksum.read_text().strip() != f"{actual}  {apk.name}":
        raise ValueError("APK checksum mismatch")
    notes = (directory / "release-notes.md").read_text()
    if sha not in notes or "实验性" not in notes:
        raise ValueError("Notes must identify the source and experimental status")
    return apk, checksum, notes


def api(path, token, fields=None, file=None):
    headers = {"User-Agent": "DiPlay-experiment-mirror"}
    data = None
    if file is not None:
        boundary = "diplay-" + uuid.uuid4().hex
        data = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"access_token\"\r\n\r\n{token}\r\n"
                f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{file.name}\"\r\n"
                "Content-Type: application/octet-stream\r\n\r\n").encode()
        data += file.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
        headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
    elif fields is not None:
        data = parse.urlencode({**fields, "access_token": token}).encode()
    try:
        with request.urlopen(request.Request(API + path, data=data, headers=headers), timeout=180) as response:
            return json.load(response)
    except error.HTTPError as failure:
        if failure.code == 404 and data is None:
            return None
        raise RuntimeError(f"Gitee API failed (HTTP {failure.code})") from None
    except (error.URLError, TimeoutError):
        raise RuntimeError("Gitee API connection failed") from None


def verified_asset(release, file):
    assets = release.get("assets", [])
    matches = [a for a in assets if a.get("name") == file.name]
    if not matches:
        return False
    if len(matches) != 1:
        raise ValueError("Duplicate mirror asset; refusing replacement")
    url = matches[0].get("browser_download_url", "")
    parsed = parse.urlparse(url)
    if parsed.scheme != "https" or parsed.hostname not in ("gitee.com", "www.gitee.com"):
        raise ValueError("Unexpected mirror asset URL")
    try:
        with request.urlopen(url, timeout=180) as response:
            digest = hashlib.sha256()
            size = 0
            while block := response.read(1024 * 1024):
                size += len(block)
                if size > file.stat().st_size:
                    raise ValueError("Mirror asset size differs; refusing replacement")
                digest.update(block)
    except (error.URLError, TimeoutError):
        raise RuntimeError("Mirror asset cannot be downloaded; refusing duplicate upload") from None
    if size != file.stat().st_size or digest.digest() != hashlib.sha256(file.read_bytes()).digest():
        raise ValueError("Mirror asset bytes differ; refusing replacement")
    return True


def sync(directory, tag, sha, token):
    apk, checksum, notes = validate(tag, sha, directory)
    if not token:
        raise ValueError("Existing GITEE_TOKEN secret is unavailable")
    tag_path = "/releases/tags/" + parse.quote(tag, safe="")
    # The commit must already have been uploaded to the user's mirror branch.
    commit = api("/commits/" + sha, token)
    if not commit or commit.get("sha") != sha:
        raise ValueError("Source commit is not present on the Gitee mirror")
    release = api(tag_path, token)
    if release is None:
        release = api("/releases", token, fields={
            "tag_name": tag, "target_commitish": sha,
            "name": f"DiPlay 转向影像与仪表联动（实验性） · {sha[:12]}", "body": notes,
        })
    if not isinstance(release, dict) or not isinstance(release.get("id"), int):
        raise ValueError("Gitee did not return a release identity")
    # Resolve the actual tag commit, not the mutable target_commitish string.
    ref = api("/commits/" + parse.quote(tag, safe=""), token)
    if ref is None or ref.get("sha") != sha:
        raise ValueError("Gitee tag does not match the approved source commit")
    for file in (apk, checksum):
        release = api(tag_path, token)
        if not verified_asset(release, file):
            api(f"/releases/{release['id']}/attach_files", token, file=file)
            if not verified_asset(api(tag_path, token), file):
                raise ValueError("Gitee attachment was not returned for verification")
        print(f"Verified identical Gitee download: {file.name}")
    print(f"Gitee experimental delivery verified: {tag} ({sha})")


if __name__ == "__main__":
    try:
        sync(Path(sys.argv[1]), os.environ.get("RELEASE_TAG", ""),
             os.environ.get("SOURCE_SHA", ""), os.environ.get("GITEE_TOKEN", ""))
    except Exception as failure:
        # Do not include request/response bodies or credentials in CI logs.
        print(f"Experimental mirror failed: {type(failure).__name__}: {failure}", file=sys.stderr)
        sys.exit(1)
