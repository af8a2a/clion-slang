import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.slang.intellij"
version = "0.1.3"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val localIdePath = providers.gradleProperty("localIdePath").orNull
val platformVersion = providers.gradleProperty("platformVersion").getOrElse("2026.1.3")

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
