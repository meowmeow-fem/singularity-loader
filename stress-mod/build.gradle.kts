plugins {
    `java-library`
}

// Test-only mod: fake players and tick-time statistics for load tests (docs/PERFORMANCE.md, "Measuring").
// Never part of the distribution. Written against 26.3.
version = rootProject.version
group = "net.singularitymc.stress"

val mcLibsDir = (findProperty("minecraftLibraries") as String?)?.let { rootProject.file(it) } ?: rootProject.file("run/GameLibraries")

dependencies {
    compileOnly(rootProject)
    compileOnly(files(rootProject.file("libs/Mojang_Mapping.jar")))
    compileOnly("net.fabricmc:sponge-mixin:0.17.2+mixin.0.8.7")
    compileOnly("org.jspecify:jspecify:1.0.0")
    compileOnly("org.jetbrains:annotations:26.0.2")
    compileOnly(fileTree(mcLibsDir) { include("**/*.jar") })
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
    archiveBaseName.set("singularity-stress")
}
