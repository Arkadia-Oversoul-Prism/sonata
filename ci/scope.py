#!/usr/bin/env python3
"""Compute the JVM-verifiable scope of the Sonata integration tree.

Prints one line per source:
    MAIN <path>   compile + run
    TEST <path>   compile + run
    OUT  <path>   present in the tree but not compiled here

A source is OUT when it imports android.*, org.robolectric.*, or any androidx
package other than the two pure-JVM annotation artifacts (androidx.room.*,
androidx.annotation.*). The set is then closed transitively over class
references, so scope is derived from the code rather than hand-maintained.

Deterministic: same tree in, same list out. No network, no state.
"""
import os
import re
import sys

ROOT = "app/src"
ALLOWED_ANDROIDX = ("androidx.room.", "androidx.annotation.")

# Classes excluded deliberately on top of the import rule, each with a reason.
# TtsEngine imports only android.util.Log, which the Gradle unit-test config
# stubs (isReturnDefaultValues = true). It is still out of scope here because
# its production factory constructs AndroidOfflineVoice, which needs the real
# AudioTrack / android.speech.tts APIs — a Log shim would not make it runnable.
FORCED_OUT = {
    "app/src/main/kotlin/com/arkadia/sonata/TtsEngine.kt",
}


def imports_of(path):
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("import "):
                yield line[7:].strip().split(" ")[0]


def android_bound(path):
    for target in imports_of(path):
        if target.startswith("android."):
            return True
        if target.startswith("org.robolectric."):
            return True
        if target.startswith("androidx.") and not target.startswith(ALLOWED_ANDROIDX):
            return True
    return False


def collect(sub):
    base = os.path.join(ROOT, sub, "kotlin")
    found = []
    for dirpath, _, files in os.walk(base):
        for name in files:
            if name.endswith(".kt"):
                found.append(os.path.join(dirpath, name))
    return sorted(found)


def cls(path):
    return os.path.basename(path)[:-3]


DECL_RE = re.compile(
    r"^(?:@\w+\s+)*(?:public |internal |private |open |abstract |sealed |data |enum |annotation )*"
    r"(?:class|interface|object|enum class|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)


def declared_names(path):
    """Top-level type names a file declares (plus its filename stem)."""
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    names = set(DECL_RE.findall(text))
    names.add(cls(path))
    return names


def references(path, names):
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    hits = set()
    for name in names:
        pattern = r"(?<![A-Za-z0-9_])" + re.escape(name) + r"(?![A-Za-z0-9_])"
        if re.search(pattern, text):
            hits.add(name)
    return hits


def close_over_references(candidates, excluded):
    """Exclude any candidate that names a class declared by an excluded file."""
    excluded = set(excluded)
    changed = True
    while changed:
        changed = False
        names = set()
        for path in excluded:
            names |= declared_names(path)
        for path in candidates:
            if path in excluded:
                continue
            if references(path, names):
                excluded.add(path)
                changed = True
    return excluded


def main():
    main_src = collect("main")
    test_src = collect("test")

    excluded_main = {p for p in main_src if android_bound(p)} | (
        FORCED_OUT & set(main_src)
    )
    excluded_main = close_over_references(main_src, excluded_main)

    # Tests are out when android-bound, when they name an excluded main class,
    # or when they name another excluded test (e.g. a shared Fakes helper).
    main_names = set()
    for path in excluded_main:
        main_names |= declared_names(path)
    excluded_test = {p for p in test_src if android_bound(p) or references(p, main_names)}
    excluded_test = close_over_references(test_src, excluded_test)

    for path in main_src:
        print(("MAIN " if path not in excluded_main else "OUT  ") + path)
    for path in test_src:
        print(("TEST " if path not in excluded_test else "OUT  ") + path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
