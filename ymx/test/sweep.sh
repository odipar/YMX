#!/bin/sh
# sweep.sh - corpus sweep: every .ym tune replayed on the real player under
# emulation, each chip write compared to the YM truth.
#
#   ymx/test/sweep.sh song.ym [more.ym ...]
#
# One status line per tune: OK, ISSUE, PACKFAIL or SKIP; a non-zero exit on
# any ISSUE. YMX_PACK_OPTIONS adds packer options for a shape the corpus
# never asks for.
#
# The work is org.ymx.rig.Sweep's; this only finds the repo and the
# classes. Needs rmac on PATH and libunicorn (UNICORN_LIB names it when it
# is somewhere unusual).
set -e
TEST_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$TEST_DIR/../.." && pwd)

# -dotnet as the first argument runs the C# tree (dotnet/) instead of the
# Java one; both produce the same bytes.
if [ "$1" = "-dotnet" ]; then
    shift
    DLL="$REPO/dotnet/bin/Release/net10.0/ymx.dll"
    [ -f "$DLL" ] || (cd "$REPO/dotnet" && dotnet build -c Release -v q)
    YMX_REPO="$REPO" exec dotnet "$DLL" sweep "$@"
fi
[ -d "$REPO/target/test-classes/org/ymx/rig" ] || (cd "$REPO" && mvn -q test-compile)
exec java -ea --enable-native-access=ALL-UNNAMED -Dymx.repo="$REPO" \
    -cp "$REPO/target/classes:$REPO/target/test-classes" \
    org.ymx.rig.Sweep "$@"
