#!/bin/sh
# play.sh - test drive a YM tune: pack it, build a player, run it under Hatari.
#
#   ymx/play.sh song.ym                  # 960-byte rings, 24 values per call
#   ymx/play.sh -n2048 -c32 song.ym      # longer calls: cheaper on average
#   ymx/play.sh -o song.ym               # play once and stop
#   ymx/play.sh -min13 -sec52 song.ym    # trim: start deep in a long tune
#   ymx/play.sh one.ym two.ym            # a set: subtunes, number keys pick
#   ymx/play.sh -perf song.ym            # the raster monitor
#   ymx/play.sh -nomask song.ym          # drop the frame write's interrupt mask
#
# ymx/play.sh -h lists the lot. Press SPACE in the Hatari window to stop; the
# program asks Hatari to quit on its way out, so the script returns.
#
# The work is org.ym6.Play's; this only finds the repo and the classes.
# Needs rmac, hatari with a TOS image, and a compiled Java tree.
#
#   HATARI=/path/to/hatari TOS=/path/to/tos.img ymx/play.sh song.ym
set -e
YMX_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YMX_DIR/.." && pwd)
[ -d "$REPO/target/classes" ] || (cd "$REPO" && mvn -q compile)
exec java -ea -Dymx.repo="$REPO" -cp "$REPO/target/classes" org.ym6.Play "$@"
