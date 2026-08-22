#!/bin/sh
# ymr.sh - test drive a .YMR tune: pack it, build a player, run it under Hatari.
#
#   ymx/ymr.sh song.ymr                # 960-byte rings, 24 values per call
#   ymx/ymr.sh -n2048 -c32 song.ymr    # longer calls: cheaper on average
#   ymx/ymr.sh -o song.ymr             # play once and stop
#   ymx/ymr.sh -min13 -sec52 song.ymr  # trim: start deep in a long tune
#   ymx/ymr.sh one.ymr two.ymr         # a set: subtunes, number keys pick
#   ymx/ymr.sh -perf song.ymr          # the raster monitor
#   ymx/ymr.sh -nomask song.ymr        # drop the frame write's interrupt mask
#
# ymx/ymr.sh -h lists the lot. Press SPACE in the Hatari window to stop; the
# program asks Hatari to quit on its way out, so the script returns.
#
# The work is org.ymr.YmrPlay's; this only finds the repo and the classes.
# Needs rmac, hatari with a TOS image, and a compiled Java tree.
#
#   HATARI=/path/to/hatari TOS=/path/to/tos.img ymx/ymr.sh song.ymr
set -e
YMX_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YMX_DIR/.." && pwd)
[ -d "$REPO/target/classes" ] || (cd "$REPO" && mvn -q compile)
exec java -ea -Dymx.repo="$REPO" -cp "$REPO/target/classes" org.ymr.YmrPlay "$@"
