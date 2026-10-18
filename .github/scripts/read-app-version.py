#!/usr/bin/env python3
"""Read a literal versionName or a releaseVersion override's literal fallback."""
import re
import sys
from pathlib import Path


def read_version(source):
    assignments = re.findall(r'^\s*versionName\s*=\s*(.+)$', source, re.MULTILINE)
    if len(assignments) != 1:
        raise ValueError('Expected exactly one versionName assignment')
    expression = assignments[0].split('//', 1)[0].strip()
    match = re.fullmatch(r'"([^"]+)"', expression)
    if match is None:
        match = re.search(r'\?:\s*"([^"]+)"\s*$', expression)
    if match is None or not re.fullmatch(r'\d+\.\d+\.\d+(?:[.-][0-9A-Za-z]+)*', match.group(1)):
        raise ValueError('Cannot establish a valid literal versionName base; refusing build')
    return match.group(1)


def main():
    arg = sys.argv[1] if len(sys.argv) > 1 else 'app/build.gradle.kts'
    if arg == '--self-test':
        assert read_version('versionName = "3.0.0"') == '3.0.0'
        assert read_version('versionName = (project.findProperty("releaseVersion") as String?) ?: "3.0.0"') == '3.0.0'
        for invalid in ('versionName = computedVersion', 'versionName = ""', 'versionName = "bad"', ''):
            try:
                read_version(invalid)
            except ValueError:
                pass
            else:
                raise AssertionError('Invalid version unexpectedly accepted')
        assert read_version(Path('app/build.gradle.kts').read_text()) == '3.0.0'
        print('Version parser self-test passed (literal, override fallback, invalid inputs, real checkout).')
        return
    source = sys.stdin.read() if arg == '-' else Path(arg).read_text()
    print(read_version(source))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError) as exc:
        sys.exit(str(exc))
