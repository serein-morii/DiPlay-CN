#!/usr/bin/env python3
"""Fail if a built APK is missing the two runtime identity files."""
import sys
import zipfile
from pathlib import Path

REQUIRED = (
    "assets/offline-mfi/identity.pk8",
    "assets/offline-mfi/certificate.p7b",
)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify_apk_identity.py <built.apk>")
    apk = Path(sys.argv[1])
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        for name in REQUIRED:
            if name not in names:
                raise SystemExit(f"built APK is missing {name}")
            if archive.getinfo(name).file_size <= 0:
                raise SystemExit(f"built APK has empty {name}")
    print("APK contains runtime identity assets")


if __name__ == "__main__":
    main()
