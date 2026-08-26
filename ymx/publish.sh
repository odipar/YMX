#!/bin/sh
# The standalone ym-to-ymx executables: one per platform, each carrying the
# .NET runtime and the SNDH cores and PRG stub, so a machine with neither a
# repository nor an SDK can turn a YM dump into something that plays.
#
#   ymx/publish.sh [outdir]        # the three platforms below
#   RIDS="linux-x64" ymx/publish.sh
#
# The binaries come from dist/release, which ymx/mkrelease.sh stages, so a
# published executable carries this release's own cores. Needs the .NET SDK.
set -e
cd "$(dirname "$0")/.."
REPO=$(pwd)
OUT=${1:-dist/standalone}
RIDS=${RIDS:-"win-x64 osx-arm64 linux-x64"}

# the cores the executable embeds: this release's, all twelve and the stub
if [ ! -d dist/release ]; then
    ymx/mkrelease.sh >/dev/null
fi

mkdir -p "$OUT"
for rid in $RIDS; do
    dotnet publish dotnet -c Release -r "$rid" --self-contained true \
        -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true \
        -p:AssemblyName=ym-to-ymx -p:DebugType=none \
        -o "$OUT/$rid" >/dev/null
    exe="$OUT/$rid/ym-to-ymx"
    [ -f "$exe.exe" ] && exe="$exe.exe"
    size=$(wc -c < "$exe" | tr -d ' ')
    echo "$exe: $size bytes"
done

# the launchers travel beside them
for rid in $RIDS; do
    case "$rid" in
        win-*) cp ymx/ymxplay.cmd "$OUT/$rid/" ;;
        *)     cp ymx/ymxplay.sh  "$OUT/$rid/" ;;
    esac
done
echo "$OUT: $(echo $RIDS | wc -w | tr -d ' ') platforms"
