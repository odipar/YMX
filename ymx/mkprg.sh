#!/bin/sh
# mkprg.sh - a runnable TOS program around one or more packed tunes.
#
#   ymx/mkprg.sh [-m] [-perf] [-nomask] [-tTitle] [-cComposer] [-Nnamesfile] output.prg tunes...
#   ymx/mkprg.sh [-m] tune.ymx output.prg        # the old order still works
#
# -t, -c and -N flow into the embedded SNDH's tags (mksndh.sh has the
# details); without them the title is the output's stem.
#
# The tunes are built into an SNDH container first (ymx/mksndh.sh - the
# canonical form of the player) and the PRG is a thin shell around those
# same bytes: takeover, one play call per VBL, SPACE to quit, number keys
# to switch subtunes. -m makes the program drop YMXDONE.MRK as it exits,
# which is how ym/play.sh knows to close the emulator.
#
# The work is org.ymx.MkPrg's; this only finds the repo and the classes.
# Needs rmac on PATH.
set -e
YMX_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YMX_DIR/.." && pwd)
[ -d "$REPO/target/classes" ] || (cd "$REPO" && mvn -q compile)
exec java -ea -Dymx.repo="$REPO" -cp "$REPO/target/classes" org.ymx.MkPrg "$@"
