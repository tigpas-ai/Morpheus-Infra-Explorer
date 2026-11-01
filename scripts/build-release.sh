#!/usr/bin/env bash
# Builds the plugin jar of a released version from its git tag (v<version>).
#
#   scripts/build-release.sh <version> [output directory]
#
# The jar is written to <output directory>/infra-explorer-<version>-all.jar (default: build/releases). An existing
# jar is kept. The integration test uses this directory to restore the version that was installed before the test.
set -euo pipefail

version=${1:?"usage: $0 <version> [output directory]"}
repo=$(cd "$(dirname "$0")/.." && pwd)
out=${2:-$repo/build/releases}
mkdir -p "$out"
out=$(cd "$out" && pwd)
jar="$out/infra-explorer-$version-all.jar"

if [[ -f $jar ]]; then
  echo "$jar"
  exit 0
fi

tag="v$version"
git -C "$repo" rev-parse -q --verify "refs/tags/$tag" >/dev/null || { echo "tag $tag not found" >&2; exit 1; }

work=$(mktemp -d)
cleanup() {
  git -C "$repo" worktree remove --force "$work/src" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

git -C "$repo" worktree add --quiet --detach "$work/src" "$tag" >&2
(cd "$work/src" && ./gradlew -q --no-daemon shadowJar) >&2
built="$work/src/build/libs/infra-explorer-$version-all.jar"
[[ -f $built ]] || { echo "build of $tag did not produce infra-explorer-$version-all.jar" >&2; exit 1; }
cp "$built" "$jar"
echo "$jar"
