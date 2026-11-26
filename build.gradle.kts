import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipFile

plugins {
    java
    `maven-publish`
    alias(libs.plugins.shadow)
    alias(libs.plugins.modrinthMinotaur)
}

group = "gg.moonrise.hawkeye"
description = "Hawkeye Paper plugin"

// Versions are year.quarter (docs/knowledge/README.md "Release facts"), and paper-plugin.yml, /he help and the
// startup line all show this one string:
//   stable release   26.4      -Pversion from the GitHub release tag (CI); a second release that quarter is 26.4.1
//   Jenkins build    26.4.B25  the quarter of the commit plus Jenkins' BUILD_NUMBER
//   any other build  26.4-SNAPSHOT
// The quarter comes from the commit date (UTC), so rebuilding an old commit gives it the same version.
val versionPattern = Regex("""\d{2}\.[1-4](\.\d+|\.B\d+|-SNAPSHOT)?""")
if (version == Project.DEFAULT_VERSION) {
    val commitDate = providers.exec {
        commandLine("git", "log", "-1", "--format=%cd", "--date=format-local:%Y-%m-%d")
        environment("TZ", "UTC")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
    val date = if (commitDate.isEmpty()) LocalDate.now(ZoneOffset.UTC) else LocalDate.parse(commitDate)
    val quarter = "%02d.%d".format(date.year % 100, (date.monthValue - 1) / 3 + 1)
    val jenkinsBuild = providers.environmentVariable("BUILD_NUMBER").orNull?.takeIf { it.isNotBlank() }
    version = if (jenkinsBuild != null) "$quarter.B$jenkinsBuild" else "$quarter-SNAPSHOT"
}
require(versionPattern.matches(version.toString())) {
    "Version '$version' is not year.quarter (26.4), year.quarter.hotfix (26.4.1), a Jenkins build (26.4.B25) " +
        "or a snapshot (26.4-SNAPSHOT)"
}

val pluginName = "Hawkeye"
val basePackage = "gg.moonrise.hawkeye"

// One jar for Paper 1.21.1 to 26.3 (docs/knowledge/README.md "Version support"). Paper 1.21.x runs on Java 21, so
// every class in the jar is Java 21 bytecode; Gradle, javac and the tests run on the Java 25 toolchain.
val pluginRelease = 21
// paper-plugin.yml's api-version: the Minecraft version of the oldest API main compiles against.
val oldestMinecraft = libs.versions.paperOldest.get().substringBefore("-")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Classes that need a newer Paper API than the oldest supported one. They compile against the newest API, ship in
// the same jar, and each carries util.compat.ConditionalOnApi, so Spring registers it only on a server that has the
// classes it names (on an older one the class is never loaded); a fallback in main carries ConditionalOnMissingApi.
val newerApi: SourceSet = sourceSets.create("newerApi") {
    compileClasspath += sourceSets.main.get().output
}
sourceSets.test {
    compileClasspath += newerApi.output
    runtimeClasspath += newerApi.output
}
// paper-api 26.3 declares Java 25; newerApi still compiles for Java 21 (--release) and uses no newer JDK API.
configurations.named(newerApi.compileClasspathConfigurationName) {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
}

dependencies {
    // Moonrise plugin-engine: shaded and relocated (see shadowJar). Its runtime libraries (Spring, Moss,
    // Cloud, ConfigLib) are resolved by the engine's PaperPluginLoader at server start, so compileOnly here.
    implementation(libs.engine)
    compileOnly(libs.bundles.engineRuntime)
    // main compiles against the oldest supported API, so it can use nothing newer; newerApi and the tests against
    // the newest.
    compileOnly(libs.paperOldest)
    // Hawkeye's own runtime libraries: resolved by HawkeyePluginLoader at server start, never shaded.
    compileOnly(libs.bundles.hawkeyeRuntime)
    // The error log's log4j filter: the server's log4j-core, as the oldest supported Paper ships it.
    compileOnly(libs.log4jCore)

    "newerApiCompileOnly"(libs.paper)
    "newerApiCompileOnly"(libs.engine)
    "newerApiCompileOnly"(libs.bundles.engineRuntime)
    "newerApiCompileOnly"(libs.bundles.hawkeyeRuntime)

    // java-development skill: Lombok and @NotNull/@Nullable in main and test code.
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    compileOnly(libs.jetbrainsAnnotations)
    "newerApiCompileOnly"(libs.lombok)
    "newerApiAnnotationProcessor"(libs.lombok)
    "newerApiCompileOnly"(libs.jetbrainsAnnotations)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
    testCompileOnly(libs.jetbrainsAnnotations)

    testImplementation(libs.paper)
    testImplementation(libs.bundles.engineRuntime)
    testImplementation(libs.bundles.hawkeyeRuntime)
    testImplementation(libs.mockbukkit)
    // A real log4j behind SLF4J, so the tests log through the error log's filter as a server does.
    testImplementation(libs.log4jCoreTest)
    testRuntimeOnly(libs.log4jSlf4jTest)
    testImplementation(libs.archunit)
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // -classfile: log4j-core's class files carry SpotBugs and bnd annotations whose classes it does not ship.
        options.compilerArgs.add("-Xlint:all,-processing,-classfile")
    }
    compileJava {
        options.release.set(pluginRelease)
    }
    named<JavaCompile>(newerApi.compileJavaTaskName) {
        options.release.set(pluginRelease)
    }
    withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }
    jar {
        enabled = false
    }
    build {
        dependsOn(shadowJar)
    }
    processResources {
        val props = mapOf("version" to project.version.toString())
        inputs.properties(props)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }
    test {
        useJUnitPlatform()
        // The opt-in storage benchmarks hold whole segments of events in memory: HAWKEYE_TEST_HEAP=3g.
        providers.environmentVariable("HAWKEYE_TEST_HEAP").orNull?.let { heap -> maxHeapSize = heap }
        // MockBukkit builds a Bukkit LibraryLoader on every registerEvents call, and its Maven resolver asks
        // InetAddress.getLocalHost(). On macOS a host name missing from /etc/hosts makes each lookup wait 5 s
        // for mDNS, so the test JVM resolves names from this file only (docs/knowledge "Environment facts").
        val hosts = layout.projectDirectory.file("src/test/hosts")
        inputs.file(hosts).withPropertyName("hosts").withPathSensitivity(PathSensitivity.NONE)
        systemProperty("jdk.net.hosts.file", hosts.asFile.absolutePath)
        // HawkeyePluginLoaderTest compares the loader's coordinates with the catalog.
        val runtimeLibraries = listOf(libs.hikari, libs.sqlite, libs.mariadb, libs.postgresql, libs.caffeine,
            libs.lettuce, libs.zstd).joinToString(",") { library -> library.get().toString() }
        inputs.property("hawkeyeRuntimeLibraries", runtimeLibraries)
        systemProperty("hawkeye.runtime.libraries", runtimeLibraries)
        // ApiConditionTest checks that every newerApi class is gated.
        systemProperty("hawkeye.newerApi.classes", newerApi.output.classesDirs.asPath)
    }
    // The shipped jar: build/libs/<pluginName>-<version>.jar (Hawkeye-26.4.jar, Hawkeye-26.4.B12.jar) with our
    // classes and the relocated engine only.
    shadowJar {
        archiveBaseName.set(pluginName)
        archiveClassifier.set("")
        from(newerApi.output)

        // Paper provides itself and the loader resolves the engine's libraries: only the engine is bundled.
        dependencies {
            include(dependency("gg.moonrise.engine:plugin-engine-paper:.*"))
        }

        // The engine moves under our package so Moss's scan (main class package only) finds its beans; its
        // async command registry is replaced by command.registry.HawkeyeCommandRegistry.
        exclude("gg/moonrise/engine/paper/command/PaperCommandRegistry.class")
        relocate("gg.moonrise.engine", "$basePackage.libs.engine")
    }
}

val verifyPluginJar = tasks.register("verifyPluginJar") {
    description = "Checks the plugin jar: entry points, relocated engine, expanded descriptor, one command registry, " +
        "Java $pluginRelease bytecode and the oldest supported api-version."
    group = "verification"
    val artifact = tasks.shadowJar.flatMap { it.archiveFile }
    val expectedVersion = project.version.toString()
    val expectedApiVersion = oldestMinecraft
    val release = pluginRelease
    val maxClassMajor = 44 + release
    val ours = basePackage.replace('.', '/') + "/"
    val mainClass = "$basePackage.${pluginName}Plugin"
    val loaderClass = "$basePackage.loader.${pluginName}PluginLoader"
    val registryClass = "${ours}command/registry/${pluginName}CommandRegistry.class"
    inputs.file(artifact)
    val marker = layout.buildDirectory.file("verifyPluginJar/ok")
    outputs.file(marker)

    doLast {
        val file = artifact.get().asFile
        ZipFile(file).use { jar ->
            val names = jar.entries().asSequence().map { it.name }.toList()
            listOf(
                mainClass.replace('.', '/') + ".class",
                loaderClass.replace('.', '/') + ".class",
                "${ours}libs/engine/paper/PaperPlugin.class",
                "${ours}libs/engine/paper/loader/PaperPluginLoader.class",
                "paper-plugin.yml",
                // Spring lists a package's classes through its directory entry.
                ours,
            ).forEach { require(it in names) { "${file.name} is missing $it" } }

            val classes = names.filter { it.endsWith(".class") }
            val foreign = classes.filter { !it.startsWith(ours) }
            require(foreign.isEmpty()) {
                "only our classes and the relocated engine may be bundled, found ${foreign.take(5)}"
            }
            require(names.none { it.startsWith("gg/moonrise/engine/") }) { "the engine must be relocated" }
            val registries = classes.filter { it.endsWith("CommandRegistry.class") }
            require(registries == listOf(registryClass)) {
                "expected exactly our command registry $registryClass, found $registries"
            }
            // Paper 1.21.x runs on Java 21: a newer class file would fail to load there.
            val tooNew = classes.filter { name ->
                val header = jar.getInputStream(jar.getEntry(name)).use { it.readNBytes(8) }
                ((header[6].toInt() and 0xff) shl 8 or (header[7].toInt() and 0xff)) > maxClassMajor
            }
            require(tooNew.isEmpty()) { "classes newer than Java $release: ${tooNew.take(5)}" }

            val yml = jar.getInputStream(jar.getEntry("paper-plugin.yml")).use { it.readBytes().decodeToString() }
            require("version: '$expectedVersion'" in yml) { "paper-plugin.yml version not expanded" }
            require("main: $mainClass" in yml) { "paper-plugin.yml main is not $mainClass" }
            require("loader: $loaderClass" in yml) { "paper-plugin.yml loader is not $loaderClass" }
            require("api-version: '$expectedApiVersion'" in yml) {
                "paper-plugin.yml api-version is not '$expectedApiVersion', the oldest API main compiles against " +
                    "(paperOldest in gradle/libs.versions.toml); move both together"
            }
        }
        marker.get().asFile.writeText("ok\n")
    }
}

tasks.check {
    dependsOn(verifyPluginJar)
}

// Publishes the plugin jar to the Moonrise Nexus on request (`./gradlew publish -Pversion=26.4` with
// nexusUsername/nexusPassword); no CI does it. Snapshot versions go to maven-snapshots.
publishing {
    publications {
        create<MavenPublication>("plugin") {
            from(components["shadow"])
        }
    }
    repositories {
        maven {
            name = "nexus"
            val repository = if (version.toString().endsWith("-SNAPSHOT")) "maven-snapshots" else "maven-releases"
            url = uri("https://repo.moonrise.gg/repository/$repository/")
            credentials {
                username = findProperty("nexusUsername") as String?
                password = findProperty("nexusPassword") as String?
            }
        }
    }
}

// Uploads the plugin jar to Modrinth as a version of the project `hawkeye` (docs/knowledge/README.md "Release facts"):
// release.yml for every GitHub release, modrinth-dev-build.yml for every push to main. By hand:
// `MODRINTH_TOKEN=... ./gradlew modrinth -Pversion=26.4`; MODRINTH_DEBUG=true prints the upload instead of sending it.
// A release or hotfix (26.4, 26.4.1) is a Modrinth release, anything else an alpha; the workflows set the type,
// number, name and changelog through the MODRINTH_* variables.
val stableVersion = Regex("""\d{2}\.[1-4](\.\d+)?""").matches(version.toString())
modrinth {
    token.set(providers.environmentVariable("MODRINTH_TOKEN"))
    projectId.set(providers.environmentVariable("MODRINTH_PROJECT_ID").orElse("hawkeye"))
    versionNumber.set(providers.environmentVariable("MODRINTH_VERSION_NUMBER").orElse(version.toString()))
    versionName.set(providers.environmentVariable("MODRINTH_VERSION_NAME").orElse("$pluginName $version"))
    versionType.set(providers.environmentVariable("MODRINTH_VERSION_TYPE").orElse(if (stableVersion) "release" else "alpha"))
    changelog.set(providers.environmentVariable("MODRINTH_CHANGELOG")
        .orElse("See [CHANGELOG.md](https://github.com/ericlmao/hawkeye/blob/main/CHANGELOG.md)."))
    uploadFile.set(tasks.shadowJar)
    gameVersions.addAll(providers.gradleProperty("modrinth.gameVersions")
        .map { versions -> versions.split(",").map(String::trim).filter(String::isNotEmpty) })
    // paper-plugin.yml: Paper and its forks only, Folia included (folia-supported: true).
    loaders.addAll("paper", "folia", "purpur")
    debugMode.set(providers.environmentVariable("MODRINTH_DEBUG").map { it == "true" }.orElse(false))
}

tasks.named("modrinth") {
    dependsOn(verifyPluginJar)
    // Minotaur 2.10.0 reads Task.project while it runs.
    notCompatibleWithConfigurationCache("Minotaur reads Task.project at execution time")
}
