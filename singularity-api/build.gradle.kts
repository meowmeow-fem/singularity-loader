plugins {
    `java-library`
}

// The Singularity API mod: the API for mods and plugins (package net.singularitymc.sapi) plus the plugin loader that
// reads SingularityPlugins/. It has its own version: plugins name it in plugin.toml (api = ">=4.0.0").
version = "4.4.0"
group = "net.singularitymc.sapi"

// Same folders as the loader itself; -PminecraftLibraries is relative to the root project, not to this one.
val mcLibsDir = (findProperty("minecraftLibraries") as String?)?.let { rootProject.file(it) } ?: rootProject.file("run/GameLibraries")

dependencies {
    compileOnly(rootProject)
    // The bridge to the game (net.singularitymc.sapi.internal.mc) uses Minecraft classes (Mojang names). Never bundled.
    compileOnly(files(rootProject.file("libs/Mojang_Mapping.jar")))
    compileOnly("org.jspecify:jspecify:1.0.0")
    compileOnly("org.jetbrains:annotations:26.0.2")
    compileOnly(fileTree(mcLibsDir) { include("**/*.jar") })
    compileOnly(fileTree(rootProject.file("libs/client")) { include("**/*.jar") })

    // Unit tests cover the parts without Minecraft types (plugin loading, TOML, scheduler, commands, coordinates).
    testImplementation(rootProject)
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(rootProject.property("javaRelease").toString().toInt())
    options.compilerArgs.addAll(listOf("-proc:none", "-Xlint:deprecation"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching(listOf("singularity.mod.json", "net/singularitymc/sapi/internal/api.properties")) {
        expand("version" to project.version.toString())
    }
}

tasks.jar {
    // The mod: goes into SingularityMods/.
    archiveBaseName.set("singularity-api")
}

// What plugin authors compile against: only the public API (no Minecraft types, no internals, no Mixins).
val apiJar = tasks.register<Jar>("apiJar") {
    group = "singularity"
    description = "Jar with only the public Singularity API, for compiling plugins"
    archiveBaseName.set("singularity-api")
    archiveClassifier.set("api")
    from(sourceSets.main.get().output) {
        include("net/singularitymc/sapi/**")
        exclude("net/singularitymc/sapi/internal/**")
    }
}

val pluginApi by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(pluginApi.name, apiJar)

tasks.assemble {
    dependsOn(apiJar)
}
