#!/bin/sh
# mkrelease.sh - stage the prebuilt player binaries, and publish them.
#
#   ymx/mkrelease.sh [stagedir]              # assemble and verify only
#   ymx/mkrelease.sh -publish [stagedir]     # and publish a GitHub release
#
# Stages every core variant - three unit sizes by the perf and mask flags -
# plus the PRG stub, verified against the descriptors the combiners read,
# with a MANIFEST.txt of sizes and SHA-256 digests. doc/BINARIES.md is the
# contract another system follows.
#
# -publish creates or updates the release tagged binaries-v<format version>
# with gh, replacing its assets. The tag tracks the format version: a new
# format is a new release, and an unchanged format updates in place.
# Needs rmac; -publish needs gh and a pushed HEAD.
set -e
YMX_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YMX_DIR/.." && pwd)
[ -d "$REPO/target/classes" ] || (cd "$REPO" && mvn -q compile)

PUBLISH=0
if [ "$1" = "-publish" ]; then
    PUBLISH=1
    shift
fi
STAGE=${1:-"$REPO/dist/release"}
COMMIT=$(cd "$REPO" && git rev-parse --short HEAD)

rm -rf "$STAGE"
java -ea -Dymx.repo="$REPO" -cp "$REPO/target/classes" \
    org.ymx.MkRelease "$STAGE" "$COMMIT"

if [ $PUBLISH -eq 1 ]; then
    FORMAT=$(sed -n 's/.*format version \([0-9]*\),.*/\1/p' "$STAGE/MANIFEST.txt")
    TAG="binaries-v$FORMAT"
    if ! gh release view "$TAG" >/dev/null 2>&1; then
        gh release create "$TAG" --title "YMX player binaries, format $FORMAT" \
            --notes "Prebuilt SNDH cores and the PRG stub, assembled at $COMMIT. doc/BINARIES.md is the combine contract; MANIFEST.txt lists sizes and SHA-256 digests."
    fi
    gh release upload "$TAG" --clobber "$STAGE"/*.bin "$STAGE/MANIFEST.txt"
    echo "published $TAG"
fi
