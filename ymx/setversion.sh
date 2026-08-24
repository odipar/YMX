#!/bin/sh
# setversion.sh - rewrite the format version at every site that carries it.
#
#   ymx/setversion.sh MAJOR.MINOR
#
# The version word is the major in the high byte, the minor in the low, so
# versions order numerically. org.ymx.SetVersion patches the Java, C# and
# 68k constants and SPEC.md's three mentions; a site whose surrounding text
# no longer matches fails loudly, and the consistency tests read the same
# sites back. After a bump: reassemble the cores (ymx/mkcores.sh), repin
# the corpus (mvn test -Dymx.pin=refresh) and publish
# (ymx/mkrelease.sh -publish).
set -e
YMX_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YMX_DIR/.." && pwd)

# -dotnet as the first argument runs the C# tree (dotnet/) instead of the
# Java one; both rewrite the same sites.
if [ "$1" = "-dotnet" ]; then
    shift
    DLL="$REPO/dotnet/bin/Release/net10.0/ymx.dll"
    [ -f "$DLL" ] || (cd "$REPO/dotnet" && dotnet build -c Release -v q)
    YMX_REPO="$REPO" exec dotnet "$DLL" setversion "$@"
fi
[ -d "$REPO/target/classes" ] || (cd "$REPO" && mvn -q compile)
exec java -ea -Dymx.repo="$REPO" -cp "$REPO/target/classes" org.ymx.SetVersion "$@"
