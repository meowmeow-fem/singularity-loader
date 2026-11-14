rootProject.name = "singularity-loader"

include("example-mod")
include("totem-teleport-mod")
include("wide-client-mod")
// 4.0: the Singularity API mod (API for mods and plugins + the plugin loader) and an example plugin
include("singularity-api")
include("example-plugin")
// 4.2: test-only load-test mod (fake players, tick-time report); not part of the distribution
include("stress-mod")
