#!/bin/sh
# setversion.sh - rewrite the version at every site that carries it.
#
#   ymx/setversion.sh MAJOR.MINOR[.PATCH]
#
# The format version word is the major in the high byte, the minor in the
# low, so versions order numerically; the patch is the released binaries'
# own number and never reaches that word. org.ymx.SetVersion patches eight
# sites - the Java, C# and 68k format constants, SPEC.md's three mentions,
# and the two patch constants - and a site whose surrounding text no longer
# matches fails loudly, with the consistency tests reading the same sites
# back. The patch defaults to 0, so a format bump clears it.
#
# After a bump: write this release's section in doc/RELEASES.md, reassemble
# the cores (ymx/mkcores.sh), repin the corpus (mvn test -Dymx.pin=refresh)
# and publish (ymx/mkrelease.sh -publish). A patch alone needs no repin: the
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
