#!/bin/sh
# Starts finimal from @APPDIR@ (/opt/finimal in the deb and rpm) on the system's Java. It picks the newest installed
# Java 21 or later that has the desktop (X11) libraries, whatever its folder name, rather than whichever `java` is on
# the PATH (which can be older: 17 on one CI machine). The options match jpackage's launcher (desktop/build.gradle.kts).
# The first start writes the app's classes to a class-data archive in the cache, which makes later starts about a third
# faster. The JVM rebuilds the archive after an update.
app=@APPDIR@
java=
best=0
path_java=$(command -v java 2>/dev/null) && path_home=$(dirname "$(dirname "$(readlink -f "$path_java")")")
for home in /usr/lib/jvm/* ${path_home:+"$path_home"}; do
  [ -x "$home/bin/java" ] && [ -e "$home/lib/libawt_xawt.so" ] || continue
  version=$(sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$home/release" 2>/dev/null)
  if [ -n "$version" ] && [ "$version" -ge 21 ] && [ "$version" -gt "$best" ]; then java="$home/bin/java"; best=$version; fi
done
if [ -z "$java" ]; then
  echo "finimal needs Java 21 or newer, with the desktop part (not a headless runtime)." >&2
  exit 1
fi
cache="${XDG_CACHE_HOME:-$HOME/.cache}/finimal"
mkdir -p "$cache"
exec "$java" --enable-native-access=ALL-UNNAMED -XX:SharedArchiveFile="$cache/finimal.jsa" -XX:+AutoCreateSharedArchive -Xlog:cds=off -Xlog:cds+dynamic=off \
  -Dcompose.application.resources.dir="$app/resources" -Dcompose.application.configure.swing.globals=true \
  -Dskiko.library.path="$app" -Dfinimal.version=@VERSION@ \
  -cp "$app/*" com.zakius.finimal.desktop.MainKt "$@"
