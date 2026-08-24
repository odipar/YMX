#!/bin/sh
# mksndh.sh - combine one or more .ymx files into an SNDH container.
#
#   ymx/mksndh.sh [-perf] [-nomask] [-tTitle] [-cComposer] [-Nnamesfile]
#                 [-Pcorefile] output.sndh tune1.ymx [tune2.ymx ...]
#
# -c fills the COMM (composer) tag; -N names the subtunes from a file, one
# per line in tune order, instead of the file stems. -perf uses the core
# with the raster monitor in (68k/YMX.S has the colors), -nomask the one
# whose frame write runs unmasked, and -P a core file instead of resolving
# one from dist/. Flags come first.
#
# The tunes become subtunes 1..N (SNDH '##' tag) and must be packed at one
# unit size; rings and chunks may differ. The result is a raw (unpacked)
# SNDH v2.2 file: position independent, loadable anywhere, playable by any
# SNDH host; ICE 2.4 packing for the archive is optional, since players
# unpack that themselves.
#
# No assembler runs here: org.ymx.MkSndh combines a prebuilt core from
# dist/ with the tunes - doc/BINARIES.md is the contract. rmac is needed
# only the first time, when ymx/mkcores.sh assembles the cores.
set -e
YMX_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YMX_DIR/.." && pwd)

# -dotnet as the first argument runs the C# tree (dotnet/) instead of the
# Java one; both produce the same bytes.
if [ "$1" = "-dotnet" ]; then
    shift
    DLL="$REPO/dotnet/bin/Release/net10.0/ymx.dll"
    (cd "$REPO/dotnet" && dotnet build -c Release -v q)
    YMX_REPO="$REPO" exec dotnet "$DLL" mksndh "$@"
fi
(cd "$REPO" && mvn -q compile)
exec java -ea -Dymx.repo="$REPO" -cp "$REPO/target/classes" org.ymx.MkSndh "$@"
