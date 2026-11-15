import groovy.json.JsonSlurper
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    `java-library`
    distribution
}

val loaderVersion: String by project
val javaRelease: String by project

// Building, in three steps (no downloads in Gradle, the loader fetches Minecraft itself):
//   1. ./gradlew installDist -PbootstrapOnly        loader without the Minecraft patches (compiles with nothing else)
//   2. run it once in ./run (./gradlew runServer -PbootstrapOnly -PsetupOnly): it downloads Mojang's
//      server and fills run/GameLibraries
//   3. ./gradlew distZip                            the real build, compiled against run/GameLibraries
// -PminecraftLibraries=<folder> points step 3 at the GameLibraries folder of any other server.
val bootstrapOnly = project.hasProperty("bootstrapOnly")
// Without run/GameLibraries the libraries from libs/client (./gradlew setupClientLibs) are used; see dependencies.
val mcLibsDir: File = (findProperty("minecraftLibraries") as String?)?.let { file(it) } ?: file("run/GameLibraries")

group = "net.singularitymc"
version = loaderVersion

allprojects {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/") {
            name = "FabricMC"
            content {
                includeGroup("net.fabricmc")
                includeGroup("io.github.llamalad7")
            }
        }
    }
}

java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(javaRelease.toInt())
    // Mixin ships an annotation processor; it must never run on the loader itself.
    options.compilerArgs.addAll(listOf("-proc:none", "-Xlint:deprecation"))
}

dependencies {
    // Minecraft classes with Mojang mappings (local jar). compileOnly: not bundled into the distribution.
    compileOnly(files("libs/Mojang_Mapping.jar"))
    // Minecraft's classes are annotated with these; javac 25 crashes if they are missing from the class path.
    compileOnly("org.jspecify:jspecify:1.0.0")
    compileOnly("org.jetbrains:annotations:26.0.2")
    // Minecraft's own libraries (fastutil, DataFixerUpper, Guava, Brigadier, ...). javac needs them to read Minecraft's
    // classes. They come from the GameLibraries folder of a server that has started once (see "Building" below).
    compileOnly(fileTree(mcLibsDir) { include("**/*.jar") })
    // Client preview: the client jar and its libraries, filled by ./gradlew setupClientLibs (empty folder = nothing added).
    compileOnly(fileTree("libs/client") { include("**/*.jar") })
    // Mixin (FabricMC's maintained fork of SpongePowered Mixin).
    api("net.fabricmc:sponge-mixin:0.17.2+mixin.0.8.7")
    // ASM 9.9 is needed to read the Java 25 class files shipped with Minecraft 26.x.
    api("org.ow2.asm:asm:9.9")
    api("org.ow2.asm:asm-tree:9.9")
    api("org.ow2.asm:asm-commons:9.9")
    api("org.ow2.asm:asm-util:9.9")
    api("org.ow2.asm:asm-analysis:9.9")
    // MixinExtras (@ModifyExpressionValue, @WrapOperation, @Local, ...). This is the artifact the
    // loader is tested with; it has no runtime dependency on Fabric Loader.
    api("io.github.llamalad7:mixinextras-fabric:0.5.5")
    // Used by the loader to read singularity.mod.json (the game brings its own, separate copy).
    implementation("com.google.code.gson:gson:2.14.0")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

if (!bootstrapOnly) {
    // Fail with a hint instead of hundreds of "class file for ... not found" errors.
    tasks.compileJava {
        doFirst {
            val hasLibs = listOf(mcLibsDir, file("libs/client")).any { dir ->
                dir.walkTopDown().any { it.isFile && it.name.endsWith(".jar") && it.name.startsWith("fastutil") }
            }
            if (!hasLibs) {
                throw GradleException(
                    "Minecraft's libraries were not found in ${mcLibsDir.path} or libs/client. Either run " +
                        "'./gradlew runServer -PbootstrapOnly -PsetupOnly' once, or './gradlew setupClientLibs', " +
                        "or pass -PminecraftLibraries=<GameLibraries folder of a server>."
                )
            }
        }
    }
}

if (bootstrapOnly) {
    // The patches and the events (they use Minecraft types) need Minecraft's classes to compile; the bootstrap loader is built without them.
    sourceSets.main { java.exclude("net/singularitymc/loader/patches/**", "net/singularitymc/event/**") }
}

tasks.processResources {
    if (bootstrapOnly) {
        val generated = layout.buildDirectory.dir("generated/bootstrap-resources")
        exclude("singularity-loader.mixins.json", "singularity-loader.wide.mixins.json")
        from(generated)
        doFirst {
            val dir = generated.get().asFile
            dir.mkdirs()
            dir.resolve("singularity-loader.mixins.json").writeText(
                """{"required": true, "minVersion": "0.8", "package": "net.singularitymc.loader.patches", "compatibilityLevel": "JAVA_21", "mixins": []}"""
            )
            dir.resolve("singularity-loader.wide.mixins.json").writeText(
                """{"required": true, "minVersion": "0.8", "package": "net.singularitymc.loader.patches.wide", "compatibilityLevel": "JAVA_21", "mixins": []}"""
            )
        }
    }
    inputs.property("loaderVersion", loaderVersion)
    filesMatching("net/singularitymc/loader/loader.properties") {
        expand("version" to loaderVersion)
    }
}

tasks.jar {
    archiveBaseName.set("singularity-loader")
    archiveFileName.set("singularity-loader.jar") // start.bat / start.sh look for exactly this name
    manifest {
        attributes(
            "Main-Class" to "net.singularitymc.loader.Main",
            "Implementation-Title" to "Singularity Loader",
            "Implementation-Version" to loaderVersion,
            "Implementation-Vendor" to "Singularity Project",
            // The distribution puts every dependency next to the jar in lib/.
            "Class-Path" to configurations.runtimeClasspath.get().joinToString(" ") { "lib/${it.name}" }
        )
    }
}

distributions {
    main {
        distributionBaseName.set("singularity-loader")
        contents {
            from(tasks.jar)
            into("lib") { from(configurations.runtimeClasspath) }
            from("scripts") { filePermissions { unix("rwxr-xr-x") } }
            from("LICENSE", "NOTICE", "README.md")
        }
    }
}

// ./gradlew releaseZip    the loader package (as distZip) with the Singularity API mod and the Server Manager,
// AntiCheat and Auth plugins already in their folders, plus the Fabric client mods in client-mods/ (those go into a
// player's .minecraft/mods, not onto the server). Needs libs/client (./gradlew setupClientLibs) for the client mods.
tasks.register<Zip>("releaseZip") {
    group = "distribution"
    description = "Server package with the API mod and plugins installed, and the client mods"
    val serverJars = mapOf(
        ":singularity-api" to "SingularityMods",
        ":server-manager-plugin" to "SingularityPlugins",
        ":anticheat-plugin" to "SingularityPlugins",
        ":auth-plugin" to "SingularityPlugins",
        ":keys-client-mod" to "client-mods",
        ":wide-client-mod" to "client-mods"
    )
    dependsOn(serverJars.keys.map { "$it:jar" })
    archiveBaseName.set("singularity-server")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    val top = "singularity-server-$loaderVersion"
    into(top) {
        from(tasks.jar)
        into("lib") { from(configurations.runtimeClasspath) }
        from("scripts") { filePermissions { unix("rwxr-xr-x") } }
        from("LICENSE", "NOTICE", "README.md")
        serverJars.forEach { (path, folder) ->
            into(folder) { from(provider { project(path).tasks.getByName("jar") }) }
        }
    }
}

// ./gradlew runServer                  starts the loader from source in ./run (the server jar is downloaded on first start)
// ./gradlew runServer -PwithExample    additionally loads the example mod
tasks.register<JavaExec>("runServer") {
    group = "singularity"
    description = "Runs the loader from source in ./run (downloads the Minecraft server on first start)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("net.singularitymc.loader.Main")
    val runDir = layout.projectDirectory.dir("run").asFile
    workingDir = runDir
    standardInput = System.`in`
    // -PsetupOnly: only download and unpack Minecraft (fills run/GameLibraries), then exit
    if (project.hasProperty("setupOnly")) args("--singularity-setup") else args("nogui")
    doFirst { runDir.mkdirs() }
    if (project.hasProperty("withExample")) {
        dependsOn(":example-mod:jar")
        doFirst {
            val exampleJar = project(":example-mod").tasks.getByName("jar") as Jar
            systemProperty("singularity.addMods", exampleJar.archiveFile.get().asFile.absolutePath)
        }
    }
}

// ./gradlew setupClientLibs [-PmcVersion=26.3]
//   Client preview only. Downloads Mojang's client jar and the client libraries that apply to THIS operating system
//   (LWJGL, Netty, JOML, ...; read from the version JSON, with its OS rules) into libs/client/. They are compile-only
//   (never bundled). Skips files that are already there and match their SHA-1. No assets are downloaded.
tasks.register("setupClientLibs") {
    group = "singularity"
    description = "Downloads the Minecraft client jar and its libraries for this OS into libs/client (compile-only)"
    doLast {
        val version = (findProperty("mcVersion") as String?) ?: "26.3"
        val osName = System.getProperty("os.name").lowercase().let { if (it.contains("win")) "windows" else if (it.contains("mac")) "osx" else "linux" }
        val arch = System.getProperty("os.arch").lowercase()
        val manifest = JsonSlurper().parse(URI("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json").toURL()) as Map<*, *>
        val entry = (manifest["versions"] as List<*>).map { it as Map<*, *> }.firstOrNull { it["id"] == version }
            ?: throw GradleException("Minecraft version '$version' not found in Mojang's version manifest")
        val versionJson = JsonSlurper().parse(URI(entry["url"] as String).toURL()) as Map<*, *>

        fun sha1(f: File): String {
            val md = MessageDigest.getInstance("SHA-1")
            f.inputStream().use { ins -> val buf = ByteArray(65536); while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        fun fetch(url: String, target: File, expectedSha1: String?): Boolean {
            if (target.isFile && (expectedSha1 == null || sha1(target) == expectedSha1)) return false
            target.parentFile.mkdirs()
            URI(url).toURL().openStream().use { Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            if (expectedSha1 != null && sha1(target) != expectedSha1) throw GradleException("SHA-1 mismatch for $url")
            return true
        }
        // Mojang's library rules: no rules = allowed; otherwise the last matching rule decides
        fun applies(lib: Map<*, *>): Boolean {
            val rules = lib["rules"] as List<*>? ?: return true
            var allowed = false
            for (r in rules) {
                val rule = r as Map<*, *>
                val os = rule["os"] as Map<*, *>?
                val osMatches = os == null || ((os["name"] == null || os["name"] == osName) &&
                    (os["arch"] == null || (os["arch"] == "x86" && (arch == "x86" || arch == "i386")) || (os["arch"] == "arm64" && (arch == "aarch64" || arch == "arm64"))))
                if (osMatches) allowed = rule["action"] == "allow"
            }
            return allowed
        }

        val out = file("libs/client")
        val clientDl = ((versionJson["downloads"] as Map<*, *>)["client"]) as Map<*, *>
        logger.lifecycle("Client jar ${version}")
        fetch(clientDl["url"] as String, out.resolve("client-$version.jar"), clientDl["sha1"] as String?)
        var downloaded = 0
        var total = 0
        for (l in versionJson["libraries"] as List<*>) {
            val lib = l as Map<*, *>
            if (!applies(lib)) continue
            val artifact = (lib["downloads"] as Map<*, *>?)?.get("artifact") as Map<*, *>? ?: continue
            total++
            if (fetch(artifact["url"] as String, out.resolve("libraries/" + File(artifact["path"] as String).name), artifact["sha1"] as String?)) downloaded++
        }
        logger.lifecycle("Client libraries for $osName: $total ($downloaded downloaded) in ${out.absolutePath}/libraries")
    }
}
