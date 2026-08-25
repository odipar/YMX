#!/bin/sh
# The tick reference against a real MFP, under Hatari.
#
#   ./ticks.sh                      # every conformance tune that has ticks
#   ./ticks.sh retrigger ring_form  # or the ones named
#   HATARI=... TOS=... ./ticks.sh   # or point it at your own install
#
# TickDump models the timers, because the unit-test emulator raises no
# interrupt. Hatari emulates the MFP, so this builds a program for each
# tune, traces every sound-chip write it makes, and compares the two, one
# register at a time. Needs hatari with a TOS image; the Java tree compiles
# itself on the first run.
set -e
cd "$(dirname "$0")"

HATARI=${HATARI:-hatari}
TOS=${TOS:-$HOME/hatari-2.6.1_macos/tos-2.06.rom}
VBLS=${VBLS:-2500}

REPO=$(cd ../.. && pwd)
KIT="$REPO/doc/conformance"
WORK=.work/ticks
mkdir -p "$WORK"
(cd "$REPO" && mvn -q test-compile)
CP="$REPO/target/classes:$REPO/target/test-classes"

# Three of the ten tunes carry no timer at all, so there is nothing to trace.
tunes=${*:-"plain_packed unit1 unit4 ring_form retrigger resume_model wide_ring"}

for tune in $tunes; do
    ymx="$KIT/tunes/$tune.ymx"
    [ -f "$ymx" ] || { echo "$tune: no such tune in the kit" >&2; exit 1; }
    calls=$(awk -v t="$tune" '$1==t {print $2}' "$KIT/MANIFEST-ticks.txt")
    unit=$(awk  -v t="$tune" '$1==t {print $3}' "$KIT/MANIFEST-ticks.txt")
    "$REPO/ymx/mkprg.sh" -m "$WORK/$tune.PRG" "$ymx" >/dev/null

    (cd "$WORK" && "$HATARI" --tos "$TOS" --machine st --cpuclock 8 \
        --cpu-exact on --compatible on --memsize 4 --sound off --conout 2 \
        --fast-forward on --disable-video 1 --run-vbls "$VBLS" \
        --log-level fatal --trace psg_write --trace-file "$tune.trace" \
        "$tune.PRG" >/dev/null 2>&1)

    java -ea --enable-native-access=ALL-UNNAMED -Dymx.repo="$REPO" -cp "$CP" org.ymx.rig.TickDump \
        "$ymx" "$calls" "$unit" > "$WORK/$tune.json"
    echo "$tune"
    java -ea --enable-native-access=ALL-UNNAMED -Dymx.repo="$REPO" -cp "$CP" org.ymx.rig.TickHatari \
        "$WORK/$tune.trace" "$WORK/$tune.json" | sed -n 's/^  R/    R/p'
done
