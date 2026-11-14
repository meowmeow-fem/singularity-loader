plugins {
    `java-library`
}

version = rootProject.version
group = "net.singularitymc.example"

java {
    // The example mod only needs the loader API and Mixin to compile; it never needs Minecraft's
    // classes because its Mixin targets are addressed by name.
}

dependencies {
    compileOnly(rootProject)
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
    archiveBaseName.set("example-mod")
}
