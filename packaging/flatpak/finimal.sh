#!/bin/sh
# The app's own classes go into a class-data archive at the first start, kept with the app's cache: later starts take
# about a third less time. The JVM makes a new one after an update.
export JAVA_TOOL_OPTIONS="-XX:SharedArchiveFile=${XDG_CACHE_HOME:-$HOME/.cache}/finimal.jsa -XX:+AutoCreateSharedArchive -Xlog:cds=off -Xlog:cds+dynamic=off"
exec /app/finimal/bin/finimal "$@"
