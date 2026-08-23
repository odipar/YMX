#!/bin/sh
# mkrelease.sh - stage the prebuilt player binaries, and publish them.
#
#   ymx/mkrelease.sh [stagedir]              # assemble and verify only
#   ymx/mkrelease.sh -publish [stagedir]     # and publish a GitHub release
#
# Stages every core variant - three unit sizes by the perf and mask flags -
# plus the PRG stub, verified against the descriptors the combiners read,
# with a MANIFEST.txt of sizes and SHA-256 digests. doc/BINARIES.md is the
# contract another system follows. -publish creates or updates the release
# tagged binaries-v<format version> with gh, replacing its assets.
#
# The work is org.ymx.MkRelease's; this only finds the repo and the classes.
# Needs rmac; -publish needs gh and a pushed HEAD.
set -e
YMX_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YMX_DIR/.." && pwd)

# -dotnet as the first argument runs the C# tree (dotnet/) instead of the
# Java one; both produce the same bytes.
if [ "$1" = "-dotnet" ]; then
    shift
    DLL="$REPO/dotnet/bin/Release/net10.0/ymx.dll"
    [ -f "$DLL" ] || (cd "$REPO/dotnet" && dotnet build -c Release -v q)
    YMX_REPO="$REPO" exec dotnet "$DLL" mkrelease "$@"
fi
[ -d "$REPO/target/classes" ] || (cd "$REPO" && mvn -q compile)
exec java -ea -Dymx.repo="$REPO" -cp "$REPO/target/classes" org.ymx.MkRelease "$@"
