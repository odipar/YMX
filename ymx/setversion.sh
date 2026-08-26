#!/bin/sh
# setversion.sh - rewrite one of the two versions at every site it reaches.
#
#   ymx/setversion.sh -format MAJOR.MINOR
#   ymx/setversion.sh -release MAJOR.MINOR[.PATCH]
#
# The two are set apart because they mean different things. The format
# version is the compatibility gate: a word in every header, checked by
# the player, the major in the high byte and the minor in the low so
# versions order numerically. Moving it stops every tune already packed
# from playing. The release version is the binaries' own, three plain
# numbers that reach no file, and moving it breaks nothing.
#
# -format patches seven sites: the Java, C#, Go and 68k constants and
# SPEC.md's three mentions. -release patches nine more, the three numbers
# in each of the three trees. A site whose surrounding text no longer matches fails loudly, and
# the consistency tests read the same sites back. A release patch defaults
# to 0, so -release 0.9 clears it.
#
# After -format: write this release's section in doc/RELEASES.md, reassemble
# the cores (ymx/mkcores.sh), repin the corpus (mvn test -Dymx.pin=refresh)
# and publish (ymx/mkrelease.sh -publish). -release needs no repin: the
# tunes are untouched.
set -e
YMX_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YMX_DIR/.." && pwd)

# -dotnet as the first argument runs the C# tree (dotnet/) instead of the
# Java one; both rewrite the same sites.
if [ "$1" = "-dotnet" ]; then
    shift
    DLL="$REPO/dotnet/bin/Release/net10.0/ymx.dll"
    (cd "$REPO/dotnet" && dotnet build -c Release -v q)
    YMX_REPO="$REPO" exec dotnet "$DLL" setversion "$@"
fi
(cd "$REPO" && mvn -q compile)
exec java -ea -Dymx.repo="$REPO" -cp "$REPO/target/classes" org.ymx.SetVersion "$@"
