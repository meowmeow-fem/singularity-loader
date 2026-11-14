plugins {
    `java-library`
}

// Fabric client mod with two keys for a Singularity server that runs the Server Manager and AntiCheat plugins:
// Right Shift opens the server dashboard, F12 the anti-cheat dashboard. The dashboards themselves are chest windows
// made by the server, so this mod only sends "/dashboard" and "/anticheat gui"; without it, type the commands.
// Minecraft 26.x is not obfuscated: a plain jar with one Mixin, compiled against the client jar (no Loom, no Fabric API).
version = "1.0.0"
group = "net.singularitymc.keys"

val clientJar = (findProperty("wideClientJar") as String?)?.let { file(it) } ?: rootProject.file("libs/client/client-26.3.jar")

dependencies {
    compileOnly(files(clientJar))
    compileOnly(fileTree(rootProject.file("libs/client/libraries")) { include("*.jar") })
    compileOnly("net.fabricmc:sponge-mixin:0.17.2+mixin.0.8.7")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(rootProject.property("javaRelease").toString().toInt())
    options.compilerArgs.add("-proc:none")
    doFirst {
        if (!clientJar.isFile) {
            throw GradleException("keys-client-mod needs ${clientJar.path}; run './gradlew setupClientLibs' once.")
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
    archiveBaseName.set("singularity-keys")
    from(rootProject.file("LICENSE"))
}
