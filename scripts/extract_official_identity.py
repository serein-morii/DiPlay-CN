#!/usr/bin/env python3
"""Extract runtime identity files from the official public APK for one local/CI build."""
import hashlib
import sys
import zipfile
from pathlib import Path

REQUIRED = ("identity.pk8", "certificate.p7b")


def main() -> None:
    if len(sys.argv) not in (3, 4):
        raise SystemExit(
            "usage: extract_official_identity.py <official.apk> <assets-dir> [sha256]"
        )
    apk = Path(sys.argv[1])
    dest_root = Path(sys.argv[2])
    expected = sys.argv[3].lower() if len(sys.argv) == 4 else None
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if expected and digest != expected:
        raise SystemExit(f"Official APK SHA-256 mismatch: {digest} != {expected}")
    dest = dest_root / "offline-mfi"
    dest.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(apk) as archive:
        for name in REQUIRED:
            payload = archive.read(f"assets/offline-mfi/{name}")
            if not payload:
                raise SystemExit(f"{name} is empty in the official APK")
            (dest / name).write_bytes(payload)
    print("Official identity assets extracted for this run only")


if __name__ == "__main__":
    main()
