#!/bin/sh
# Build and run the YMX real-hardware validation harness under Hatari.
#
#   ./run.sh                      # uses the defaults below
#   HATARI=... TOS=... ./run.sh   # or point it at your own install
#
# Needs: rmac (assembler), and hatari (emulator) with a TOS or EmuTOS image;
# the Java tree compiles itself on the first run.
set -e
cd "$(dirname "$0")"

HATARI=${HATARI:-hatari}
TOS=${TOS:-$HOME/hatari-2.6.1_macos/tos-2.06.rom}

REPO=$(cd ../.. && pwd)
[ -d "$REPO/target/test-classes/org/ymx/rig" ] || (cd "$REPO" && mvn -q test-compile)
java -ea -Dymx.repo="$REPO" \
    -cp "$REPO/target/classes:$REPO/target/test-classes" org.ymx.rig.GenData
# -i. finds the generated ymxdata.inc; -i../../68k the player and the ST4
# decoders, and -i../../68k/test hatari_util.inc - the console and 200 Hz
# timing helpers, shared with the ST1 harnesses rather than copied.
rmac -m68000 -p +o3 -i. -i../../68k -i../../68k/test \
     -o YMXTEST.PRG ../../68k/test/YMX_test.S

# A TOS program cannot fail the shell, so its output is the verdict: it must
# reach DONE, and no line may report BAD.
out=$("$HATARI" --tos "$TOS" --machine st --cpuclock 8 --cpu-exact on \
    --compatible on --memsize 4 --sound off --conout 2 --fast-forward on \
    --disable-video 1 --run-vbls 6000 --log-level fatal YMXTEST.PRG 2>/dev/null)
printf '%s\n' "$out"

fail=0
case "$out" in
    *BAD*)  echo "FAILED: YMXTEST.PRG reported BAD"     >&2; fail=1 ;;
esac
case "$out" in
    *DONE*) ;;
    *)      echo "FAILED: YMXTEST.PRG never reached DONE" >&2; fail=1 ;;
esac
exit $fail
