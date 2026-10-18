#!/usr/bin/env python3
"""Map every settings action in a head-unit firmware image to the component that answers it.

BYD head units answer a long list of standard `android.settings.*` actions with a decoy activity
that reports "the multimedia system does not support this operation", and a `startActivity` that
reaches it succeeds. This builds the table needed to see which actions are intercepted and which
real activity, if any, answers the rest. See docs/BYD_SETTINGS_ENTRY_POINTS.md.

    python3 scripts/byd_firmware_actions.py --image system.img --out build/byd-actions

Everything is read out of the firmware; nothing is run on a car. Needs `7z` and `aapt2` (the latter
ships in the Android SDK build-tools).
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

# The BYD activity that intercepts standard settings actions on DiLink 1.0.
DEFAULT_DECOY = "com.byd.vehiclesettings.main.unsupport.UnsupportActivity"

# Actions worth reporting on by default: everything DiPlay sends, plus the candidates it could send.
DEFAULT_ACTIONS = [
    "android.settings.BLUETOOTH_SETTINGS",
    "android.settings.WIRELESS_SETTINGS",
    "android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
    "android.settings.APPLICATION_DETAILS_SETTINGS",
    "android.settings.ACCESSIBILITY_SETTINGS",
    "android.settings.USAGE_ACCESS_SETTINGS",
    "android.settings.WIFI_SETTINGS",
    "android.settings.action.MANAGE_WRITE_SETTINGS",
    "android.settings.action.MANAGE_OVERLAY_PERMISSION",
    "com.android.settings.WIFI_TETHER_SETTINGS",
    "android.intent.action.HOTSPOT_SETTINGS",
    "android.intent.action.DEVELOPMENT_SETTINGS",
    "android.settings.SETTINGS",
]

ELEM = re.compile(r"^(\s*)E: (\S+)")
ATTR = re.compile(r'^(\s*)A: (\S+?)\(0x[0-9a-f]+\)=(.*?)(?:\s+\(Raw:.*\))?$')
ATTR_PLAIN = re.compile(r'^(\s*)A: ([A-Za-z0-9_]+)=(.*?)(?:\s+\(Raw:.*\))?$')

# Components that can carry an <intent-filter>.
COMPONENTS = {"activity", "activity-alias", "service", "receiver"}


def unquote(value):
    value = value.strip()
    if len(value) >= 2 and value[0] == '"' and value[-1] == '"':
        return value[1:-1]
    return value


def parse_manifest(text):
    """Yield one record per component that declares an intent-filter."""
    stack = []          # [(indent, element name)]
    current = None      # component being built
    package = None
    pending = None      # which intent-filter child the next name attribute belongs to

    for line in text.splitlines():
        element = ELEM.match(line)
        if element:
            indent, name = len(element.group(1)), element.group(2)
            while stack and stack[-1][0] >= indent:
                stack.pop()
            parent = stack[-1][1] if stack else None
            stack.append((indent, name))
            if name in COMPONENTS:
                if current is not None:
                    yield current
                current = {"package": package, "component": None, "kind": name,
                           "exported": None, "permission": None, "priority": None,
                           "actions": []}
            pending = name if parent == "intent-filter" else None
            continue

        attr = ATTR.match(line) or ATTR_PLAIN.match(line)
        if not attr:
            continue
        # A namespaced attribute prints as
        # "http://schemas.android.com/apk/res/android:name(0x01010003)", so the lazy group
        # captures the whole URI. Reduce it to the bare attribute name.
        key = attr.group(2).rsplit(":", 1)[-1]
        value = unquote(attr.group(3))
        top = stack[-1][1] if stack else None

        if top == "manifest" and key == "package":
            package = value
            if current is not None:
                current["package"] = package
        elif top in COMPONENTS and current is not None and key == "name" and current["component"] is None:
            current["component"] = value
            current["package"] = package
        elif top in COMPONENTS and current is not None and key in ("exported", "permission"):
            current[key] = value
        elif top == "intent-filter" and current is not None and key == "priority":
            current["priority"] = value
        elif top == "action" and current is not None and key == "name" and pending == "action":
            current["actions"].append(value)

    if current is not None:
        yield current


def extract_apks(image, destination):
    if not shutil.which("7z"):
        raise SystemExit("7z is required to unpack an image; pass --apks to skip extraction")
    destination.mkdir(parents=True, exist_ok=True)
    subprocess.run(["7z", "x", "-y", "-o" + str(destination), str(image), "-ir!*.apk"],
                   stdout=subprocess.DEVNULL, check=True)


def collect_apks(root):
    found = []
    for dirpath, _, files in os.walk(root):
        found += [os.path.join(dirpath, name) for name in files if name.endswith(".apk")]
    return sorted(found)


def scan(apks, root):
    """Return (action -> responders, every component) across all APKs."""
    aapt2 = shutil.which("aapt2")
    if not aapt2:
        raise SystemExit("aapt2 is required; it ships in the Android SDK build-tools")

    actions, components, failed = {}, [], []
    for apk in apks:
        dump = subprocess.run(
            [aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
            capture_output=True, text=True, errors="replace",
        ).stdout
        if not dump.strip():
            failed.append(os.path.relpath(apk, root))
            continue
        for comp in parse_manifest(dump):
            if not comp["component"]:
                continue
            comp["apk"] = os.path.relpath(apk, root)
            components.append(comp)
            for action in comp["actions"]:
                actions.setdefault(action, []).append({
                    "package": comp["package"], "component": comp["component"],
                    "kind": comp["kind"], "exported": comp["exported"],
                    "permission": comp["permission"], "priority": comp["priority"],
                })
    return actions, components, failed


def report(actions, components, decoy, wanted):
    print()
    print("== actions DiPlay sends ==")
    for action in wanted:
        responders = actions.get(action, [])
        caught = any(r["component"] == decoy for r in responders)
        if caught:
            verdict = "DECOY"
        elif responders:
            verdict = "real screen"
        else:
            verdict = "no responder"
        print(f"\n[{verdict}] {action}")
        for responder in sorted(responders, key=lambda r: -int(r["priority"] or 0)):
            note = "  <-- decoy" if responder["component"] == decoy else ""
            flags = [f"prio={responder['priority'] or '-'}",
                     f"exported={responder['exported'] or 'default'}"]
            if responder["permission"]:
                flags.append(f"requires {responder['permission']}")
            print(f"    {responder['package']}/{responder['component']}  [{', '.join(flags)}]{note}")

    claimed = [a for a, rs in actions.items() if any(r["component"] == decoy for r in rs)]
    orphans = [a for a in claimed if not any(r["component"] != decoy for r in actions[a])]
    print()
    print(f"== decoy claims {len(claimed)} actions; {len(orphans)} of them have no other responder ==")
    print("   (an intercepted action with no other responder can only be reached by explicit component)")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--image", type=Path, help="system.img to unpack")
    source.add_argument("--apks", type=Path, help="directory of already-extracted APKs")
    parser.add_argument("--out", type=Path, default=Path("build/byd-actions"),
                        help="where to write the extraction and action-map.json")
    parser.add_argument("--decoy", default=DEFAULT_DECOY, help="component to treat as the decoy")
    parser.add_argument("--action", action="append", default=None,
                        help="action to report on; repeatable (default: the DiPlay set)")
    args = parser.parse_args()

    root = args.apks
    if args.image:
        root = args.out / "apks"
        extract_apks(args.image, root)

    apks = collect_apks(root)
    if not apks:
        raise SystemExit(f"no APKs found under {root}")

    actions, components, failed = scan(apks, root)
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "action-map.json").write_text(
        json.dumps({"actions": actions, "components": components}, indent=1))

    print(f"APKs scanned  : {len(apks)} (unreadable: {len(failed)})")
    print(f"components    : {len(components)}")
    print(f"distinct actions: {len(actions)}")
    print(f"action map    : {args.out / 'action-map.json'}")

    report(actions, components, args.decoy, args.action or DEFAULT_ACTIONS)


if __name__ == "__main__":
    sys.exit(main())
