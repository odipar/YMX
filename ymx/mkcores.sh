#!/bin/sh
# mkcores.sh - assemble the prebuilt player binaries into dist/.
#
#   ymx/mkcores.sh [-perf] [-nomask] [outdir]
#
# The one build step that runs rmac. It assembles the SNDH cores - one per
# ST4 unit size, suffixed by the flags - and, in a plain run, the PRG stub.
# org.ymx.MkSndh and org.ymx.MkPrg combine the results without an
# assembler; doc/BINARIES.md is the contract. -perf builds cores with the
# raster monitor in, -nomask cores whose frame write runs unmasked.
#
# The work is org.ymx.MkCores's; this only finds the repo and the classes.
# Needs rmac on PATH.
set -e
YMX_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YMX_DIR/.." && pwd)
[ -d "$REPO/target/classes" ] || (cd "$REPO" && mvn -q compile)
exec java -ea -Dymx.repo="$REPO" -cp "$REPO/target/classes" org.ymx.MkCores "$@"
