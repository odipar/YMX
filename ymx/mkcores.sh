#!/bin/sh
# mkcores.sh - assemble the prebuilt player binaries into dist/.
#
#   ymx/mkcores.sh [-perf] [-nomask] [-copies [-nN]] [outdir]
#
# The build step that runs rmac. It assembles the SNDH cores - one per
# ST4 unit size, suffixed by the flags - and, in a plain run, the PRG stub.
# org.ymx.MkSndh and org.ymx.MkPrg combine the results without an
# assembler; doc/BINARIES.md is the contract. -perf builds cores with the
# raster monitor in, -nomask cores whose frame write runs unmasked, and
# -copies cores built for the default ring as a window, which decode copies
# from the literal stream; -copies -nN builds them for a ring of N bytes.
#
# The work is org.ymx.MkCores's; this only finds the repo and the classes.
# Needs rmac on PATH.
set -e
YMX_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YMX_DIR/.." && pwd)

# -dotnet as the first argument runs the C# tree (dotnet/) instead of the
# Java one; both produce the same bytes.
if [ "$1" = "-dotnet" ]; then
    shift
    DLL="$REPO/dotnet/bin/Release/net10.0/ymx.dll"
    (cd "$REPO/dotnet" && dotnet build -c Release -v q)
    YMX_REPO="$REPO" exec dotnet "$DLL" mkcores "$@"
fi
(cd "$REPO" && mvn -q compile)
exec java -ea -Dymx.repo="$REPO" -cp "$REPO/target/classes" org.ymx.MkCores "$@"
