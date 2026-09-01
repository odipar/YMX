# parity-lib.sh - what parity.sh and parity-one.sh both need to run one
# command in three trees and say whether they agree.
#
# Sourced, never run. The caller sets REPO, WORK and DLL first, and TUNE_A
# and TUNE_B where a case names a tune.

# The Java class behind each command. ym-to-ymx has none: that tool is C# and
# Go only, so its cases run in those two.
java_class() {
    case $1 in
        ymxcheck) echo org.ymx.rig.Check ;;
        ymx)    echo org.ym6.Ymx ;;
        ym-to-ymx) echo org.ym6.YmToYmx ;;
        play)   echo org.ym6.Play ;;
        ymsndh) echo org.ym6.YmSndh ;;
        st4)    echo org.st4.St4 ;;
        dst4)   echo org.st4.Dst4 ;;
        mksndh) echo org.ymx.MkSndh ;;
        mkprg)  echo org.ymx.MkPrg ;;
        *)      echo "" ;;
    esac
}

# The name a tree gives a command, where the three do not agree. The checker
# is one tool: ymxcheck as a Go binary, check as the C# dispatcher's
# subcommand, org.ymx.rig.Check as a Java class.
tree_command() {
    case $1:$2 in
        ymxcheck:dotnet) echo check ;;
        *)               echo "$1" ;;
    esac
}

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

fingerprint() {
    (cd "$1" && find . -type f | LC_ALL=C sort | while read -r f; do
        printf '%s  %s\n' "$f" "$(shasum -a 256 < "$f" | cut -c1-16)"
    done)
}

# One command run in all three trees and compared: exit status, stdout, stderr
# and every file the run left. Prints what differs and gives 1 where anything
# does, so a caller counting cases and a caller writing one verdict per tune
# compare the same things.
compare_trees() {
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

    [ -z "$differs" ] && return 0
    echo "DIFFER $label ($command):$differs"
    for pair in $differs; do
        a=${pair%%/*}; b=${pair##*/}
        diff "$WORK/$label/$a.report" "$WORK/$label/$b.report" \
            | head -12 | sed 's/^/    /'
    done
    return 1
}
