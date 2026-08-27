#!/bin/sh
# check.sh - a packed tune against SPEC.md §9.3, the rules a player does
# not check.
#
#   ymx/test/check.sh tune.ymx [more.ymx ...]
#
# One line per file - "within §9.3", or a count and one line per place the
# file leaves them, each naming the frame and the rule. A non-zero exit
# where any file reports one. A player reads such a file, drives the chip
# from it and reports nothing (§9.1), so this is what a writer other than
# the packer reads its output back with.
#
# -go or -dotnet as the first argument reads the file with the Go tree
# (go/cmd/ymxcheck) or the C# one (dotnet, tool name check) instead of the
# Java one; all three read the same rules off the same streams and report
# the same faults.
#
# The work is org.ymx.rig.Check's; this only finds the repo and the
# classes. Needs neither rmac nor libunicorn.
set -e
TEST_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$TEST_DIR/../.." && pwd)

if [ "$1" = "-dotnet" ]; then
    shift
    DLL="$REPO/dotnet/bin/Release/net10.0/ymx.dll"
    # The build's own lines go to stderr: this script's stdout is the
    # report, which a caller compares against another tree's.
    (cd "$REPO/dotnet" && dotnet build -c Release -v q >&2)
    YMX_REPO="$REPO" exec dotnet "$DLL" check "$@"
fi

if [ "$1" = "-go" ]; then
    shift
    (cd "$REPO/go" && go build -o bin/ymxcheck ./cmd/ymxcheck)
    exec "$REPO/go/bin/ymxcheck" "$@"
fi

(cd "$REPO" && mvn -q test-compile)
exec java -ea -Dymx.repo="$REPO" \
    -cp "$REPO/target/classes:$REPO/target/test-classes" \
    org.ymx.rig.Check "$@"
