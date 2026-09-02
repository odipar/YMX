#!/bin/sh
# The standalone ym-to-ymx executables: one per platform, each carrying the
# SNDH cores and the PRG stub, so a machine with neither a repository nor a
# toolchain can turn a YM dump into something that plays.
#
#   ymx/publish.sh [outdir]           # the six platforms below
#   TARGETS="linux-x64" ymx/publish.sh
#
# They are built from go/, which is why there are six of them. go build
# cross-compiles to any target from any host with nothing installed for it,
# so one machine covers Windows, macOS and Linux on both architectures. The
# .NET tree builds the same bytes and is still the reference the Go tree is
# held to, but it reaches three of these from here at ten times the size:
# NativeAOT does not compile across operating systems, and a self-contained
# single file carries a runtime.
#
# The binaries come from dist/release, which ymx/mkrelease.sh stages, so a
# published executable carries this release's own cores. Every zip of this
# release is removed at the start, and each platform's directory before its
# build, so what a run leaves behind is what that run built. Needs Go.
set -e
cd "$(dirname "$0")/.."
REPO=$(pwd)
OUT=${1:-dist/standalone}
TARGETS=${TARGETS:-"win-x64 win-arm64 osx-x64 osx-arm64 linux-x64 linux-arm64"}

# the cores the executable embeds: this release's, every core and the stub
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

# What //go:embed takes: this release's binaries and nothing else, so an
# executable cannot carry an older release's core by accident.
CORES=go/internal/cores/data
rm -f "$CORES"/*.bin
cp dist/release/*.bin "$CORES"/

# Every zip of this release goes first. One from an earlier run holds the
# cores dist/release carried then, and staging has replaced those; mkrelease
# reads the names alone and cannot tell the two apart.
mkdir -p "$OUT"
rm -f "$OUT"/ym-to-ymx-*-v"$VERSION".zip

for target in $TARGETS; do
    case "$target" in
        win-*)   os=windows; exe=ym-to-ymx.exe; launcher=ymxplay.cmd ;;
        osx-*)   os=darwin;  exe=ym-to-ymx;     launcher=ymxplay.sh  ;;
        linux-*) os=linux;   exe=ym-to-ymx;     launcher=ymxplay.sh  ;;
        *) echo "publish: $target is not a platform this builds" >&2; exit 1 ;;
    esac
    case "$target" in
        *-x64)   arch=amd64 ;;
        *-arm64) arch=arm64 ;;
        *) echo "publish: $target names no architecture" >&2; exit 1 ;;
    esac

    # The directory is where a built tool gets tried out, so the build starts
    # from an empty one.
    rm -rf "$OUT/$target"
    mkdir -p "$OUT/$target"
    # CGO off is what makes the binary static and the cross-build work at
    # all; -s -w drop the symbol and debug tables, which nothing here reads.
    (cd go && CGO_ENABLED=0 GOOS=$os GOARCH=$arch \
        go build -ldflags="-s -w" -o "$REPO/$OUT/$target/$exe" ./cmd/ym-to-ymx)
    cp "ymx/$launcher" "$OUT/$target/"
    echo "$OUT/$target/$exe: $(wc -c < "$OUT/$target/$exe" | tr -d ' ') bytes"

    # the two files by name: a glob carries whatever else sits there
    zip="ym-to-ymx-$target-v$VERSION.zip"
    (cd "$OUT/$target" && zip -q -X "../$zip" "$exe" "$launcher")
    echo "$OUT/$zip: $(wc -c < "$OUT/$zip" | tr -d ' ') bytes"
done

# The host's executable, tried out as a user would: from a directory that is
# not the repository, on a tune packed with -copies, to an SNDH file and a
# program. It carries only the cores it embeds, so a core it names and does
# not carry fails here, where 0.10.0 shipped a standalone that refused its
# own -copies tune.
case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) host=osx-arm64 ;;
    Darwin-x86_64) host=osx-x64 ;;
    Linux-aarch64) host=linux-arm64 ;;
    Linux-x86_64) host=linux-x64 ;;
    *) host="" ;;
esac
case " $TARGETS " in
    *" $host "*)
        trial=$(mktemp -d)
        cp "ym/test/Synergy Credits.ym" "$trial/tune.ym"
        for out in out.sndh out.prg; do
            (cd "$trial" && env -u YMX_REPO "$REPO/$OUT/$host/ym-to-ymx" \
                -copies -k2 -c22 -n440 "$out" tune.ym > "$out.log" 2>&1) \
                && [ -s "$trial/$out" ] || {
                    echo "publish: $OUT/$host/ym-to-ymx cannot build $out from a" \
                        "-copies tune:" >&2
                    tail -3 "$trial/$out.log" >&2
                    exit 1; }
        done
        rm -rf "$trial"
        echo "$OUT/$host/ym-to-ymx: builds an SNDH file and a program from a -copies tune"
        ;;
esac
echo "$OUT: $(echo $TARGETS | wc -w | tr -d ' ') platforms"
