#!/bin/sh
# Starts finimal from @APPDIR@ (/opt/finimal in the deb and rpm) on the distribution's Java, 21 or newer: the newest
# installed full JDK or JRE, else `java` on the PATH. The options match jpackage's launcher (desktop/build.gradle.kts).
# The app's classes go into a class-data archive at the first start, kept with the app's cache: later starts take
# about a third less time. The JVM makes a new one after an update.
app=@APPDIR@
java=
for dir in /usr/lib/jvm/java-25-openjdk* /usr/lib/jvm/jre-25* /usr/lib/jvm/java-21-openjdk* /usr/lib/jvm/jre-21*; do
  if [ -x "$dir/bin/java" ]; then java="$dir/bin/java"; break; fi
done
[ -n "$java" ] || java=java
cache="${XDG_CACHE_HOME:-$HOME/.cache}/finimal"
mkdir -p "$cache"
exec "$java" --enable-native-access=ALL-UNNAMED -XX:SharedArchiveFile="$cache/finimal.jsa" -XX:+AutoCreateSharedArchive -Xlog:cds=off -Xlog:cds+dynamic=off \
  -Dcompose.application.resources.dir="$app/resources" -Dcompose.application.configure.swing.globals=true \
  -Dskiko.library.path="$app" -Dfinimal.version=@VERSION@ \
  -cp "$app/*" com.zakius.finimal.desktop.MainKt "$@"
