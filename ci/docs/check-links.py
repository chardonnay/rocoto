#!/usr/bin/env python3
"""Checks that every relative Markdown link in the repository points to an existing file."""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
LINK = re.compile(r"(?<!\!)\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
SKIP_DIRS = {".git", "target", "node_modules", ".claude"}

errors = []
checked = 0
for base, dirs, files in os.walk(ROOT):
    dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
    for name in files:
        if not name.endswith(".md"):
            continue
        path = os.path.join(base, name)
        in_code = False
        with open(path, encoding="utf-8") as handle:
            for number, line in enumerate(handle, 1):
                if line.lstrip().startswith("```"):
                    in_code = not in_code
                    continue
                if in_code:
                    continue
                for target in LINK.findall(line):
                    if re.match(r"^[a-z][a-z0-9+.-]*:", target) or target.startswith("#"):
                        continue
                    checked += 1
                    target_path = target.split("#", 1)[0]
                    resolved = os.path.normpath(os.path.join(base, target_path))
                    if not os.path.exists(resolved):
                        errors.append(f"{os.path.relpath(path, ROOT)}:{number}: broken link {target}")

for error in errors:
    print(error)
print(f"{checked} relative link(s) checked, {len(errors)} broken")
sys.exit(1 if errors else 0)
