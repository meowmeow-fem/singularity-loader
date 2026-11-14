// Standalone build (Fabric Loom) of the Singularity Wide Client for Minecraft 1.21.11, which is obfuscated: the mod is
// compiled against Mojang's names and remapped to intermediary. Kept out of the main build so that one needs no Loom.
// Build from the repository root: ./gradlew -p wide-client-mod-1.21.11 build
pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "singularity-wide-client-1.21.11"
