#!/usr/bin/env python3
"""Reject a CN release whose Android versionCode cannot upgrade prior CN builds."""

import re
import subprocess
import sys
from pathlib import Path


CN_TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)(?:\.(\d+))?-cn\.(\d+)$")
VERSION_CODE = re.compile(r"^\s*versionCode\s*=\s*(\d+)\s*$", re.MULTILINE)
VERSION_NAME = re.compile(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', re.MULTILINE)


def config(text: str) -> tuple[int, str]:
    code = VERSION_CODE.search(text)
    name = VERSION_NAME.search(text)
    if not code or not name:
        raise ValueError("mobile/build.gradle.kts must declare versionCode and versionName")
    return int(code.group(1)), name.group(1)


def version(tag: str) -> tuple[int, int, int, int, int]:
    match = CN_TAG.fullmatch(tag)
    if not match:
        raise ValueError(f"invalid CN tag: {tag}")
    major, minor, patch, hotfix, cn_revision = match.groups()
    return int(major), int(minor), int(patch), int(hotfix or 0), int(cn_revision)


def check(current_tag: str, current_text: str, prior: dict[str, str]) -> None:
    version(current_tag)
    current_code, current_name = config(current_text)
    if current_name != current_tag.removeprefix("v"):
        raise ValueError(f"tag {current_tag} does not match versionName {current_name}")
    prior_codes = {}
    for tag, text in prior.items():
        if tag == current_tag:
            continue
        version(tag)
        prior_codes[tag] = config(text)[0]
    if prior_codes:
        highest_tag = max(prior_codes, key=prior_codes.get)
        highest_code = prior_codes[highest_tag]
        if current_code <= highest_code:
            raise ValueError(
                f"versionCode {current_code} must exceed {highest_code} from {highest_tag}"
            )
    print(f"Release version accepted: {current_tag} (versionCode {current_code})")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: check_release_version.py vX.Y.Z-cn.N")
    current_tag = sys.argv[1]
    version(current_tag)
    tags = subprocess.check_output(
        ["git", "tag", "--list", "v*-cn.*"], text=True
    ).splitlines()
    prior = {}
    for tag in tags:
        if tag == current_tag or not CN_TAG.fullmatch(tag):
            continue
        prior[tag] = subprocess.check_output(
            ["git", "show", f"{tag}:mobile/build.gradle.kts"], text=True
        )
    check(current_tag, Path("mobile/build.gradle.kts").read_text(), prior)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"::error::{error}") from error
