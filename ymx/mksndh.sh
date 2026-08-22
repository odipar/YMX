#!/bin/sh
# mksndh.sh - build an SNDH container around one or more .ymx files.
#
#   ymx/mksndh.sh [-perf] [-t"Title"] [-c"Composer"] [-Nnamesfile] out.sndh tunes...
#
# -c fills the COMM (composer) tag; -N names the subtunes from a file, one
# per line in tune order, instead of the file stems. -perf builds the raster
# monitor in (68k/YMX.S has the colors). Flags come first.
#
# The tunes become subtunes 1..N (SNDH '##' tag) and must be packed with one
# configuration - same ring, chunk and unit - which `ymx ... directory/` does
# in one call. The result is a raw (unpacked) SNDH v2.2 file: position
# independent, loadable anywhere, playable by any SNDH host; pack it with
# ICE 2.4 for the archive if you like, players unpack that themselves.
#
# The work is org.ymx.MkSndh's; this only finds the repo and the classes.
# Needs rmac on PATH.
set -e
YMX_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YMX_DIR/.." && pwd)
[ -d "$REPO/target/classes" ] || (cd "$REPO" && mvn -q compile)
exec java -ea -Dymx.repo="$REPO" -cp "$REPO/target/classes" org.ymx.MkSndh "$@"
