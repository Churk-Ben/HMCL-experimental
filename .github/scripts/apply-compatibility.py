#!/usr/bin/env python3
"""Apply reviewed integration fixes; drift is an error, never a silent skip."""

import subprocess
import sys
from pathlib import Path

PATCHES = Path(__file__).resolve().parent.parent / "experimental/compatibility"


def apply(name):
    patch = str(PATCHES / name)
    if subprocess.run(["git", "apply", "--reverse", "--check", patch],
                      capture_output=True).returncode == 0:
        print(f"Already applied: {name}")
        return
    subprocess.run(["git", "apply", "--check", patch], check=True)
    subprocess.run(["git", "apply", "--index", patch], check=True)
    print(f"Applied compatibility fix: {name}")


def main():
    apply("experimental-update-source.patch")
    if Path("HMCL/src/main/java/org/jackhuang/hmcl/ui/main/LanguageSettingsPage.java").exists():
        apply("language-javafx17.patch")
    if Path("HMCLCore/src/test/java/org/jackhuang/hmcl/task/FetchTaskTest.java").exists():
        apply("fetch-partial-response.patch")
    # Each retry starts from the mandatory-layer commit, so retain the fixes in
    # the composition history instead of leaving an uncommitted build overlay.
    if subprocess.run(["git", "diff", "--cached", "--quiet"]).returncode:
        subprocess.run(["git", "commit", "-m", "fix: apply reviewed experimental compatibility fixes"], check=True)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError:
        print("::error::Compatibility patch drifted; review and refresh the patch.", file=sys.stderr)
        raise SystemExit(1)
