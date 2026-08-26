#!/bin/sh
# Hear a YM tune: build a program from it with ym-to-ymx, then play it under
# Hatari. macOS and Linux; ymxplay.cmd is the same for Windows.
#
#   ./ymxplay.sh tune.ym [more.ym ...]     # SPACE or ESC in the window stops
#   ./ymxplay.sh -perf tune.ym             # any ym-to-ymx option passes through
#
# HATARI names the emulator and TOS its ROM image. Set them once in your
# shell profile and this takes no configuration:
#
#   export HATARI=/path/to/hatari
#   export TOS=/path/to/tos.img
#
# Every argument goes to ym-to-ymx, so HATARI_OPTS is where the emulator's
# own options go:
#
#   HATARI_OPTS='--fast-forward on' ./ymxplay.sh tune.ym
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
TOOL=${YM_TO_YMX:-$HERE/ym-to-ymx}
HATARI=${HATARI:-hatari}

if [ $# -eq 0 ]; then
    echo "usage: ymxplay.sh [ym-to-ymx options] tune.ym [more.ym ...]" >&2
    echo "  HATARI= names the emulator, TOS= its ROM image." >&2
    exit 1
fi
if [ ! -x "$TOOL" ]; then
    echo "ymxplay: no ym-to-ymx beside this script. YM_TO_YMX= names one." >&2
    exit 1
fi
if ! command -v "$HATARI" >/dev/null 2>&1 && [ ! -x "$HATARI" ]; then
    echo "ymxplay: no hatari at '$HATARI'. HATARI= names the emulator." >&2
    exit 1
fi

WORK=${TMPDIR:-/tmp}/ymxplay.$$
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT
"$TOOL" -f "$WORK/play.prg" "$@"

set -- --machine st --cpuclock 8 --compatible on --memsize 4
[ -n "$TOS" ] && set -- --tos "$TOS" "$@"
# HATARI_OPTS is word-split on purpose: it carries the emulator's flags
exec "$HATARI" "$@" $HATARI_OPTS "$WORK/play.prg"
