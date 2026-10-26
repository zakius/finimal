#!/bin/bash
# libmpv for the Windows desktop build: shinchiro's mpv-winbuild-cmake, the Windows builds mpv.io links to. One
# self-contained DLL; Linux uses the distribution's libmpv2 instead. Lands where the packaged app finds it
# (desktop/resources/windows, the app's resources folder at runtime).
# Usage: tools/fetch-libmpv-windows.sh
set -euo pipefail
cd "$(dirname "$0")/.."
build=20261021
asset=mpv-dev-x86_64-20261021-git-e471496c56.7z
out=desktop/resources/windows
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl -fsSL -o "$tmp/mpv.7z" "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/$build/$asset"
mkdir -p "$out"
7z e -y -o"$out" "$tmp/mpv.7z" libmpv-2.dll > /dev/null
ls -l "$out/libmpv-2.dll"
