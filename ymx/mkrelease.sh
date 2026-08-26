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
# It also carries in the standalone ym-to-ymx zips that ymx/publish.sh
# builds into dist/standalone, one per platform, taking those named for
# this release. publish.sh embeds the staged binaries and reads the
# release version out of dist/release/MANIFEST.txt, so it runs after a
# staging run and before the publishing one:
#
#   ymx/mkrelease.sh            # the binaries publish.sh embeds
#   ymx/publish.sh              # the executables carrying them
#   ymx/mkrelease.sh -publish   # both, to the release page
#
# A release staged without publish.sh carries no zips and publishes
# without them.
#
# -publish creates or updates the release tagged binaries-v<release> -
# the format version and this release's patch - with gh, replacing its
# assets and posting this release's section of doc/RELEASES.md as the
# notes.
#
# A new release is tagged at the staged commit, the one its notes name. An
# existing tag stays where it is: a run from another commit stops rather
# than posting notes naming a commit the tag does not reach. A patch is
# published beside the patch before it, and the superseded release is
# deleted by hand - nothing here removes a published release.
#
# The work is org.ymx.MkRelease's; this only finds the repo and the classes.
# Needs rmac; -publish needs gh and a pushed HEAD.
set -e
YMX_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YMX_DIR/.." && pwd)

# -dotnet as the first argument runs the C# tree (dotnet/) instead of the
# Java one; both produce the same bytes.
if [ "$1" = "-dotnet" ]; then
    shift
    DLL="$REPO/dotnet/bin/Release/net10.0/ymx.dll"
    (cd "$REPO/dotnet" && dotnet build -c Release -v q)
    YMX_REPO="$REPO" exec dotnet "$DLL" mkrelease "$@"
fi
(cd "$REPO" && mvn -q compile)
exec java -ea -Dymx.repo="$REPO" -cp "$REPO/target/classes" org.ymx.MkRelease "$@"
