#!/usr/bin/env python3
"""Print true if a Git diff touches an app file, false for CI/docs/Markdown only.

Renames are deliberately disabled so Git reports both the old and new paths;
deletes remain visible. NUL-delimited paths preserve unusual filenames. Any Git
error is fatal so callers fail closed rather than interpreting an unknown diff
as docs-only.
"""
from __future__ import annotations

import subprocess
import sys


def is_app_path(path: bytes) -> bool:
    # Match the workflow's established exclusion policy using raw path bytes.
    return not (
        path.startswith(b".github/")
        or path.startswith(b"docs/")
        or path.endswith(b".md")
    )


def git_paths(args: list[str]) -> list[bytes]:
    result = subprocess.run(
        ["git", *args], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    return [path for path in result.stdout.split(b"\0") if path]


def main(argv: list[str]) -> int:
    if len(argv) == 3 and argv[1] == "--commit":
        paths = git_paths(
            ["diff-tree", "--root", "--no-commit-id", "--no-renames", "--name-only", "-r", "-z", argv[2]]
        )
    elif len(argv) == 3:
        paths = git_paths(
            ["diff", "--no-renames", "--name-only", "-z", argv[1], argv[2]]
        )
    else:
        print(f"Usage: {argv[0]} BASE TIP | --commit COMMIT", file=sys.stderr)
        return 2
    print("true" if any(is_app_path(path) for path in paths) else "false")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv))
    except subprocess.CalledProcessError as exc:
        detail = exc.stderr.decode("utf-8", errors="replace").strip()
        print(f"Cannot classify Git changes (fail closed): {detail}", file=sys.stderr)
        raise SystemExit(exc.returncode or 1)
