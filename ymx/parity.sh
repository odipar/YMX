#!/bin/sh
# parity.sh - the bar the three trees are held to: one command line gives the
# same bytes, the same text and the same exit status in Java, C# and Go.
#
#   ymx/parity.sh [-quick]
#
# The trees agreed on the files they wrote long before they agreed on anything
# else. What drifted was everything around the bytes: a flag one tree parsed
# and another took for a file name, a value that packed a file in one tree and
# stopped the run in the next, a fault named in two trees and thrown as a stack
# trace in the third. A sweep that compares only output files sees none of it,
# so this compares the whole run: stdout, stderr, the exit status, and every
# file the case leaves behind.
#
# Each case runs three times, once per tree, each in a directory of its own,
# and the directory is hashed whole afterwards - so a file written under a
# different name is a difference and not a pass. The tunes are copied in under
# plain names, because a path with a space in it has twice produced a false
# pass here: two trees printing the same usage error look identical, and
# nothing was compared at all.
#
# YM_CORPUS names the directory holding the .ym collection. Without it the
# script says so and stops.
#
#   -quick   four tunes and the cases that have caught something, for a test
#            run; the default sweeps every case over eight tunes.

REPO=$(cd "$(dirname "$0")/.." && pwd)
# TMPDIR carries a trailing slash on macOS, which made every path in the
# reports miss the substitution below and every case that printed where it
# wrote look like a difference.
TMP=${TMPDIR:-/tmp}
WORK=${PARITY_WORK:-${TMP%/}/ymx-parity}
DLL=$REPO/dotnet/bin/Release/net10.0/ymx.dll

QUICK=no
[ "$1" = "-quick" ] && QUICK=yes

CORPUS=${YM_CORPUS:-$HOME/git/jatari/data/ym_format}
if [ ! -d "$CORPUS" ]; then
    echo "parity.sh: set YM_CORPUS to the directory holding the .ym collection" >&2
    exit 2
fi

# The Java class behind each command. ym-to-ymx has none: that tool is C# and
# Go only, so its cases run in those two.
# The name a tree gives a command, where the three do not agree. The
# checker is one tool: ymxcheck as a Go binary, check as the C# dispatcher's
# subcommand, org.ymx.rig.Check as a Java class.
tree_command() {
    case $1:$2 in
        ymxcheck:dotnet) echo check ;;
        *)               echo "$1" ;;
    esac
}

java_class() {
    case $1 in
        ymxcheck) echo org.ymx.rig.Check ;;
        ymx)    echo org.ym6.Ymx ;;
        play)   echo org.ym6.Play ;;
        ymsndh) echo org.ym6.YmSndh ;;
        st4)    echo org.st4.St4 ;;
        dst4)   echo org.st4.Dst4 ;;
        mksndh) echo org.ymx.MkSndh ;;
        mkprg)  echo org.ymx.MkPrg ;;
        *)      echo "" ;;
    esac
}

# What a run leaves that says nothing about the tree that made it: the paths
# it happened to run in, the progress meter's redraws, and a measured time.
# normalise <file> <case directory>
normalise() {
    # The case directory first, and it carries the tree's own name: a run that
    # prints where it wrote differs from another only in that name, which is
    # the one thing about it that cannot be the same. /private/var before it,
    # because a temp directory reached through the platform's own symlink is
    # the same directory and one tree resolves it.
    tr -d '\r' < "$1" \
        | sed -e 's|/private/var/|/var/|g' \
              -e "s|$2|CASE|g" \
              -e "s|$WORK|WORK|g" -e "s|$REPO|REPO|g" -e "s|$HOME|HOME|g" \
              -e 's|[0-9][0-9]*\.[0-9][0-9]* ms|T ms|g' \
              -e 's|[0-9][0-9]*m* *[0-9][0-9]*s left||g' \
              -e 's|^\[[ 0-9][ 0-9][ 0-9]%\].*$||' \
        | grep -v '^ *$'
}

# Every file the case left, by name and digest, so a file written under
# another name shows up as a difference.
fingerprint() {
    (cd "$1" && find . -type f | LC_ALL=C sort | while read -r f; do
        printf '%s  %s\n' "$f" "$(shasum -a 256 < "$f" | cut -c1-16)"
    done)
}

pass=0; fail=0; failed_cases=""

# one_case <label> <command> <argv...>
# @T and @U stand for the two tunes, copied in as a.ym and b.ym; @O for an
# output name the case chooses.
one_case() {
    label=$1; command=$2; shift 2
    for tree in java dotnet go; do
        d=$WORK/$label/$tree
        rm -rf "$d"; mkdir -p "$d"
        cp "$TUNE_A" "$d/a.ym"; cp "$TUNE_B" "$d/b.ym"
        # The set form takes a directory that is already there; without it
        # every tree prints usage and the case compares nothing.
        case " $* " in *" adir/ "*) mkdir -p "$d/adir" ;; esac
        # A case naming a fixture builds it here, in the tree's own
        # directory, so the three runs meet the same bytes.
        [ -n "$SETUP" ] && (cd "$d" && sh -c "$SETUP")
        argv=""
        for a in "$@"; do
            a=$(printf '%s' "$a" | sed -e 's|@T|a.ym|g' -e 's|@U|b.ym|g')
            argv="$argv $a"
        done
        klass=$(java_class "$command")
        if [ "$tree" = java ] && [ -z "$klass" ]; then
            continue        # ym-to-ymx: this tree does not carry it
        fi
        (
            cd "$d" || exit 1
            case $tree in
                java)   eval "TOS=/nope java -ea -Dymx.repo=$REPO -cp $REPO/target/classes:$REPO/target/test-classes $klass $argv" ;;
                dotnet) eval "TOS=/nope YMX_REPO=$REPO dotnet $DLL $(tree_command "$command" dotnet) $argv" ;;
                go)     eval "TOS=/nope $REPO/go/bin/$(tree_command "$command" go) $argv" ;;
            esac
        ) >"$d.out" 2>"$d.err"
        echo $? > "$d.status"
    done

    trees="java dotnet go"
    [ -z "$(java_class "$command")" ] && trees="dotnet go"
    first=""; differs=""
    for tree in $trees; do
        d=$WORK/$label/$tree
        {
            echo "--- exit"; cat "$d.status"
            echo "--- stdout"; normalise "$d.out" "$d"
            echo "--- stderr"; normalise "$d.err" "$d"
            echo "--- files"; fingerprint "$d"
        } > "$d.report"
        if [ -z "$first" ]; then
            first=$tree
        elif ! cmp -s "$WORK/$label/$first.report" "$d.report"; then
            differs="$differs $first/$tree"
        fi
    done

    SETUP=""
    if [ -n "$differs" ]; then
        fail=$((fail + 1)); failed_cases="$failed_cases $label"
        echo "DIFFER $label ($command):$differs"
        for pair in $differs; do
            a=${pair%%/*}; b=${pair##*/}
            diff "$WORK/$label/$a.report" "$WORK/$label/$b.report" \
                | head -12 | sed 's/^/    /'
        done
    else
        pass=$((pass + 1))
    fi
}

# The cases. Each has caught something, or covers a flag that changes bytes.
sweep() {
    one_case base-$1        ymx  @T out.ymx
    one_case ring-$1        ymx  -n480 @T out.ymx
    one_case chunk-$1       ymx  -c48 @T out.ymx
    one_case unit1-$1       ymx  -k1 @T out.ymx
    one_case unit4-$1       ymx  -k4 @T out.ymx
    one_case once-$1        ymx  -o @T out.ymx
    one_case loop-$1        ymx  -l0 @T out.ymx
    one_case timers-$1      ymx  -timersBC @T out.ymx
    one_case drum-$1        ymx  -drumhz12800 @T out.ymx
    one_case sid-$1         ymx  -sidresume @T out.ymx
    one_case trim-$1        ymx  -min0 -sec5 @T out.ymx
    one_case window-$1      ymx  -startframe500 -frames300 @T out.ymx
    one_case meta-$1        ymx  -meta @T
    one_case script-$1      ymx  -script @T
    one_case default-out-$1 ymx  @T
    one_case setdir-$1      ymx  @T @U adir/
    one_case sndh-$1        ymsndh out.sndh @T
    one_case sndh-trim-$1   ymsndh -sec5 out.sndh @T
    one_case sndh-set-$1    ymsndh -tSet out.sndh @T @U
    one_case y2y-ymx-$1     ym-to-ymx out.ymx @T
    one_case y2y-sndh-$1    ym-to-ymx out.sndh @T @U
    one_case y2y-prg-$1     ym-to-ymx out.prg @T
    one_case play-trim-$1   play -min0 -sec2 @T
    one_case play-unit-$1   play -k1 @T

    if [ "$QUICK" = no ]; then
        one_case st4-k1-$1   st4 -k1 @T out.st4
        one_case st4-k2-$1   st4 -k2 @T out.st4
        one_case st4-k4-$1   st4 -k4 @T out.st4
        one_case perf-$1     ymsndh -perf out.sndh @T
        one_case nomask-$1   ym-to-ymx -nomask out.prg @T
    fi
}



# What each command says when it is given nothing, or asked for help. The
# texts drifted apart once already: one tree's ymx printed two lines where
# the others printed forty-seven.
usage_texts() {
    one_case usage-ymx        ymx
    one_case usage-st4        st4
    one_case usage-dst4       dst4
    one_case usage-mksndh     mksndh
    one_case usage-mkprg      mkprg
    one_case usage-ymsndh     ymsndh
    one_case usage-y2y        ym-to-ymx
    one_case help-play        play -h
    one_case help-y2y         ym-to-ymx -h
    # a flag wrong and an input missing at once: which one a tree answers
    one_case flag-before-input ymx -c7 nosuch.ym out.ymx
}

# The malformed inputs, built once so the three runs meet the same bytes.
# A file a tool cannot read is where the trees have most room to differ:
# one names the fault, one prints its runtime's bounds text, one exits on a
# trace. None of that shows in a sweep over files that read cleanly.
fixtures() {
    FX=$WORK/fx; mkdir -p "$FX"
    # deterministic, so a rerun compares the same bytes
    perl -e 'print pack("C*", map { ($_ * 37 + 11) % 256 } 0 .. 4095)' > "$FX/in.bin"
    printf 'not a ym file at all' > "$FX/bad.ym"
    perl -e 'print "\0" x 13, "##", "\0"' > "$FX/hash16.bin"
    perl -e 'print pack("C*", map { ($_ * 7) % 256 } 0 .. 31)' > "$FX/notaymx.bin"

    # a real ST4 container, and the same one cut short
    "$REPO/go/bin/st4" -f "$FX/in.bin" "$FX/good.st4" >/dev/null 2>&1
    perl -e 'local $/; open F, "<", $ARGV[0]; binmode F; $d = <F>; print substr($d, 0, 30)' \
        "$FX/good.st4" > "$FX/short.st4"

    # a real .ymx, for the checker to read back
    (cd "$FX" && cp "$TUNE_A" c.ym \
        && "$REPO/go/bin/ymx" -frames200 c.ym good.ymx >/dev/null 2>&1)

    # a real SNDH, and the same one a byte short of its terminator
    (cd "$FX" && cp "$TUNE_A" f.ym \
        && "$REPO/go/bin/ymsndh" good.sndh f.ym >/dev/null 2>&1)
    perl -e 'local $/; open F, "<", $ARGV[0]; binmode F; $d = <F>;
             print substr($d, 0, length($d) - 1)' \
        "$FX/good.sndh" > "$FX/trunc.sndh" 2>/dev/null
}

# The §9.3 checker, which reads a packed tune back against the rules a
# player does not check. One tool in three trees, so it belongs here.
checks() {
    SETUP="cp $WORK/fx/good.ymx ."    ; one_case check-good     ymxcheck good.ymx
    SETUP="cp $WORK/fx/notaymx.bin ." ; one_case check-notaymx  ymxcheck notaymx.bin
    one_case check-missing  ymxcheck nosuch.ymx
    one_case check-usage    ymxcheck
}

# What a tool does with a file it cannot read.
malformed() {
    SETUP="cp $WORK/fx/trunc.sndh ."   ; one_case bad-sndh-cut   mkprg out.prg trunc.sndh
    SETUP="cp $WORK/fx/hash16.bin ."   ; one_case bad-sndh-hash  mkprg out.prg hash16.bin
    SETUP="cp $WORK/fx/notaymx.bin ."  ; one_case bad-ymx        mksndh out.sndh notaymx.bin
    SETUP="cp $WORK/fx/short.st4 ."    ; one_case bad-st4-short  dst4 short.st4 out.bin
    SETUP="cp $WORK/fx/good.st4 ."     ; one_case bad-st4-as-ymx mkprg out.prg good.st4
    SETUP="cp $WORK/fx/bad.ym ."       ; one_case bad-ym         ymx bad.ym out.ymx
    SETUP="cp $WORK/fx/bad.ym ."       ; one_case bad-ym-sndh    ymsndh out.sndh bad.ym
    SETUP="mkdir adir"                 ; one_case dir-as-input   st4 adir out.st4
    SETUP="cp $WORK/fx/in.bin . && mkdir adir"
    one_case dir-as-output  st4 in.bin adir
    SETUP="cp $WORK/fx/good.st4 . && mkdir adir"
    one_case dir-as-output-d dst4 good.st4 adir
}

# The command lines that used to answer differently in different trees.
refusals() {
    one_case bad-unit      ymx -k0 @T out.ymx
    one_case bad-value     ymx -nabc @T out.ymx
    one_case bad-flag      ymx -zzz @T out.ymx
    one_case huge-value    ymx -frames3000000000 @T out.ymx
    one_case empty-window  ymx -startframe100 -endframe50 @T out.ymx
    one_case no-input      ymx nosuch.ym out.ymx
    one_case no-args       ymx
    one_case sndh-bad-flag ymsndh -zzz out.sndh @T
    one_case sndh-no-input ymsndh out.sndh nosuch.ym
    one_case mkprg-no-inp  mkprg out.prg nosuch.ymx
    one_case mksndh-no-inp mksndh out.sndh nosuch.ymx
    one_case play-bad-unit play -k0 @T
    one_case play-bad-val  play -nabc @T
    one_case play-empty-k  play -k @T
    one_case st4-no-input  st4 nosuch.bin out.st4
    one_case dst4-no-input dst4 nosuch.st4 out.bin
}

# Build the Go commands first. go/bin is not built by anything else, and a
# harness that compares yesterday's binary against today's source reports
# agreement it has not checked - the same false pass this script exists to
# catch. The build cache makes this cheap when nothing changed. The Java tree
# is built by whoever runs mvn; PARITY_NO_BUILD skips the C# build for a
# caller who has just done it.
for c in ymx ym-to-ymx play ymsndh mksndh mkprg st4 dst4; do
    (cd "$REPO/go" && go build -o "bin/$c" "./cmd/$c") || {
        echo "parity.sh: cannot build go/cmd/$c" >&2; exit 2; }
done
if [ -z "$PARITY_NO_BUILD" ]; then
    (cd "$REPO/dotnet" && dotnet build -c Release -v q >/dev/null) || {
        echo "parity.sh: cannot build the C# tree" >&2; exit 2; }
fi

rm -rf "$WORK"; mkdir -p "$WORK"

# The cores are shared, not per case, so the first tree to want one
# assembles it into dist/ and prints that it did, where the two after it
# find it there and print nothing. That is a difference in the runs and
# not in the trees, and it appears on every release bump, when dist/
# carries no core at the new version yet. Assemble them before any case
# runs, for the same reason a case builds its fixture: so the three runs
# meet the same bytes.
for flags in "" "-perf" "-nomask"; do
    # shellcheck disable=SC2086
    "$REPO/ymx/mkcores.sh" $flags >/dev/null 2>&1 || {
        echo "parity.sh: cannot assemble the cores$flags" >&2; exit 2; }
done

count=8
[ "$QUICK" = yes ] && count=4

# One name per line and read whole: a corpus name holds spaces, and splitting
# on them fed the cases a tune that was not there. The cases then compared two
# runs that had both failed the same way, which is a pass that means nothing.
ls "$CORPUS"/*.ym > "$WORK/tunes" 2>/dev/null
[ -s "$WORK/tunes" ] || { echo "parity.sh: no .ym files in $CORPUS" >&2; exit 2; }

i=0
prev=""
while IFS= read -r tune; do
    if [ -z "$prev" ]; then prev=$tune; continue; fi
    i=$((i + 1))
    TUNE_A=$prev; TUNE_B=$tune
    sweep "$i"
    prev=$tune
    [ "$i" -ge "$count" ] && break
done < "$WORK/tunes"

TUNE_A=$(head -1 "$WORK/tunes"); TUNE_B=$(head -2 "$WORK/tunes" | tail -1)
refusals
usage_texts
fixtures
checks
malformed

echo
echo "parity: $pass matched, $fail differed"
[ "$fail" -eq 0 ] || { echo "differing cases:$failed_cases"; exit 1; }
