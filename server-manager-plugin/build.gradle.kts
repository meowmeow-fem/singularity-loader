plugins {
    `java-library`
}

version = "1.0.0"
group = "net.singularitymc.servermanager"

dependencies {
    // A plugin compiles against the Singularity API jar and the loader jar only: no Minecraft, no Mixin.
    compileOnly(project(path = ":singularity-api", configuration = "pluginApi"))
    compileOnly(project(":")) { isTransitive = false }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(rootProject.property("javaRelease").toString().toInt())
}

tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching("plugin.toml") {
        expand("version" to project.version.toString())
    }
}

tasks.jar {
    // The plugin: goes into SingularityPlugins/.
    archiveBaseName.set("server-manager")
}
