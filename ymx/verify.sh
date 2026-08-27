#!/bin/sh
# verify.sh - every check this repository carries, in one run, naming what it
# could not run and why.
#
#   ymx/verify.sh          # the checks that need no corpus and no emulator
#   ymx/verify.sh -full    # adds the corpus sweep and the emulator batteries
#
# The checks are spread over a Maven build, three trees, a §9.3 reader, an
# emulator rig, a corpus sweep and two Hatari harnesses. Run one of them and
# the question is answered for one of them. This runs each in turn and prints
# one table: PASS, FAIL, or SKIP naming the tool or the directory that is
# absent. A step that did not run is never a pass, and the exit status is
# non-zero where any step failed.
#
# Each step writes its own log; the table names the log of every step that
# failed. VERIFY_LOGS names the directory they go in.
#
#   HATARI=... TOS=... YM_CORPUS=... ymx/verify.sh -full

REPO=$(cd "$(dirname "$0")/.." && pwd)
TMP=${TMPDIR:-/tmp}
LOGS=${VERIFY_LOGS:-${TMP%/}/ymx-verify}

FULL=0
case ${1:-} in
    -full) FULL=1 ;;
    "") ;;
    *) echo "usage: verify.sh [-full]" >&2; exit 2 ;;
esac

rm -rf "$LOGS"
mkdir -p "$LOGS"
SUMMARY=$LOGS/summary
: > "$SUMMARY"

have() {
    command -v "$1" >/dev/null 2>&1
}

# step NAME SLUG SKIP COMMAND - runs COMMAND through a shell, so a step may
# name a pipeline or a glob. A non-empty SKIP names what is absent and the
# step is not run.
step() {
    name=$1
    log=$LOGS/$2.log
    skip=$3
    command=$4
    if [ -n "$skip" ]; then
        printf 'SKIP  %-30s %s\n' "$name" "$skip"
        printf 'SKIP\t%s\t%s\n' "$name" "$skip" >> "$SUMMARY"
        return
    fi
    # The running step is named before it runs, so a long one says which it
    # is. On a terminal the line is overwritten by its result; redirected,
    # printing it twice would put the name in the file twice.
    [ -t 1 ] && printf '      %-30s' "$name"
    if sh -c "$command" > "$log" 2>&1; then
        [ -t 1 ] && printf '\r'
        printf 'PASS  %-30s\n' "$name"
        printf 'PASS\t%s\t\n' "$name" >> "$SUMMARY"
    else
        [ -t 1 ] && printf '\r'
        printf 'FAIL  %-30s %s\n' "$name" "$log"
        printf 'FAIL\t%s\t%s\n' "$name" "$log" >> "$SUMMARY"
    fi
}

# What each step needs, looked for once.
GO_ABSENT=
have go || GO_ABSENT="go is not on the PATH"
DOTNET_ABSENT=
have dotnet || DOTNET_ABSENT="dotnet is not on the PATH"
RMAC_ABSENT=
have rmac || RMAC_ABSENT="rmac is not on the PATH"

CORPUS_ABSENT=
if [ -z "${YM_CORPUS:-}" ]; then
    CORPUS_ABSENT="YM_CORPUS names no directory"
elif [ ! -d "$YM_CORPUS" ]; then
    CORPUS_ABSENT="YM_CORPUS is not a directory: $YM_CORPUS"
fi

HATARI=${HATARI:-hatari}
TOS=${TOS:-$HOME/hatari-2.6.1_macos/tos-2.06.rom}
EMULATOR_ABSENT=
if ! have "$HATARI"; then
    EMULATOR_ABSENT="hatari is not on the PATH"
elif [ ! -f "$TOS" ]; then
    EMULATOR_ABSENT="no TOS image at $TOS"
fi

# The rig and the sweep assemble the player and run it under libunicorn, and
# each looks for the library itself over a list a shell does not carry. They
# report an absent one in their own words rather than being skipped here.
TUNES="$REPO/doc/conformance/tunes"

echo "verify.sh: logs in $LOGS"
echo

step "the Java tree" java "" \
    "cd '$REPO' && mvn -q test"
step "the Go tree" go "$GO_ABSENT" \
    "cd '$REPO/go' && go build ./... && go vet ./... && go test ./..."
step "the C# tree" dotnet "$DOTNET_ABSENT" \
    "cd '$REPO/dotnet' && dotnet build -c Release -v q"
step "9.3, the Java reader" check-java "" \
    "'$REPO/ymx/test/check.sh' '$TUNES'/*.ymx"
step "9.3, the Go reader" check-go "$GO_ABSENT" \
    "'$REPO/ymx/test/check.sh' -go '$TUNES'/*.ymx"
step "9.3, the C# reader" check-dotnet "$DOTNET_ABSENT" \
    "'$REPO/ymx/test/check.sh' -dotnet '$TUNES'/*.ymx"
step "9.3, a damaged file" damage "" \
    "'$REPO/ymx/test/damage.sh'"
step "the player rig" rig "$RMAC_ABSENT" \
    "'$REPO/ymx/test/rig.sh' --quick"

if [ "$FULL" = 1 ]; then
    PARITY_ABSENT=$GO_ABSENT
    [ -n "$PARITY_ABSENT" ] || PARITY_ABSENT=$DOTNET_ABSENT
    [ -n "$PARITY_ABSENT" ] || PARITY_ABSENT=$CORPUS_ABSENT
    SWEEP_ABSENT=$RMAC_ABSENT
    [ -n "$SWEEP_ABSENT" ] || SWEEP_ABSENT=$CORPUS_ABSENT

    step "the three trees agree" parity "$PARITY_ABSENT" \
        "'$REPO/ymx/parity.sh' -quick"
    step "the player rig, in full" rig-full "$RMAC_ABSENT" \
        "'$REPO/ymx/test/rig.sh'"
    step "the corpus on the player" sweep "$SWEEP_ABSENT" \
        "'$REPO/ymx/test/sweep.sh' \"\$YM_CORPUS\"/*.ym"
    step "the tick reference" ticks "$EMULATOR_ABSENT" \
        "'$REPO/ymx/test/ticks.sh'"
else
    echo
    echo "  The corpus sweep and the emulator batteries were not asked for:"
    echo "  ymx/verify.sh -full runs those too."
fi

PASSED=$(grep -c '^PASS' "$SUMMARY" || true)
FAILED=$(grep -c '^FAIL' "$SUMMARY" || true)
SKIPPED=$(grep -c '^SKIP' "$SUMMARY" || true)
echo
echo "verify.sh: $PASSED passed, $FAILED failed, $SKIPPED not run"
[ "$FAILED" = 0 ] || exit 1
