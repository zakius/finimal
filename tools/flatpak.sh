#!/bin/bash
# Builds the Linux Flatpak bundle dist/finimal-<version>-x86_64.flatpak. flatpak-builder runs in Flathub's CI image
# (Docker), so nothing needs installing on the host. Install the bundle with `flatpak install --user <file>`.
# Usage: tools/flatpak.sh [--no-gradle]
set -euo pipefail
cd "$(dirname "$0")/.."
version=$(sed -n 's/^finimal.versionName=//p' gradle.properties)
[ "${1:-}" = --no-gradle ] || ./gradlew -q :desktop:createReleaseDistributable
mkdir -p dist
# flatpak-builder keeps its state in .flatpak-builder between runs, so libmpv and FFmpeg build only once.
docker run --rm --privileged -v "$PWD:/src" -w /src ghcr.io/flathub-infra/flatpak-github-actions:freedesktop-25.08 bash -c "
  set -e
  # The cache is chowned back to the host user after each run, so git, running as root here, would refuse its mirrors.
  export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=safe.directory GIT_CONFIG_VALUE_0='*'
  flatpak-builder --force-clean --disable-rofiles-fuse --repo=build/flatpak-repo \
    build/flatpak-app packaging/flatpak/io.github.zakius.finimal.yml
  flatpak build build/flatpak-app /app/bin/finimal --check-player
  flatpak build-bundle --runtime-repo=https://flathub.org/repo/flathub.flatpakrepo build/flatpak-repo dist/finimal-$version-x86_64.flatpak io.github.zakius.finimal
  chown -R $(id -u):$(id -g) build .flatpak-builder dist
"
ls -l "dist/finimal-$version-x86_64.flatpak"
