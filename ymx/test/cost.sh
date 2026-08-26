#!/bin/sh
# The play call's cost, measured: build a -perf program for each tune,
# run it under a cycle-exact Hatari tracing the raster monitor's palette
# writes, and read every call's span back. doc/performance.md carries the
# figures this produced and the method in full.
#
#   ymx/test/cost.sh tune.ymx [more.ymx ...]
#   VBLS=3000 ymx/test/cost.sh tune.ymx     # a longer sample
set -e
CALLER=$(pwd)
cd "$(dirname "$0")"
HATARI=${HATARI:-hatari}
TOS=${TOS:-$HOME/hatari-2.6.1_macos/tos-2.06.rom}
VBLS=${VBLS:-1500}
REPO=$(cd ../.. && pwd)
WORK=.work/cost
mkdir -p "$WORK"
for tune in "$@"; do
    case "$tune" in
        /*) ;;
        *) tune="$CALLER/$tune" ;;
    esac
    name=$(basename "$tune" .ymx)
    "$REPO/ymx/mkprg.sh" -m -perf "$WORK/COST.PRG" "$tune" >/dev/null
    (cd "$WORK" && "$HATARI" --tos "$TOS" --machine st --cpuclock 8 \
        --cpu-exact on --compatible on --memsize 4 --sound off --conout 2 \
        --fast-forward on --disable-video 1 --run-vbls "$VBLS" \
        --log-level fatal --trace video_color --trace-file trace.txt \
        COST.PRG >/dev/null 2>&1)
    printf '%-36s ' "$name"
    python3 "$REPO/ymx/test/cost.py" "$WORK/trace.txt"
done
