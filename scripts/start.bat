@echo off
rem Starts a Minecraft server with Singularity Loader.
rem Run it from your server folder (the one that contains server.jar), or copy it there.
rem Any argument is passed to the server, e.g.:  start.bat nogui
rem
rem Optional environment variables:
rem   JAVA       java executable
rem   JAVA_OPTS  memory and other JVM options (default: -Xms2G -Xmx4G; on a small machine give it about
rem              half of the RAM and make both values the same, e.g. -Xms2G -Xmx2G)
rem   GC_OPTS    garbage collector options (default: G1 tuned for Minecraft, see docs/PERFORMANCE.md;
rem              set GC_OPTS=-XX:+UseG1GC for the JVM's own defaults)
rem   LOADER     path to the loader jar

setlocal
if "%JAVA%"=="" set "JAVA=java"
if "%JAVA_OPTS%"=="" set "JAVA_OPTS=-Xms2G -Xmx4G"
if "%GC_OPTS%"=="" set "GC_OPTS=-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1"
if "%LOADER%"=="" set "LOADER=%~dp0singularity-loader.jar"

if not exist "%LOADER%" (
    echo Cannot find singularity-loader.jar at %LOADER% ^(set LOADER=C:\path\to\singularity-loader.jar^)
    exit /b 1
)

"%JAVA%" %JAVA_OPTS% %GC_OPTS% -jar "%LOADER%" %*
