plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "be.jonassteur.claudesessions"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local("/Applications/PyCharm.app")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

intellijPlatform {
    instrumentCode = false
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }
}
