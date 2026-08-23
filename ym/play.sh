#!/bin/sh
# play.sh - test drive a YM tune: pack it, build a player, run it under Hatari.
#
#   ym/play.sh song.ym                  # 960-byte rings, 24 values per call
#   ym/play.sh -n2048 -c32 song.ym      # longer calls: cheaper on average
#   ym/play.sh -o song.ym               # play once and stop
#   ym/play.sh -min13 -sec52 song.ym    # trim: start deep in a long tune
#   ym/play.sh one.ym two.ym            # a set: subtunes, number keys pick
#   ym/play.sh -perf song.ym            # the raster monitor
#   ym/play.sh -nomask song.ym          # drop the frame write's interrupt mask
#
# ym/play.sh -h lists the lot. Press SPACE in the Hatari window to stop; the
# program asks Hatari to quit on its way out, so the script returns.
#
# The work is org.ym6.Play's; this only finds the repo and the classes.
# Needs rmac, hatari with a TOS image, and a compiled Java tree.
#
#   HATARI=/path/to/hatari TOS=/path/to/tos.img ym/play.sh song.ym
set -e
YMX_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YMX_DIR/.." && pwd)

# -dotnet as the first argument runs the C# tree (dotnet/) instead of the
# Java one; both produce the same bytes.
if [ "$1" = "-dotnet" ]; then
    shift
    DLL="$REPO/dotnet/bin/Release/net10.0/ymx.dll"
    [ -f "$DLL" ] || (cd "$REPO/dotnet" && dotnet build -c Release -v q)
    YMX_REPO="$REPO" exec dotnet "$DLL" play "$@"
fi
[ -d "$REPO/target/classes" ] || (cd "$REPO" && mvn -q compile)
exec java -ea -Dymx.repo="$REPO" -cp "$REPO/target/classes" org.ym6.Play "$@"
