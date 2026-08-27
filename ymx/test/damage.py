#!/usr/bin/env python3
"""The two halves of damage.sh that are awkward in a shell.

mutants WORK TUNE
    Writes one copy of TUNE per changed byte into WORK, and prints how many.
    Every header byte is changed, and a spread of four hundred through the
    body; each is changed three times, by $01, $80 and $FF, so a mutant
    reaches a low bit, a high bit and the whole byte.

compare NAME=REPORT ...
    Reads each tree's report, splits it per file and compares. A file whose
    report names a section that does not decode is set aside and counted:
    the three ST4 readers word that one reason differently, which is text
    and not behaviour. Prints the counts and exits non-zero on a difference.
"""

import collections
import os
import re
import sys

HEADER_SIZE = 142               # SPEC.md §1.1, through the section table
DELTA = (0x01, 0x80, 0xFF)
BODY_SPOTS = 400


def spots(length):
    """The bytes to change: every header byte, then a spread of the body."""
    step = max(1, length // BODY_SPOTS)
    return list(range(0, min(HEADER_SIZE, length))) \
        + list(range(HEADER_SIZE, length, step))


def mutants(work, tune):
    with open(tune, "rb") as source:
        original = source.read()
    os.makedirs(work, exist_ok=True)
    written = 0
    for at in spots(len(original)):
        for delta in DELTA:
            damaged = bytearray(original)
            damaged[at] ^= delta
            name = os.path.join(work, "m%05d.ymx" % written)
            with open(name, "wb") as out:
                out.write(bytes(damaged))
            written += 1
    print(written)
    return 0


def reports(path):
    """One tree's report, split per file. A line that opens at the left
    margin names a file; the lines indented under it are its faults."""
    out = collections.OrderedDict()
    current = None
    with open(path, encoding="utf-8") as text:
        for line in text:
            line = line.rstrip("\n")
            if line.startswith("  "):
                if current is not None:
                    out[current].append(line)
            elif line:
                current = os.path.basename(line.split(":")[0])
                out[current] = []
    return out


def decodes(rows):
    """The streams a report says do not decode, which is where the three ST4
    readers word one reason three ways."""
    return {re.search(r"stream (\d+)", row).group(1)
            for row in rows if "does not decode" in row}


def compare(pairs):
    trees = collections.OrderedDict()
    for pair in pairs:
        name, path = pair.split("=", 1)
        trees[name] = reports(path)
    names = list(trees)
    if len(names) < 2:
        print("damage.py: %s alone, so there is nothing to compare against"
              % names[0])
        return 0

    files = list(trees[names[0]])
    for name in names[1:]:
        if list(trees[name]) != files:
            print("damage.py: %s reported %d files and %s reported %d"
                  % (names[0], len(files), name, len(trees[name])))
            return 1

    same = differ = aside = withfaults = 0
    first = None
    for f in files:
        rows = [trees[name][f] for name in names]
        if any("does not decode" in row for tree in rows for row in tree):
            # Set aside, and only where every tree rejects the same streams:
            # a tree that decodes what another rejects is a difference.
            if len({frozenset(decodes(tree)) for tree in rows}) > 1:
                differ += 1
                if first is None:
                    first = (f, rows)
                continue
            aside += 1
            continue
        if rows[0]:
            withfaults += 1
        if all(tree == rows[0] for tree in rows):
            same += 1
        else:
            differ += 1
            if first is None:
                first = (f, rows)

    print("  trees          : " + ", ".join(names))
    print("  files read     : %d" % len(files))
    print("  compared       : %d, of which %d carry faults"
          % (same + differ, withfaults))
    print("  agreed         : %d" % same)
    print("  differed       : %d" % differ)
    print("  set aside      : %d, where a section does not decode" % aside)
    if first is not None:
        report(names, first)
    return 1 if differ else 0


def report(names, first):
    """The first fault the trees word differently, one line per tree. A whole
    report printed instead runs to hundreds of lines, and every line after
    the first carries the same difference."""
    f, rows = first
    deep = max(len(tree) for tree in rows)
    for at in range(deep):
        got = [tree[at].strip() if at < len(tree) else "(no more faults)"
               for tree in rows]
        if any(row != got[0] for row in got):
            print("  first difference: %s, fault %d of %d"
                  % (f, at + 1, deep))
            for name, row in zip(names, got):
                print("    %-8s %s" % (name, row))
            return
    print("  first difference: " + f + ", in the count alone: "
          + ", ".join("%s %d" % (n, len(t)) for n, t in zip(names, rows)))


def main(argv):
    if len(argv) >= 4 and argv[1] == "mutants":
        return mutants(argv[2], argv[3])
    if len(argv) >= 3 and argv[1] == "compare":
        return compare(argv[2:])
    sys.stderr.write(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
