plugins {
    `java-library`
}

// Fabric client mod that lets players go past 30,000,000 blocks on a Singularity server (docs/COORDINATES.md,
// "Client mod"). Minecraft 26.x is not obfuscated, so the mod is a plain jar of Mixins compiled against the client
// jar: no Fabric Loom, no Fabric API. The coordinate patches are the loader's own source files, compiled in here too.
version = rootProject.version
group = "net.singularitymc.wideclient"

// -PwideClientJar=<client jar> compiles against another 26.x client (to check the mod still fits it).
val clientJar = (findProperty("wideClientJar") as String?)?.let { file(it) } ?: rootProject.file("libs/client/client-26.3.jar")

sourceSets.main {
    java {
        srcDir(rootProject.file("src/main/java"))
        include(
            "net/singularitymc/wideclient/**",
            "net/singularitymc/loader/util/WidePacking.java",
            "net/singularitymc/loader/util/SafeMath.java",
            "net/singularitymc/loader/classloading/ClassTransformer.java",
            "net/singularitymc/loader/classloading/MidpointRewriter.java",
            "net/singularitymc/loader/util/ServerState.java",
            "net/singularitymc/loader/util/PatchSettings.java",
            // calls whose bytecode differs between 26.1-26.2 and 26.3 (EntitySectionStorageWideMixin)
            "net/singularitymc/compat/GameShims.java",
            // Stage 1: the hard-coded 30M limits (the client has the same ones, e.g. the Player.tick clamp).
            "net/singularitymc/loader/patches/LevelLimitMixin.java",
            "net/singularitymc/loader/patches/EntityLimitMixin.java",
            "net/singularitymc/loader/patches/PlayerLimitMixin.java",
            "net/singularitymc/loader/patches/WorldBorderLimitMixin.java",
            "net/singularitymc/loader/patches/WorldBorderSettingsLimitMixin.java",
            // Integrated server (singleplayer, LAN)
            "net/singularitymc/loader/patches/BorderWarningMixin.java",
            "net/singularitymc/loader/patches/WorldBorderCommandLimitMixin.java",
            "net/singularitymc/loader/patches/ForceLoadLimitMixin.java",
            "net/singularitymc/loader/patches/PacketLimitMixin.java",
            "net/singularitymc/loader/patches/MinecraftServerLimitMixin.java",
            // Stage 2: wide packing (PacketDecoderWideMixin is replaced by the client's own decoder patch)
            "net/singularitymc/loader/patches/wide/BlockPosWideMixin.java",
            "net/singularitymc/loader/patches/wide/SectionPosWideMixin.java",
            "net/singularitymc/loader/patches/wide/EntitySectionStorageWideMixin.java",
            "net/singularitymc/loader/patches/wide/ChunkPyramidWideMixin.java",
            "net/singularitymc/loader/patches/wide/ChunkPosWideMixin.java",
            "net/singularitymc/loader/patches/wide/FriendlyByteBufWideMixin.java"
        )
    }
}

dependencies {
    compileOnly(files(clientJar))
    compileOnly(fileTree(rootProject.file("libs/client/libraries")) { include("*.jar") })
    compileOnly("net.fabricmc:sponge-mixin:0.17.2+mixin.0.8.7")
    compileOnly("org.ow2.asm:asm:9.9")
    compileOnly("org.ow2.asm:asm-tree:9.9")
    compileOnly("org.jspecify:jspecify:1.0.0")
    compileOnly("org.jetbrains:annotations:26.0.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(rootProject.property("javaRelease").toString().toInt())
    options.compilerArgs.add("-proc:none")
    doFirst {
        if (!clientJar.isFile) {
            throw GradleException("wide-client-mod needs ${clientJar.path}; run './gradlew setupClientLibs' once.")
        }
    }
}

tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching("fabric.mod.json") {
        expand("version" to project.version.toString())
    }
}

tasks.jar {
    archiveBaseName.set("singularity-wide-client")
    from(rootProject.file("LICENSE"))
}
