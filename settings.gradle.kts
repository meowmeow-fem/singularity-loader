rootProject.name = "singularity-loader"

include("example-mod")
include("totem-teleport-mod")
include("wide-client-mod")
// 4.0: the Singularity API mod (API for mods and plugins + the plugin loader) and an example plugin
include("singularity-api")
include("example-plugin")
// a real plugin written against the plugin API only: TPS, broadcasts, staff chat, maintenance mode, mutes, shutdown
include("server-manager-plugin")
// server-side anti-cheat (speed, fly, timer, reach, click rate) on the API's move and attack events (API 4.2)
include("anticheat-plugin")
// Fabric client mod: Right Shift = /dashboard, F12 = /anticheat gui
include("keys-client-mod")
// 4.2: test-only load-test mod (fake players, tick-time report); not part of the distribution
include("stress-mod")
