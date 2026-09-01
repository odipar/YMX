#!/bin/sh
# parity-one.sh - one tune of the collection, packed by all three trees and
# compared. parity.sh stages the tunes, runs many of these at once and reads
# the verdicts; nothing here prints, so the runs do not interleave.
#
#   parity-one.sh N
#
# N names the staged tune. WORK, REPO, DLL, TUNE_A and TUNE_B come from the
# environment parity.sh exports. The verdict is $WORK/verdict/N: empty where
# the trees agree, and what differs where they do not.
REPO=${REPO:-$(cd "$(dirname "$0")/.." && pwd)}
. "$REPO/ymx/parity-lib.sh"

n=$1
SETUP="cp '$WORK/staged/$n.ym' t.ym"
if compare_trees "corpus-$n" ymx t.ym out.ymx > "$WORK/verdict/$n" 2>&1; then
    # A case that matched leaves nothing to read, and the collection fills a
    # disk if every one of them stays.
    rm -rf "$WORK/corpus-$n"
    : > "$WORK/verdict/$n"
fi
# Always well, so one differing tune does not stop the rest.
exit 0
