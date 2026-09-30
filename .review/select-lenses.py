#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Print the JSON list of lenses whose trigger globs match any of the changed paths given as arguments."""
import json
import pathlib
import re
import sys
from fnmatch import fnmatch

LENS_DIR = pathlib.Path(__file__).parent / "lenses"


def front_matter(text: str) -> dict:
    match = re.match(r"^---\n(.*?)\n---\n", text, re.S)
    if not match:
        raise ValueError("lens file has no front matter")
    meta = {}
    for line in match.group(1).splitlines():
        key, _, value = line.partition(":")
        value = value.strip()
        meta[key.strip()] = json.loads(value) if value.startswith("[") else value
    return meta


def main(changed: list[str]) -> None:
    selected = []
    for path in sorted(LENS_DIR.glob("*.md")):
        meta = front_matter(path.read_text(encoding="utf-8"))
        triggers = meta.get("triggers", ["**"])
        if any(fnmatch(file, pattern) for file in changed for pattern in triggers):
            selected.append({"name": meta["name"], "title": meta.get("title", meta["name"]),
                             "blocking": meta.get("blocking", ["high"])})
    print(json.dumps(selected))


if __name__ == "__main__":
    main(sys.argv[1:])
