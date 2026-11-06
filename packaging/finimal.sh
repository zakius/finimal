#!/bin/sh
# Starts finimal from the jpackage image at @APPDIR@ (/app/finimal in the Flatpak).
# The first start writes the app's classes to a class-data archive in the cache, which makes later starts about a third
# faster. The JVM rebuilds the archive after an update.
export JAVA_TOOL_OPTIONS="-XX:SharedArchiveFile=${XDG_CACHE_HOME:-$HOME/.cache}/finimal/finimal.jsa -XX:+AutoCreateSharedArchive -Xlog:cds=off -Xlog:cds+dynamic=off"
mkdir -p "${XDG_CACHE_HOME:-$HOME/.cache}/finimal"
exec @APPDIR@/bin/finimal "$@"
