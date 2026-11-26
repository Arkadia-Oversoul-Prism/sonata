#!/usr/bin/env python3
"""Fail when a bytecode cache is tracked by git.

A tracked ``__pycache__`` entry is a stale artifact: no reviewer reads it, every
checkout rewrites it, and a stale ``.pyc`` can shadow the source it claims to be
compiled from. It is the same defect class the harness exists to prevent -- the
reviewed artifact must be the artifact in the tree.

Exits 0 when the tree is clean or when this is not a git checkout (an exported
tarball has no index to judge), 1 when a tracked cache path is found.
"""
import subprocess
import sys

CACHE_PREFIXES = ("__pycache__/",)
CACHE_SUFFIXES = (".pyc", ".pyo")


def tracked_cache_paths(paths):
    found = []
    for path in paths:
        if path.endswith(CACHE_SUFFIXES) or any(
            part == "__pycache__" for part in path.split("/")[:-1]
        ):
            found.append(path)
    return found


def tracked_files():
    try:
        out = subprocess.run(
            ["git", "ls-files", "-z"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError):
        return None
    return [p for p in out.split("\0") if p]


def main():
    paths = tracked_files()
    if paths is None:
        print("hygiene: not a git checkout, tracked-cache check skipped")
        return 0
    found = tracked_cache_paths(paths)
    if not found:
        print("hygiene: no tracked bytecode cache")
        return 0
    print("hygiene: FAIL - tracked bytecode cache:")
    for path in found:
        print(f"  {path}")
    print("hygiene: untrack it with `git rm --cached <path>` and keep it ignored")
    return 1


if __name__ == "__main__":
    sys.exit(main())
