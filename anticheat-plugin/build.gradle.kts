plugins {
    `java-library`
}

version = "1.0.0"
group = "net.singularitymc.anticheat"

dependencies {
    // A plugin compiles against the Singularity API jar and the loader jar only: no Minecraft, no Mixin.
    compileOnly(project(path = ":singularity-api", configuration = "pluginApi"))
    compileOnly(project(":")) { isTransitive = false }

    // The checks themselves (Checks.java) are plain arithmetic and are unit-tested without a server.
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(rootProject.property("javaRelease").toString().toInt())
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching("plugin.toml") {
        expand("version" to project.version.toString())
    }
}

tasks.jar {
    // The plugin: goes into SingularityPlugins/.
    archiveBaseName.set("singularity-anticheat")
}
