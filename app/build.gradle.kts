plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.example.otlhelper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.otlhelper"
        minSdk = 24
        targetSdk = 36
        val appVersionName = (project.findProperty("APP_VERSION_NAME") as? String) ?: "2.5.9"
        val parts = appVersionName.split(".").map { it.toIntOrNull() ?: 0 }
        versionCode = (parts.getOrElse(0) { 0 } * 10000) + (parts.getOrElse(1) { 0 } * 100) + parts.getOrElse(2) { 0 }
        versionName = appVersionName

        // §TZ-2.3.31 Phase 4c — APK signature self-check. Если задан через
        // gradle property `otl.signing.sha256=<64hex>` или env
        // `OTL_SIGNING_SHA256`, в release IntegrityGuard сравнивает runtime
        // SHA-256 cert'а с ожидаемым и убивает процесс при mismatch.
        // Пустая строка = skip check (dev-keystore, CI).
        val expectedSigningSha256 = (project.findProperty("otl.signing.sha256") as? String)
            ?: System.getenv("OTL_SIGNING_SHA256")
            ?: ""
        buildConfigField("String", "EXPECTED_SIGNING_SHA256", "\"$expectedSigningSha256\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Allow `installDebug` to overwrite an APK with a HIGHER versionCode that
    // was previously installed by the release script (or an OTA update). Without
    // -d, adb refuses with INSTALL_FAILED_VERSION_DOWNGRADE and the IDE just
    // throws an error — so devs hit "Run" after a release and the build fails.
    installation {
        installOptions("-r", "-d")
    }

    // Release signing — reads keystore path + passwords from env vars OR
    // ~/.gradle/gradle.properties. Falls back to the debug keystore when
    // nothing is configured, so `assembleRelease` still produces an APK for
    // CI or local smoke tests (won't install on devices that expect prod
    // fingerprint, but lets R8 + shrinker run to catch issues early).
    //
    // To enable prod signing (one-time):
    //   1. keytool -genkey -v -keystore ~/keystores/otl-release.jks \
    //        -alias otl -keyalg RSA -keysize 4096 -validity 10000
    //   2. Add to ~/.gradle/gradle.properties (outside the repo — NEVER commit):
    //        otl.signing.keystorePath=/Users/you/keystores/otl-release.jks
    //        otl.signing.keystorePassword=...
    //        otl.signing.keyAlias=otl
    //        otl.signing.keyPassword=...
    //   3. Record the SHA-256 fingerprint for server-side Play Integrity:
    //        keytool -list -v -keystore ~/keystores/otl-release.jks
    signingConfigs {
        create("release") {
            val keystorePath = (project.findProperty("otl.signing.keystorePath") as? String)
                ?: System.getenv("OTL_SIGNING_KEYSTORE_PATH")
            val keystorePasswordValue = (project.findProperty("otl.signing.keystorePassword") as? String)
                ?: System.getenv("OTL_SIGNING_KEYSTORE_PASSWORD")
            val keyAliasValue = (project.findProperty("otl.signing.keyAlias") as? String)
                ?: System.getenv("OTL_SIGNING_KEY_ALIAS")
            val keyPasswordValue = (project.findProperty("otl.signing.keyPassword") as? String)
                ?: System.getenv("OTL_SIGNING_KEY_PASSWORD")

            if (keystorePath != null && file(keystorePath).exists() &&
                keystorePasswordValue != null && keyAliasValue != null && keyPasswordValue != null) {
                storeFile = file(keystorePath)
                storePassword = keystorePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
                // v1+v2+v3 signing so the APK verifies on all supported Android versions.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
            // else: leave empty — fallback logic below wires release to the debug keystore.
        }
    }

    buildTypes {
        release {
            // R8 full mode — tree-shakes unused code + resources, obfuscates
            // method/field names, and inlines what it can. Required by
            // Google Play 2026 guidelines. Rules live in proguard-rules.pro;
            // consumer rules ship with each AAR.
            isMinifyEnabled = true
            isShrinkResources = true
            // Hard-disable the debuggable flag even if someone later flips
            // it on the command line. Prevents attaching a JDWP debugger
            // to a production APK and inspecting runtime memory.
            isDebuggable = false
            // Strip native-library debug symbols — forces reversers to work
            // against raw .so blobs instead of getting function names for
            // free. Release-size win too.
            ndk {
                debugSymbolLevel = "NONE"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Pick the release signing config if properly set up, otherwise
            // fall back to debug so `assembleRelease` succeeds in dev/CI.
            // Prod releases require the release keystore — see signingConfigs block.
            val releaseCfg = signingConfigs.getByName("release")
            signingConfig = if (releaseCfg.storeFile != null) releaseCfg
            else signingConfigs.getByName("debug")
        }
        debug {
            // Keep debug builds fast and fully symbolicated — no R8 in the
            // edit-compile-run loop. Logs stay on, debugger attaches normally.
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)

    // Activity + Navigation
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)

    // Lifecycle / ViewModel
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    // ProcessLifecycleOwner — глобальные foreground/background-наблюдатели
    // (нужно для presence app_state heartbeat).
    implementation(libs.lifecycle.process)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room + SQLCipher
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.sqlite.ktx)

    // Coroutines
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)

    // Firebase Cloud Messaging (BoM aligns versions)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Coil (images + animated GIF)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)

    // Media3 / ExoPlayer — inline video player + SimpleCache
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource)
    // §TZ-2.3.20 — OkHttp datasource для ExoPlayer чтобы видео шли через
    // наш HttpClientFactory (DNS override → VPS), а не через системный HTTP
    // stack (который идёт прямо на CF и троттлится ISP DPI).
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.database)

    // OkHttp (networking) + HttpLoggingInterceptor (debug builds only in usage)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    // Lottie
    implementation(libs.lottie)

    // Biometric auth (§3.15.a.Д, Phase 12e)
    implementation(libs.androidx.biometric)

    // Play Integrity API — attestation APK + devices on login
    implementation(libs.play.integrity)

    // §TZ-2.3.25 — Google Tink для E2E encryption (X25519+HKDF subtle API).
    // Выбран вместо BouncyCastle потому что: (a) tested by Google крипто-команда,
    // (b) ~600KB overhead vs BC ~4MB, (c) clean subtle API для primitives.
    implementation(libs.tink.android)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // §TZ-2.5.6 — ML Kit Barcode + CameraX для QR-сканера (как в Telegram).
    // ZXing-android-embedded был slow на autofocus + decoding. ML Kit detect'ит
    // моментально, CameraX даёт modern lifecycle-aware preview.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // Старый ZXing оставляем как fallback на случай если ML Kit недоступен на
    // устаревших устройствах (legacy Android Go без Google Play Services).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
