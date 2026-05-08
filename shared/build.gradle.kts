plugins {
    alias(libs.plugins.kotlin.jvm)
}

// §TZ-DESKTOP-DIST — toolchain 17 (auto-download через foojay-resolver,
// см. settings.gradle.kts). Совместимо с :desktop, который тоже на 17 для
// ProGuard 7.2.2 в Compose plugin при packageRelease*.
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}
