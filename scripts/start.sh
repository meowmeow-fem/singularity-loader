#!/usr/bin/env sh
# Starts a Minecraft server with Singularity Loader.
# Run it from your server folder (the one that contains server.jar), or copy it there.
# Any argument is passed to the server, e.g.:  ./start.sh nogui
#
# Environment variables:
#   JAVA       java executable to use (default: java)
#   JAVA_OPTS  memory and other JVM options (default: -Xms2G -Xmx4G; on a small machine give it about
#              half of the RAM and make both values the same, e.g. -Xms2G -Xmx2G)
#   GC_OPTS    garbage collector options (default: G1 tuned for Minecraft, see docs/PERFORMANCE.md;
#              GC_OPTS=-XX:+UseG1GC gives the JVM's own defaults)
#   LOADER     path to singularity-loader.jar (default: next to this script)

set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
JAVA="${JAVA:-java}"
JAVA_OPTS="${JAVA_OPTS:--Xms2G -Xmx4G}"
GC_OPTS="${GC_OPTS:--XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1}"
LOADER="${LOADER:-$HERE/singularity-loader.jar}"

if [ ! -f "$LOADER" ]; then
    echo "Cannot find singularity-loader.jar at $LOADER (set LOADER=/path/to/singularity-loader.jar)" >&2
    exit 1
fi

# shellcheck disable=SC2086
exec "$JAVA" $JAVA_OPTS $GC_OPTS -jar "$LOADER" "$@"
