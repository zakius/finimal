#!/bin/bash
# The desktop app for Debian/Ubuntu and Fedora: dist/finimal_<version>_amd64.deb and dist/finimal-<version>-1.x86_64.rpm,
# from the release app image's app files. Both install to /opt/finimal with /usr/bin/finimal and run on the
# distribution's Java 21+ and libmpv, so their fixes come with system updates (the Flatpak bundles its own).
# The jars go in stored, not deflated: the package's xz then compresses across all classes, 20 MB down to 9 MB.
# rpmbuild comes from the host (Ubuntu: the rpm package) or, without it, a Fedora container.
# Usage: tools/linux-packages.sh [--no-gradle]
set -euo pipefail
cd "$(dirname "$0")/.."
version=$(sed -n 's/^finimal.versionName=//p' gradle.properties)
[ "${1:-}" = --no-gradle ] || ./gradlew -q :desktop:createReleaseDistributable
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
root=$stage/root
mkdir -p "$root/opt" "$root/usr/bin" "$root/usr/share/applications" "$root/usr/share/icons/hicolor/scalable/apps" "$root/usr/share/metainfo" dist
app=$PWD/desktop/build/compose/binaries/main-release/app/finimal/lib/app
mkdir -p "$root/opt/finimal"
cp -a "$app"/libskiko-*.so "$root/opt/finimal/"
[ ! -d "$app/resources" ] || cp -a "$app/resources" "$root/opt/finimal/"
for jar in "$app"/*.jar; do
  rm -rf "$stage/x"; mkdir "$stage/x"; (cd "$stage/x" && unzip -q "$jar" && zip -q -0 -r -X "$root/opt/finimal/$(basename "$jar")" .)
done
rm -rf "$stage/x"
sed -e 's|@APPDIR@|/opt/finimal|' -e "s|@VERSION@|$version|" packaging/finimal-system-java.sh > "$root/usr/bin/finimal"
chmod 755 "$root/usr/bin/finimal"
install -m644 packaging/io.github.zakius.finimal.desktop "$root/usr/share/applications/"
install -m644 packaging/finimal.svg "$root/usr/share/icons/hicolor/scalable/apps/io.github.zakius.finimal.svg"
install -m644 packaging/io.github.zakius.finimal.metainfo.xml "$root/usr/share/metainfo/"

mkdir "$root/DEBIAN"
cat > "$root/DEBIAN/control" <<CONTROL
Package: finimal
Version: $version
Architecture: amd64
Maintainer: zakius <zakius@users.noreply.github.com>
Depends: java21-runtime, libmpv2, libgl1
Section: video
Priority: optional
Installed-Size: $(du -sk "$root" | cut -f1)
Homepage: https://github.com/zakius/finimal
Description: Jellyfin client for movies and TV
 Goes straight to your movie and TV libraries, keeps play state locally so series never restart from
 episode 1, buffers to the end of the episode on disk, and plays through mpv.
CONTROL
dpkg-deb --root-owner-group -Zxz -b "$root" "dist/finimal_${version}_amd64.deb" > /dev/null
rm -r "$root/DEBIAN"

cp packaging/finimal.spec "$stage/"
build="rpmbuild -bb --define '_topdir /tmp/rpmbuild' --define 'stage $root' --define 'version $version' $stage/finimal.spec && cp /tmp/rpmbuild/RPMS/x86_64/*.rpm $PWD/dist/"
if command -v rpmbuild > /dev/null; then bash -c "$build"
else
  docker run --rm -v "$stage:$stage" -v "$PWD/dist:$PWD/dist" fedora:latest bash -c \
    "dnf -q -y install rpm-build > /dev/null && $build && chown $(id -u):$(id -g) $PWD/dist/*.rpm"
fi
ls -l dist/finimal_"$version"_amd64.deb dist/finimal-"$version"-1.x86_64.rpm
