# Fedora package for the tree tools/linux-packages.sh stages. Java and libmpv come from Fedora, so their fixes arrive
# with system updates.
# xz like the .deb: Ubuntu's rpmbuild (used in CI) defaults to gzip, which made the package 40% bigger.
%global _binary_payload w9.xzdio

Name:           finimal
Version:        %{version}
Release:        1
Summary:        Jellyfin client for movies and TV
License:        GPL-3.0-only
URL:            https://github.com/zakius/finimal
ExclusiveArch:  x86_64
# Otherwise rpm lists every library Skia links against as a requirement; the full Java package already brings X11 and fonts.
AutoReqProv:    no
Requires:       (java-25-openjdk or java-21-openjdk or java-latest-openjdk)
Requires:       mpv-libs libglvnd-glx

%description
A Jellyfin client that goes straight to your movie and TV libraries. It keeps play state locally, so series never
restart from episode 1, buffers to the end of the episode on disk, and plays through mpv.

%install
cp -a %{stage}/. %{buildroot}/

%files
/opt/finimal
/usr/bin/finimal
/usr/share/applications/io.github.zakius.finimal.desktop
/usr/share/icons/hicolor/scalable/apps/io.github.zakius.finimal.svg
/usr/share/metainfo/io.github.zakius.finimal.metainfo.xml
