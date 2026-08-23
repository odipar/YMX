#!/bin/sh
# ymr_sweep.sh - corpus sweep for the .ymr front end: every RhYMe register
# dump replayed on the real player under emulation, each chip write compared
# to an independent model of the .YMR image.
#
#   ymx/test/ymr_sweep.sh song.ymr [more.ymr ...]
#
# Without arguments it sweeps ymr/test/deeper.ymr. One status line per
# tune: OK, ISSUE, PACKFAIL or SKIP; a non-zero exit on any ISSUE.
# YMR_FRAME_CAP raises the walk's frame cap - the only way to reach a long
# tune's wrap.
#
# The work is org.ymx.rig.YmrSweep's; this only finds the repo and the
# classes. Needs rmac on PATH and libunicorn (UNICORN_LIB names it when it
# is somewhere unusual).
set -e
TEST_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$TEST_DIR/../.." && pwd)
[ -d "$REPO/target/test-classes/org/ymx/rig" ] || (cd "$REPO" && mvn -q test-compile)
exec java -ea --enable-native-access=ALL-UNNAMED -Dymx.repo="$REPO" \
    -cp "$REPO/target/classes:$REPO/target/test-classes" \
    org.ymx.rig.YmrSweep "$@"
