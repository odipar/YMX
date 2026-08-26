#!/bin/sh
# The standalone ym-to-ymx executables: one per platform, each carrying the
# .NET runtime and the SNDH cores and PRG stub, so a machine with neither a
# repository nor an SDK can turn a YM dump into something that plays.
#
#   ymx/publish.sh [outdir]        # the three platforms below
#   RIDS="linux-x64" ymx/publish.sh
#
# The binaries come from dist/release, which ymx/mkrelease.sh stages, so a
# published executable carries this release's own cores. Every zip of this
# release is removed at the start, and each platform's directory before its
# build, so what a run leaves behind is what that run built. Needs the .NET
# SDK.
set -e
cd "$(dirname "$0")/.."
REPO=$(pwd)
OUT=${1:-dist/standalone}
RIDS=${RIDS:-"win-x64 osx-arm64 linux-x64"}

# the cores the executable embeds: this release's, all twelve and the stub
if [ ! -d dist/release ]; then
    ymx/mkrelease.sh >/dev/null
fi

# The release version names the zips as it names the binaries, and the
# staged manifest is where it is written down.
VERSION=$(sed -n 's/^YMX player binaries - release \([0-9.]*\),.*/\1/p' \
    dist/release/MANIFEST.txt)
if [ -z "$VERSION" ]; then
    echo "publish: dist/release/MANIFEST.txt names no release" >&2
    exit 1
fi

# Every zip of this release goes first. One from an earlier run holds the
# cores dist/release carried then, and staging has replaced those; mkrelease
# reads the names alone and cannot tell the two apart.
mkdir -p "$OUT"
rm -f "$OUT"/ym-to-ymx-*-v"$VERSION".zip

for rid in $RIDS; do
    case "$rid" in
        win-*) exe=ym-to-ymx.exe; launcher=ymxplay.cmd ;;
        *)     exe=ym-to-ymx;     launcher=ymxplay.sh  ;;
    esac
    # dotnet publish leaves what it finds, and this directory is where a
    # built tool gets tried out, so the build starts from an empty one
    rm -rf "$OUT/$rid"
    dotnet publish dotnet -c Release -r "$rid" --self-contained true \
        -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true \
        -p:AssemblyName=ym-to-ymx -p:DebugType=none \
        -o "$OUT/$rid" >/dev/null
    cp "ymx/$launcher" "$OUT/$rid/"
    echo "$OUT/$rid/$exe: $(wc -c < "$OUT/$rid/$exe" | tr -d ' ') bytes"

    # the two files by name: a glob carries whatever else sits there
    zip="ym-to-ymx-$rid-v$VERSION.zip"
    (cd "$OUT/$rid" && zip -q -X "../$zip" "$exe" "$launcher")
    echo "$OUT/$zip: $(wc -c < "$OUT/$zip" | tr -d ' ') bytes"
done
echo "$OUT: $(echo $RIDS | wc -w | tr -d ' ') platforms"
