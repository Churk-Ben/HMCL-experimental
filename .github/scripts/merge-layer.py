#!/usr/bin/env python3
"""Merge a mandatory layer, replaying only exact, reviewed conflict fragments.

Unlike a whole-file checkout or -Xtheirs, this preserves all nonconflicting
changes. Unknown conflicts abort the merge and fail the composition.
"""

import json
import re
import subprocess
import sys
from pathlib import Path

RESOLUTIONS = Path(__file__).resolve().parent.parent / "experimental/resolutions"
CONFLICT = re.compile(
    r"^<<<<<<< [^\n]*\n(.*?)^=======\n(.*?)^>>>>>>> [^\n]*\n",
    re.MULTILINE | re.DOTALL,
)


def resolve(text, rules, directory=RESOLUTIONS):
    """Resolve every hunk or raise without writing any part of the file."""
    count = 0

    def replace(match):
        nonlocal count
        for rule in rules:
            fragment = directory / rule["fragment"]
            if (match[1] == fragment.with_suffix(".ours").read_text()
                    and match[2] == fragment.with_suffix(".theirs").read_text()):
                count += 1
                return fragment.with_suffix(".resolved").read_text()
        raise ValueError("unreviewed conflict fragment")

    result = CONFLICT.sub(replace, text)
    if count == 0 or re.search(r"^(<<<<<<<|=======|>>>>>>>|\|{7})", result, re.MULTILINE):
        raise ValueError("unrecognized conflict format")
    return result


def git(*args, **kwargs):
    return subprocess.run(["git", *args], check=True, **kwargs)


def main():
    layer, ref = sys.argv[1:]
    if git("status", "--porcelain", capture_output=True).stdout.strip():
        raise SystemExit("Refusing to merge a mandatory layer into a dirty worktree")
    # Force the format understood by the reviewed fragment matcher.
    result = subprocess.run(["git", "-c", "merge.conflictStyle=merge",
                             "merge", "--no-edit", "--no-stat", ref])
    if result.returncode == 0:
        return
    manifest = json.loads((RESOLUTIONS / "manifest.json").read_text())
    try:
        paths = git("diff", "--name-only", "--diff-filter=U", "-z",
                    capture_output=True).stdout.decode().strip("\0").split("\0")
        if not paths or paths == [""]:
            raise ValueError("merge failed without a resolvable content conflict")
        resolved = {}
        for path in paths:
            rules = [r for r in manifest if r["layer"] == layer and r["path"] == path]
            resolved[path] = resolve(Path(path).read_text(), rules)
        for path, content in resolved.items():
            Path(path).write_text(content)
            git("add", "--", path)
            print(f"Replayed reviewed resolution: {layer}: {path}", flush=True)
        git("commit", "--no-edit")
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"::error::Mandatory layer {layer}: {error}. Review the conflict; no layer was skipped.", file=sys.stderr)
        subprocess.run(["git", "merge", "--abort"])
        raise SystemExit(1)


if __name__ == "__main__":
    main()
