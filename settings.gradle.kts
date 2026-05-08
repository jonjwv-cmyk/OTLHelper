pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// §TZ-DESKTOP-DIST — auto-provisioning JDK 17 для toolchain. Compose
// Multiplatform 1.7.3 ProGuard 7.2.2 не принимает class file format > 62
// (Java 18); наш target = 17 (формат 61). Foojay-resolver автоматически
// скачивает Temurin JDK 17 в `~/.gradle/jdks/` при первом packageRelease*.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // §TZ-DESKTOP 0.4.0 — JCEF native binaries (Chromium runtime для KCEF)
        // публикуются на jcefmaven repo + jitpack (kcef wrapper).
        maven("https://jogamp.org/deployment/maven")
        maven("https://repo.spring.io/release")
        maven("https://maven.jzy3d.org/releases/")
        maven("https://jitpack.io")
    }
}

rootProject.name = "OTL Helper"
include(":shared")
include(":app")
include(":desktop")
