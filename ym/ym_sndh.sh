#!/bin/sh
# ym_sndh.sh - from .ym dumps to one SNDH file, in one command.
#
#   ym/ym_sndh.sh [-perf] [-tTitle] [packer flags] output.sndh one.ym [two.ym ...]
#
# Runs the two steps this repo already has: the YMX packer over every input
# with one configuration, then the SNDH builder around the results - the
# tunes become subtunes 1..N, named from their own YM headers. Every packer
# flag passes through (-nN -cC -kK -o -drumhzH; the trim flags too, for a
# single tune). -tTitle names the SNDH; the default is the songs, joined.
#
#   ym/ym_sndh.sh -t"Mad Max" maxset.sndh stormlord3.ym lastv8.ym
#
# The work is org.ym6.YmSndh's; this only finds the repo and the classes.
# rmac is needed only the first time, when ymx/mkcores.sh assembles the
# prebuilt player binaries.
set -e
YMX_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YMX_DIR/.." && pwd)

# -dotnet as the first argument runs the C# tree (dotnet/) instead of the
# Java one, and -go the Go tree (go/); all three produce the same bytes.
# The Go one is what a release ships, and needs no build step.
if [ "$1" = "-go" ]; then
    shift
    # Built rather than "go run": the tool has to see the paths the
    # caller typed, and go run would hand it the go tree's directory.
    (cd "$REPO/go" && go build -o "$REPO/go/bin/ymsndh" ./cmd/ymsndh)
    YMX_REPO="$REPO" exec "$REPO/go/bin/ymsndh" "$@"
fi
if [ "$1" = "-dotnet" ]; then
    shift
    DLL="$REPO/dotnet/bin/Release/net10.0/ymx.dll"
    (cd "$REPO/dotnet" && dotnet build -c Release -v q)
    YMX_REPO="$REPO" exec dotnet "$DLL" ymsndh "$@"
fi
(cd "$REPO" && mvn -q compile)
exec java -ea -Dymx.repo="$REPO" -cp "$REPO/target/classes" org.ym6.YmSndh "$@"
