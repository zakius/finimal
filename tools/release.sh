#!/bin/bash
# Signed release APKs, one per ABI: dist/finimal-<version>-<abi>.apk.
# Each gets AGP's baseline profile embedded at assets/art-profile/baseline.prof, where ART (Android 15+) reads it when
# the APK is installed and compiles the profiled code. Without it a sideloaded install runs uncompiled until the
# device's idle-time dexopt, and every update resets that: AGP only writes assets/dexopt/, which ProfileInstaller
# handles after the first launch.
# The key comes from keystore.properties (storeFile, storePassword, keyAlias, keyPassword), or KEYSTORE_PROPERTIES.
# Usage: tools/release.sh [--no-build]
set -euo pipefail
cd "$(dirname "$0")/.."
sdk=${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)}
tools=$(ls -d "$sdk/build-tools/"* | sort -V | tail -n 1)
version=$(sed -n 's/^finimal.versionName=//p' gradle.properties)
[ "${1:-}" = --no-build ] || ./gradlew -q :app:assembleRelease
source <(sed 's/^\([a-zA-Z]*\)=\(.*\)$/\1="\2"/' "${KEYSTORE_PROPERTIES:-keystore.properties}")
built=app/build/outputs/apk/release
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p dist
for apk in "$built"/app-*-release-unsigned.apk; do
  abi=${apk#"$built"/app-}; abi=${abi%-release-unsigned.apk}
  # The profile for API 31+ from the .dm AGP writes next to the APK. It names the dex files by checksum; adding a
  # stored entry leaves their bytes as they are.
  dm=$(python3 -c 'import json, sys; m = json.load(open(sys.argv[1])); print(next(f for p in m["baselineProfiles"] if p["minApi"] >= 31 for f in p["baselineProfiles"] if "-" + sys.argv[2] + "-" in f))' "$built/output-metadata.json" "$abi")
  mkdir -p "$tmp/$abi/assets/art-profile"
  unzip -q -p "$built/$dm" primary.prof > "$tmp/$abi/assets/art-profile/baseline.prof"
  cp "$apk" "$tmp/$abi.apk"
  (cd "$tmp/$abi" && zip -q -0 -X "../$abi.apk" assets/art-profile/baseline.prof)
  "$tools/zipalign" -f -P 16 4 "$tmp/$abi.apk" "$tmp/$abi-aligned.apk"
  out="dist/finimal-$version-$abi.apk"
  "$tools/apksigner" sign --ks "$storeFile" --ks-pass "pass:$storePassword" --ks-key-alias "$keyAlias" --key-pass "pass:$keyPassword" --out "$out" "$tmp/$abi-aligned.apk"
  rm -f "$out.idsig"
  ls -l "$out"
done
