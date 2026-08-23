#!/bin/sh
# mkprg.sh - a runnable TOS program around one or more packed tunes.
#
#   ymx/mkprg.sh [-m] [-perf] [-nomask] [-tTitle] [-cComposer] [-Nnamesfile] output.prg tunes...
#   ymx/mkprg.sh [-m] output.prg set.sndh        # around a ready SNDH file
#   ymx/mkprg.sh [-m] tune.ymx output.prg        # the old order still works
#
# -t, -c and -N flow into the embedded SNDH's tags (mksndh.sh has the
# details); without them the title is the output's stem.
#
# The tunes are combined into an SNDH container first (ymx/mksndh.sh - the
# canonical form of the player) and the PRG is a thin stub in front of
# those same bytes: takeover, one play call per VBL, SPACE to quit, number
# keys to switch subtunes. -m makes the program drop YMXDONE.MRK as it
# exits, which is how ym/play.sh closes the emulator by itself.
#
# No assembler runs here: org.ymx.MkPrg prepends the prebuilt stub from
# dist/ - doc/BINARIES.md is the contract. rmac is needed only the first
# time, when ymx/mkcores.sh assembles the binaries.
set -e
YMX_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YMX_DIR/.." && pwd)

# -dotnet as the first argument runs the C# tree (dotnet/) instead of the
# Java one; both produce the same bytes.
if [ "$1" = "-dotnet" ]; then
    shift
    DLL="$REPO/dotnet/bin/Release/net10.0/ymx.dll"
    [ -f "$DLL" ] || (cd "$REPO/dotnet" && dotnet build -c Release -v q)
    YMX_REPO="$REPO" exec dotnet "$DLL" mkprg "$@"
fi
[ -d "$REPO/target/classes" ] || (cd "$REPO" && mvn -q compile)
exec java -ea -Dymx.repo="$REPO" -cp "$REPO/target/classes" org.ymx.MkPrg "$@"
