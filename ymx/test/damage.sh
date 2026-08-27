#!/bin/sh
# damage.sh - a packed tune with its bytes changed one at a time, read back
# by every tree.
#
#   ymx/test/damage.sh [tune.ymx]
#
# A reader that stops the run on one damaged file reports nothing about that
# file, and nothing about any file after it. Four inputs did that, and none
# of the other batteries reached one of them: a negative sample-table offset,
# a sample table near the int ceiling whose entry offset wraps, a loop table
# past the file's end, and a malformed container. Each was one changed byte.
#
# This changes one byte at a time, reads every result back with each tree
# that is built, and compares what they report. A tree whose tools are absent
# is named and left out. Files where a section does not decode are set aside
# and counted: the three ST4 readers word that one reason differently, which
# is text and not behaviour.
#
# The work goes in DAMAGE_WORK, or ymx-damage under the temporary directory.
# DamagedFileTest covers the Java reader alone and runs with the tests.
set -e
TEST_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$TEST_DIR/../.." && pwd)
TUNE=${1:-$REPO/doc/conformance/tunes/plain_packed.ymx}

if [ ! -f "$TUNE" ]; then
    echo "damage.sh: no such file: $TUNE" >&2
    exit 2
fi

TMP=${TMPDIR:-/tmp}
WORK=${DAMAGE_WORK:-${TMP%/}/ymx-damage}
rm -rf "$WORK"
mkdir -p "$WORK/tunes"

COUNT=$(python3 "$TEST_DIR/damage.py" mutants "$WORK/tunes" "$TUNE")
echo "damage.sh: $COUNT copies of $(basename "$TUNE"), one byte changed in each"

PAIRS=""

# read_with NAME FLAG - one tree over every copy. The readers exit non-zero
# where a file leaves the rules, which is what nearly all of them do, so the
# status here says nothing and is not read.
read_with() {
    out=$WORK/$1.txt
    sh "$TEST_DIR/check.sh" $2 "$WORK"/tunes/*.ymx > "$out" 2> "$WORK/$1.err" \
        || true
    PAIRS="$PAIRS $1=$out"
    echo "damage.sh: $1 read them"
}

read_with java ""
if command -v go >/dev/null 2>&1; then
    read_with go -go
else
    echo "damage.sh: go is not on the PATH, so the Go reader is left out"
fi
if command -v dotnet >/dev/null 2>&1; then
    read_with dotnet -dotnet
else
    echo "damage.sh: dotnet is not on the PATH, so the C# reader is left out"
fi

# A reader that ended its run leaves a stack trace behind rather than a
# report, and its report is short by every file after the one that ended it.
for log in "$WORK"/*.err; do
    if grep -q "Exception\|Unhandled\|panic:" "$log" 2>/dev/null; then
        echo "damage.sh: $(basename "$log" .err) ended its run:" >&2
        head -3 "$log" >&2
        exit 1
    fi
done

python3 "$TEST_DIR/damage.py" compare $PAIRS
