import groovy.json.JsonSlurper
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.slang.intellij"
version = "0.6.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val localIdePath = providers.gradleProperty("localIdePath").orNull
val platformVersion = providers.gradleProperty("platformVersion").getOrElse("2026.1.3")
val bundledSlangdArchive = file(
    providers.gradleProperty("bundledSlangdArchive")
        .getOrElse(".bundled-runtime/windows-x86_64.zip")
)

val bundledRuntimeEntries = listOf(
    "0001-m2a-enhanced-semantic-tokens.patch",
    "0002-m3-slang-hlsl-semantic-tokens.patch",
    "0003-field-layout-hover.patch",
    "0004-field-hover-presentation.patch",
    "0005-document-local-references.patch",
    "0006-document-variable-highlights.patch",
    "0007-rider-function-hover.patch",
    "LICENSE-slang.txt",
    "LICENSES/lz4-distribution.txt",
    "LICENSES/lz4-lib-BSD-2-Clause.txt",
    "LICENSES/miniz-MIT.txt",
    "LICENSES/unordered_dense-MIT.txt",
    "manifest.json",
    "slang-compiler.dll",
    "slang-glsl-module.bin",
    "slangd.exe",
)

val verifyBundledSlangdArchive = tasks.register("verifyBundledSlangdArchive") {
    group = "verification"
    description = "Verifies the complete Windows x86_64 bundled slangd archive contract."

    // Track the path rather than declaring a file input so Gradle reaches the
    // actionable error below when the file does not exist.
    inputs.property("bundledSlangdArchivePath", bundledSlangdArchive.absolutePath)
    inputs.property("bundledSlangdExpectedEntries", bundledRuntimeEntries.joinToString("\n"))

    doLast {
        val archive = File(inputs.properties.getValue("bundledSlangdArchivePath") as String)
        val expectedEntries = (inputs.properties.getValue("bundledSlangdExpectedEntries") as String)
            .lineSequence()
            .filter { it.isNotEmpty() }
            .toList()
        val expectedPayloadEntries = expectedEntries.filterNot { it == "manifest.json" }.toSet()
        fun requiredObject(parent: Map<*, *>, name: String): Map<*, *> =
            parent[name] as? Map<*, *>
                ?: throw GradleException("Bundled slangd manifest field '$name' must be an object")
        fun requiredInteger(parent: Map<*, *>, name: String): Int {
            val value = parent[name] as? Number
                ?: throw GradleException("Bundled slangd manifest field '$name' must be an integer")
            return try {
                BigDecimal(value.toString()).intValueExact()
            } catch (exception: ArithmeticException) {
                throw GradleException(
                    "Bundled slangd manifest field '$name' must be an exact 32-bit integer",
                    exception
                )
            } catch (exception: NumberFormatException) {
                throw GradleException(
                    "Bundled slangd manifest field '$name' must be an exact 32-bit integer",
                    exception
                )
            }
        }
        fun entrySha256(input: java.io.InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            return HexFormat.of().formatHex(digest.digest())
        }
        if (!archive.isFile) {
            throw GradleException(
                """
                Bundled slangd archive is missing: ${archive.absolutePath}
                Generate it with scripts/prepare-bundled-slangd.ps1, or select an existing archive with:
                  -PbundledSlangdArchive=<path-to-windows-x86_64.zip>
                """.trimIndent()
            )
        }

        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val names = entries.map { it.name }
            if (names != expectedEntries) {
                throw GradleException(
                    """
                    Bundled slangd archive has an unexpected entry set or order.
                    Expected: ${expectedEntries.joinToString()}
                    Actual:   ${names.joinToString()}
                    """.trimIndent()
                )
            }
            if (names.toSet().size != names.size || entries.any { it.isDirectory }) {
                throw GradleException("Bundled slangd archive must contain each fixed file exactly once")
            }
            if (entries.any { it.method != ZipEntry.STORED }) {
                throw GradleException("Bundled slangd archive must use STORE compression for every entry")
            }
            val extractedBytes = entries.sumOf {
                if (it.size < 0) {
                    throw GradleException("Bundled slangd archive entry '${it.name}' has no declared size")
                }
                it.size
            }
            if (extractedBytes > 512L * 1024 * 1024) {
                throw GradleException("Bundled slangd archive exceeds the 512 MiB extracted-size limit")
            }

            val manifestEntry = zip.getEntry("manifest.json")
                ?: throw GradleException("Bundled slangd archive is missing manifest.json")
            if (manifestEntry.size !in 1..(1024L * 1024)) {
                throw GradleException("Bundled slangd manifest must be between 1 byte and 1 MiB")
            }
            val manifestText = zip.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val manifest = JsonSlurper().parseText(manifestText) as? Map<*, *>
                ?: throw GradleException("Bundled slangd manifest must be a JSON object")

            if (requiredInteger(manifest, "schemaVersion") != 1) {
                throw GradleException("Bundled slangd manifest schemaVersion must be 1")
            }
            if (manifest["profile"] != "clion-slang-m3") {
                throw GradleException("Bundled slangd manifest profile must be 'clion-slang-m3'")
            }
            val build = requiredObject(manifest, "build")
            if (build["platform"] != "windows-x64") {
                throw GradleException("Bundled slangd manifest build.platform must be 'windows-x64'")
            }
            val protocol = requiredObject(manifest, "protocol")
            if (requiredInteger(protocol, "major") != 1 ||
                requiredInteger(protocol, "minor") != 5 ||
                protocol["features"] != listOf(
                    "semanticTokens.m2a",
                    "semanticTokens.m3",
                    "hover.fieldLayout.natural",
                    "references.documentLocal",
                    "documentHighlight.documentLocal",
                    "hover.functionSignature.rider",
                )
            ) {
                throw GradleException(
                    "Bundled slangd manifest protocol must be 1.5 with semanticTokens.m2a, " +
                        "semanticTokens.m3, hover.fieldLayout.natural, references.documentLocal, " +
                        "documentHighlight.documentLocal, and hover.functionSignature.rider"
                )
            }

            val files = requiredObject(manifest, "files")
            if (files.keys != expectedPayloadEntries) {
                throw GradleException(
                    "Bundled slangd manifest files must exactly match the fixed payload entry set"
                )
            }
            expectedPayloadEntries.forEach { name ->
                val expectedHash = files[name] as? String
                    ?: throw GradleException("Bundled slangd manifest hash for '$name' must be a string")
                if (!expectedHash.matches(Regex("[0-9a-f]{64}"))) {
                    throw GradleException("Bundled slangd manifest hash for '$name' is not lowercase SHA-256")
                }
                val actualHash = zip.getInputStream(zip.getEntry(name)).use(::entrySha256)
                if (actualHash != expectedHash) {
                    throw GradleException(
                        "Bundled slangd archive SHA-256 mismatch for '$name': " +
                            "expected $expectedHash, got $actualHash"
                    )
                }
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
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
    }

    withType<Test>().configureEach {
        useJUnit()
    }

    processResources {
        dependsOn(verifyBundledSlangdArchive)
        from(bundledSlangdArchive) {
            into("slang/runtime")
            rename { "windows-x86_64.zip" }
        }
    }

    named<BuildPluginTask>("buildPlugin") {
        archiveClassifier.set("windows-x86_64")
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
        // Match the version suffix produced by the IntelliJ Platform native-variant model.
        version = "${project.version}-windows-x86_64"
        ideaVersion {
            sinceBuild = "261.25134"
        }
    }

    buildSearchableOptions = false
}
