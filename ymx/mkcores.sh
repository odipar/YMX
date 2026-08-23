#!/bin/sh
# mkcores.sh - assemble the prebuilt player binaries into dist/.
#
#   ymx/mkcores.sh [-perf] [-nomask] [outdir]
#
# The one build step that needs rmac. It assembles:
#
#   ymxsndh-k1.bin, -k2, -k4    the SNDH cores, one per ST4 unit size
#   ymxprg.bin                  the PRG stub
#
# org.ymx.MkSndh combines a core with packed tunes into an SNDH file, and
# org.ymx.MkPrg wraps that in a runnable program - both without an
# assembler; doc/BINARIES.md is the contract. -perf builds cores with the
# raster monitor in, -nomask cores whose frame write runs unmasked; either
# suffixes the names.
set -e
YMX_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YMX_DIR/.." && pwd)

PERF=0; MASK=1
while [ $# -gt 0 ]; do
    case "$1" in
        -perf)   PERF=1; shift ;;
        -nomask) MASK=0; shift ;;
        *) break ;;
    esac
done
SUFFIX=
[ $PERF -eq 1 ] && SUFFIX="$SUFFIX-perf"
[ $MASK -eq 0 ] && SUFFIX="$SUFFIX-nomask"
true
OUT=${1:-"$REPO/dist"}
mkdir -p "$OUT"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

for UNIT in 1 2 4; do
    cat > "$WORK/core.S" <<INC
ST4_UNIT        equ     $UNIT
YMX_PERF        equ     $PERF
YMX_MASK_BURST  equ     $MASK
        include "YMX_sndh.S"
INC
    (cd "$WORK" && rmac -m68000 -fr +o3 -i"$REPO/68k" \
        -o "$OUT/ymxsndh-k$UNIT$SUFFIX.bin" core.S)
    echo "$OUT/ymxsndh-k$UNIT$SUFFIX.bin: $(wc -c < "$OUT/ymxsndh-k$UNIT$SUFFIX.bin" | tr -d ' ') bytes"
done

if [ -z "$SUFFIX" ]; then
    (cd "$WORK" && rmac -m68000 -fr +o3 -i"$REPO/68k" \
        -o "$OUT/ymxprg.bin" "$REPO/68k/YMX_player.S")
    echo "$OUT/ymxprg.bin: $(wc -c < "$OUT/ymxprg.bin" | tr -d ' ') bytes"
fi
