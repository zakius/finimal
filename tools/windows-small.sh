#!/bin/bash
# The small Windows build, dist/finimal-<version>-x64.msi and dist/finimal-<version>-windows-x64.zip: the full build's
# app without its Java runtime and libmpv, started by packaging/windows/Launcher.cs, which finds both on the machine or
# downloads them once. The image stays in desktop/build/windows-small for CI's checks.
# The JRE is pinned here. The libmpv is the full build's (tools/fetch-libmpv-windows.sh), zipped and published once per
# libmpv build as a release of its own, libmpv-<build>, which needs gh with write access: a pull request that bumps
# libmpv fails here until the bump reaches main.
# Runs on Windows (Git Bash) after :desktop:packageReleaseMsi :desktop:createReleaseDistributable, which leave the
# app image and the WiX toolset jpackage needs.
# Usage: tools/windows-small.sh
set -euo pipefail
cd "$(dirname "$0")/.."
jre_url=https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jre_x64_windows_hotspot_21.0.12.1_1.zip
jre_sha256=d35f31e712f0fcf6ac5a093edc90204fbff22f720ba3950bd09d331d5e621636
jre_dir=jdk-21.0.12.1+1-jre
version=$(sed -n 's/^finimal.versionName=//p' gradle.properties)
build=$(sed -n 's/^build=//p' tools/fetch-libmpv-windows.sh)
tag=libmpv-$build
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# Every small build of this libmpv downloads the zip published first, so its hash is the one to pin.
mpv=$tmp/libmpv-2-x64.zip
if ! gh release download "$tag" -p libmpv-2-x64.zip -D "$tmp" 2> /dev/null; then
  (cd desktop/resources/windows && 7z a -tzip "$mpv" libmpv-2.dll > /dev/null)
  gh release create "$tag" "$mpv" --title "libmpv $build for Windows" --prerelease --latest=false \
    --notes "The libmpv-2.dll that finimal's small Windows build downloads on its first start: shinchiro's mpv-winbuild-cmake build $build." \
    || gh release download "$tag" -p libmpv-2-x64.zip -D "$tmp" --clobber # another run published it first
fi
mpv_sha256=$(sha256sum "$mpv" | cut -d' ' -f1)

cat > "$tmp/Pins.cs" <<PINS
static class Pins
{
    public const string JreUrl = "$jre_url";
    public const string JreSha256 = "$jre_sha256";
    public const string JreDir = "$jre_dir";
    public const string MpvUrl = "https://github.com/zakius/finimal/releases/download/$tag/libmpv-2-x64.zip";
    public const string MpvSha256 = "$mpv_sha256";
}
PINS
/c/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe -nologo -optimize -target:winexe \
  -out:"$(cygpath -w "$tmp/finimal.exe")" -win32icon:"$(cygpath -w packaging/finimal.ico)" \
  -r:System.IO.Compression.dll -r:System.IO.Compression.FileSystem.dll -r:System.Windows.Forms.dll -r:System.Drawing.dll \
  "$(cygpath -w packaging/windows/Launcher.cs)" "$(cygpath -w "$tmp/Pins.cs")"

small=desktop/build/windows-small/finimal
rm -rf "$small"
mkdir -p "$(dirname "$small")" dist
cp -r desktop/build/compose/binaries/main-release/app/finimal "$small"
rm -rf "$small/runtime"
find "$small" -name libmpv-2.dll -delete
rm -f "$small/finimal.exe" # jpackage's launcher, read-only
cp "$tmp/finimal.exe" "$small/finimal.exe"
(cd "$(dirname "$small")" && 7z a -tzip "$OLDPWD/dist/finimal-$version-windows-x64.zip" finimal > /dev/null)

# The installer around it, as packageReleaseMsi makes the full one (desktop/build.gradle.kts): the same upgrade code,
# so either build replaces the other.
candle=$(find desktop/build build ~/AppData/Local -name candle.exe 2> /dev/null | head -n 1)
[ -n "$candle" ] || { echo "No WiX toolset (candle.exe) found" >&2; exit 1; }
PATH="$(dirname "$candle"):$PATH" "$JAVA_HOME/bin/jpackage" --type msi --app-image "$(cygpath -w "$small")" \
  --name finimal --app-version "$version" --vendor zakius --description "Jellyfin client for movies and TV" \
  --license-file LICENSE --win-per-user-install --win-menu --win-menu-group finimal --win-shortcut \
  --win-upgrade-uuid 3f7c2a4e-8b1d-4e6a-9c2f-5d8e1a7b4c90 --dest "$(cygpath -w "$tmp/msi")"
cp "$tmp"/msi/*.msi "dist/finimal-$version-x64.msi"
ls -l "dist/finimal-$version-x64.msi" "dist/finimal-$version-windows-x64.zip"
