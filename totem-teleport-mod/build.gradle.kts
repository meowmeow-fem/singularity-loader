plugins {
    `java-library`
}

version = rootProject.version
group = "net.singularitymc.totemtp"

// Same folders as the loader itself; -PminecraftLibraries is relative to the root project, not to this one.
val mcLibsDir = (findProperty("minecraftLibraries") as String?)?.let { rootProject.file(it) } ?: rootProject.file("run/GameLibraries")

dependencies {
    compileOnly(rootProject)
    // This mod touches Minecraft classes (Mojang names), so compile against them. Never bundled.
    compileOnly(files(rootProject.file("libs/Mojang_Mapping.jar")))
    compileOnly("org.jspecify:jspecify:1.0.0")
    compileOnly("org.jetbrains:annotations:26.0.2")
    compileOnly(fileTree(mcLibsDir) { include("**/*.jar") })
    // Filled by ./gradlew setupClientLibs; brings the same libraries when run/GameLibraries does not exist yet.
    compileOnly(fileTree(rootProject.file("libs/client")) { include("**/*.jar") })
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(rootProject.property("javaRelease").toString().toInt())
    options.compilerArgs.add("-proc:none")
}

tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching("singularity.mod.json") {
        expand("version" to project.version.toString())
    }
}

tasks.jar {
    archiveBaseName.set("totem-teleport")
}
