#!/bin/bash
# Builds the Linux Flatpak bundle dist/finimal-<version>-x86_64.flatpak: the app image, then flatpak-builder in Flathub's
# CI image (Docker), so nothing needs installing on the host. Install it with `flatpak install --user <file>`.
# Usage: tools/flatpak.sh [--no-gradle]
set -euo pipefail
cd "$(dirname "$0")/.."
version=$(sed -n 's/^finimal.versionName=//p' gradle.properties)
[ "${1:-}" = --no-gradle ] || ./gradlew -q :desktop:createDistributable
mkdir -p dist
# Builder state persists between runs in .flatpak-builder, so libmpv and FFmpeg build once.
docker run --rm --privileged -v "$PWD:/src" -w /src ghcr.io/flathub-infra/flatpak-github-actions:freedesktop-25.08 bash -c "
  set -e
  flatpak-builder --force-clean --disable-rofiles-fuse --repo=build/flatpak-repo \
    build/flatpak-app packaging/flatpak/io.github.zakius.finimal.yml
  flatpak build build/flatpak-app /app/bin/finimal --check-player
  flatpak build-bundle --runtime-repo=https://flathub.org/repo/flathub.flatpakrepo build/flatpak-repo dist/finimal-$version-x86_64.flatpak io.github.zakius.finimal
  chown -R $(id -u):$(id -g) build .flatpak-builder dist
"
ls -l "dist/finimal-$version-x86_64.flatpak"
