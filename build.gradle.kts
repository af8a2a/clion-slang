import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.slang.intellij"
version = "0.8.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val localIdePath = providers.gradleProperty("localIdePath").orNull
val platformVersion = providers.gradleProperty("platformVersion").getOrElse("2026.1.3")

// Runtime files live next to lib/, so the IDE installs/removes them with this plugin version.
val bundledSlangdDirectory = layout.projectDirectory.dir(
    providers.gradleProperty("bundledSlangdDir").getOrElse(".bundled-runtime/windows-x86_64")
)
val verifyBundledSlangd by tasks.registering {
    val root = bundledSlangdDirectory.asFile
    val currentPatches = fileTree("patches/slang").matching { include("*.patch") }.files
    inputs.dir(root)
    inputs.files(currentPatches)
    doLast {
        val manifestFile = root.resolve("manifest.json")
        if (!manifestFile.isFile) {
            throw GradleException("Missing bundled slangd. Run python scripts/prepare-bundled-slangd.py; see docs/bundled-slangd.md.")
        }
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        val manifest = JsonSlurper().parse(manifestFile) as Map<*, *>
        check(manifest["schemaVersion"] == 1 && manifest["profile"] == "clion-slang-enhanced"
            && manifest["platform"] == "windows-x86_64") { "Unsupported bundled slangd manifest" }
        val files = manifest["files"] as Map<*, *>
        check(files.keys.containsAll(listOf("slangd.exe", "slang-compiler.dll", "slang-glsl-module.dll",
            "NOTICE.txt", "licenses/Slang.txt", "licenses/Apache-2.0.txt"))) { "Incomplete bundled runtime" }
        val actualFiles = root.walkTopDown().filter { it.isFile && it != manifestFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        check(actualFiles == files.keys) { "Bundled runtime file set differs from manifest" }
        files.forEach { (name, hash) ->
            val relative = name as String
            check(relative.split('/').none { it == ".." || it.isEmpty() } && !relative.contains('\\') && !relative.contains(':'))
            check(sha256(root.resolve(relative)) == hash) { "Bundled runtime checksum mismatch: $name" }
        }
        val source = manifest["source"] as Map<*, *>
        check(source["commit"] == "5f9227cf6e5055b6a9ee742fdd729aab9162cf25") { "Unexpected Slang base commit" }
        val patches = source["patches"] as Map<*, *>
        check(currentPatches.map { it.name }.toSet() == patches.keys) { "Stale bundled patch series" }
        currentPatches.forEach {
            check(sha256(it) == patches[it.name] && files["patches/${it.name}"] == patches[it.name]) {
                "Bundled slangd is stale: ${it.name} changed; rebuild and stage it again"
            }
        }
    }
}

dependencies {
    intellijPlatform {
        if (localIdePath.isNullOrBlank()) {
            clion(platformVersion)
        } else {
            local(localIdePath)
        }
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

java {
    toolchain {
        // The local CLion installation ships JBR 25. Compile with it while
        // --release 21 keeps bytecode and JDK API usage compatible with 261.
        languageVersion = JavaLanguageVersion.of(25)
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    withType<PrepareSandboxTask>().configureEach {
        dependsOn(verifyBundledSlangd)
        from(bundledSlangdDirectory) {
            into(pluginName.map { "$it/runtime/windows-x86_64" })
        }
    }

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
    }

    withType<Test>().configureEach {
        useJUnit()
        providers.gradleProperty("slangdTestPath").orNull?.let {
            systemProperty("slang.test.slangd", it)
        }
        providers.gradleProperty("slangVariantsTestPath").orNull?.let {
            systemProperty("slang.test.cmakeVariants", it)
        }
    }

    wrapper {
        gradleVersion = "9.0.0"
        distributionType = Wrapper.DistributionType.BIN
    }

}

intellijPlatform {
    // This plugin has no GUI Designer forms and does not rely on injected
    // runtime nullability assertions, so bytecode instrumentation is unnecessary.
    instrumentCode = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261.25134"
        }
    }

    buildSearchableOptions = false
}
