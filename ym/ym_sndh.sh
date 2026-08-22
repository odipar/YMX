#!/bin/sh
# ym_sndh.sh - from .ym dumps to one SNDH file, in one command.
#
#   ym/ym_sndh.sh [-perf] [-tTitle] [packer flags] output.sndh one.ym [two.ym ...]
#
# Runs the two steps this repo already has: the YMX packer over every input
# with one configuration, then the SNDH builder around the results - the
# tunes become subtunes 1..N, named from their own YM headers. Every packer
# flag passes through (-nN -cC -kK -lF -o -drumhzH; the trim flags too, for a
# single tune). -tTitle names the SNDH; the default is the songs, joined.
#
#   ym/ym_sndh.sh -t"Mad Max" maxset.sndh stormlord3.ym lastv8.ym
#
# The work is org.ym6.YmSndh's; this only finds the repo and the classes.
# Needs rmac on PATH.
set -e
YMX_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YMX_DIR/.." && pwd)
[ -d "$REPO/target/classes" ] || (cd "$REPO" && mvn -q compile)
exec java -ea -Dymx.repo="$REPO" -cp "$REPO/target/classes" org.ym6.YmSndh "$@"
