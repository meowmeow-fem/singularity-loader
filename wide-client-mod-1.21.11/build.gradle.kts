import java.util.Properties

plugins {
    // the newest Loom that runs on the repository's Gradle 9.6 (1.18+ need Gradle 9.8)
    id("fabric-loom") version "1.17.21"
}

// The 26.x mod's sources (wide-client-mod and the loader patches it shares), compiled against 1.21.11 with Mojang's
// names; Loom remaps the jar and the Mixin targets to intermediary.
val repoRoot = rootDir.parentFile
val loaderVersion = Properties().apply { repoRoot.resolve("gradle.properties").reader().use { load(it) } }
    .getProperty("loaderVersion")
version = loaderVersion
group = "net.singularitymc.wideclient"

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.18.4")
    compileOnly("org.jspecify:jspecify:1.0.0")
    compileOnly("org.jetbrains:annotations:26.0.2")
}

sourceSets.main {
    java {
        srcDir(repoRoot.resolve("wide-client-mod/src/main/java"))
        srcDir(repoRoot.resolve("src/main/java"))
        include(
            "net/singularitymc/wideclient/**",
            "net/singularitymc/loader/util/WidePacking.java",
            "net/singularitymc/loader/util/SafeMath.java",
            "net/singularitymc/loader/classloading/ClassTransformer.java",
            "net/singularitymc/loader/classloading/MidpointRewriter.java",
            "net/singularitymc/loader/util/ServerState.java",
            "net/singularitymc/loader/util/PatchSettings.java",
            "net/singularitymc/compat/GameShims.java",
            "net/singularitymc/loader/patches/LevelLimitMixin.java",
            "net/singularitymc/loader/patches/EntityLimitMixin.java",
            "net/singularitymc/loader/patches/PlayerLimitMixin.java",
            "net/singularitymc/loader/patches/WorldBorderLimitMixin.java",
            "net/singularitymc/loader/patches/WorldBorderSettingsLimitMixin.java",
            "net/singularitymc/loader/patches/BorderWarningMixin.java",
            "net/singularitymc/loader/patches/WorldBorderCommandLimitMixin.java",
            "net/singularitymc/loader/patches/ForceLoadLimitMixin.java",
            "net/singularitymc/loader/patches/PacketLimitMixin.java",
            "net/singularitymc/loader/patches/MinecraftServerLimitMixin.java",
            "net/singularitymc/loader/patches/wide/BlockPosWideMixin.java",
            "net/singularitymc/loader/patches/wide/SectionPosWideMixin.java",
            "net/singularitymc/loader/patches/wide/EntitySectionStorageWideMixin.java",
            "net/singularitymc/loader/patches/wide/ChunkPyramidWideMixin.java",
            "net/singularitymc/loader/patches/wide/ChunkPosWideMixin.java",
            "net/singularitymc/loader/patches/wide/FriendlyByteBufWideMixin.java"
        )
    }
    resources {
        srcDir(repoRoot.resolve("wide-client-mod/src/main/resources"))
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching("fabric.mod.json") {
        expand("version" to project.version.toString())
        // the same mod for 1.21.11 (Java 21)
        filter { it.replace("\">=26.1 <26.4\"", "\"~1.21.11\"").replace("\">=25\"", "\">=21\"") }
    }
}

tasks.remapJar {
    archiveBaseName.set("singularity-wide-client")
    archiveVersion.set("${project.version}+1.21.11")
}
