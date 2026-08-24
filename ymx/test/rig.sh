#!/bin/sh
# rig.sh - the whole emulator test battery for the YMX player.
#
#   ymx/test/rig.sh [--quick]
#
# Packs synthetic tunes with the real packer, assembles YMX.S with the
# decoder, runs the player under emulation as a plain 68000, and compares
# every write it makes to the sound chip against what a YM2149 should have
# received. --quick leaves out the four-thousand-frame shapes. YMX_NOMASK=1
# runs the battery against the unmasked-frame-write build, the tools'
# -nomask.
#
# The work is org.ymx.rig.PlayerTests's; this only finds the repo and the
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
    (cd "$REPO/dotnet" && dotnet build -c Release -v q)
    YMX_REPO="$REPO" exec dotnet "$DLL" rig "$@"
fi
(cd "$REPO" && mvn -q test-compile)
exec java -ea --enable-native-access=ALL-UNNAMED -Dymx.repo="$REPO" \
    -cp "$REPO/target/classes:$REPO/target/test-classes" \
    org.ymx.rig.PlayerTests "$@"
