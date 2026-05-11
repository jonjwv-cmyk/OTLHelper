import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// §TZ-DESKTOP-0.1.0 — отдельный Gradle-модуль, НЕ импортит из :app.
// Никаких Android-зависимостей тут нет.

// §TZ-DESKTOP-DIST — toolchain JDK 17 (через foojay-resolver auto-download
// в settings.gradle.kts). Compose Multiplatform 1.7.3 ProGuard 7.2.2 не
// читает class files > 62 (Java 18). JDK 17 → формат 61 → ProGuard ОК.
// Кроме того ProGuard читает JDK system jmods для resolve symbols — поэтому
// runtime JDK тоже должен быть ≤ 17.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    // compose.components.resources намеренно не подключаем — не используем,
    // и его code-gen падает из-за пробела в `rootProject.name = "OTL Helper"`.

    // §TZ-DESKTOP-0.1.0 этап 3 — сетевой слой.
    // OkHttp тот же что в Android (4.12.0) → тот же паттерн DNS override + VPS cert.
    implementation(libs.okhttp)
    // JSON — используем org.json, как в Android, чтобы легко мигрировать
    // репозитории позже в :shared модуль.
    implementation(libs.org.json)

    // §TZ-DESKTOP-0.1.0 этап 5 — inline video-плеер через VLCJ.
    // VLCJ — Java-bindings к libVLC; требует установленный VLC на системе
    // (macOS /Applications/VLC.app, Windows C:\Program Files\VideoLAN\VLC, Linux /usr/lib/vlc).
    // Если VLC не найден — VideoPlayer автоматически падает в placeholder-режим.
    // Поддерживает все кодеки что и VLC (mp4/H.264/H.265, webm, mkv, mov, и т.д.).
    implementation("uk.co.caprica:vlcj:4.8.3")

    // §TZ-DESKTOP 0.3.1 — webcam capture для "Сделать снимок" в аватарке.
    // sarxos webcam-capture (2019, самый стабильный pure-Java wrapper на
    // native OS-APIs: QTKit/AVFoundation на mac, DirectShow на Windows,
    // Video4Linux на Linux). На старте запрашивает разрешение у OS. Если
    // камера недоступна — Webcam.getDefault() возвращает null, мы
    // показываем ошибку в диалоге (graceful fallback).
    implementation("com.github.sarxos:webcam-capture:0.3.12")

    // §TZ-DESKTOP 0.4.0 — Dispatchers.Main для desktop Swing/AWT EDT.
    // WsClient.handleMessage делает scope.launch(Dispatchers.Main) — без
    // этой зависимости падает с "Module with the Main dispatcher is missing".
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // §TZ-DESKTOP 0.4.0 + §TZ-DESKTOP-DIST 0.5.2 — KCEF (Kotlin Chromium
    // Embedded Framework). Встроенный Chromium для Google Sheets-зоны:
    // настоящий редактор Google с формулами и Apps Script триггерами,
    // плюс наша CSS-маска поверх. На первом запуске SheetsRuntime сам
    // качает bundle (~150 MB) с api.otlhelper.com/kcef-bundle/ через VPS,
    // распаковывает в ~/.otldhelper/kcef-bundle/, KCEF.init() видит
    // install.lock → use bundle.
    // §TZ-DESKTOP-DIST 0.5.2 — upgrade с 2024.04.20.4 → 2025.03.23.
    // Старая версия падала на парсинге GitHub API response (поле 'body' стало
    // null в JBR releases) при попытке prebuild Mac bundle в CI. Новая версия
    // pinned to Kotlin 2.0.21 (как у нас) → совместима.
    implementation("dev.datlag:kcef:2025.03.23")

    // §TZ-DESKTOP-DIST 0.5.2 — Apache Commons Compress для распаковки
    // tar.gz bundle'а в SheetsRuntime.extractTarGz(). KCEF library
    // подтягивает её как transitive runtime, но не exposes для compile —
    // поэтому декларируем явно.
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Lottie cat splash in the desktop Sheets loading cover.
    implementation(libs.compottie)

    // §TZ-2.4.0 — Google Tink (pure JVM) для E2E encryption на desktop.
    // Тот же subtle API что и в `:app` через tink-android. Wire format
    // X25519 + HKDF-SHA256 + AES-256-GCM один и тот же сервер ↔ клиент.
    implementation(libs.tink.jvm)

    // Native macOS WKWebView bridge (free system engine) and Windows WebView2 bridge prep.
    implementation(libs.jna)
    implementation(libs.jna.platform)

    // §TZ-DESKTOP-0.9.1 — WAFFLE-JNA: Windows SSPI bridge для transparent
    // Integrated Windows Authentication через corporate proxy (Squid+NTLM/Kerberos).
    // Использует current Windows-domain-user creds (как Chrome/Edge), без UI.
    // Активируется только на Windows; на Mac/Linux JAR в classpath но idle.
    implementation(libs.waffle.jna)

    // §TZ-DESKTOP-0.9.3 — jcifs-ng для NTLMv2 Type-3 с explicit creds
    // (fallback когда WAFFLE-SSPI не справился). Юзер вводит логин/пароль
    // в диалог, jcifs-ng генерит криптографически правильный Type-3 response.
    implementation(libs.jcifs.ng)

    // §TZ-0.10.5 — QR-код для desktop login. nayuki/qrcodegen — pure-Java 11
    // single-class encoder. ZXing 3.5.x все скомпилированы Java 21 (байт-код
    // version 65.0) и валят ProGuard 7.2.2. nayuki собран с Java 11 → OK.
    implementation("io.nayuki:qrcodegen:1.8.0")
}

// §TZ-DESKTOP-TEST — thin test JAR для проверки JVM-SSPI Kerberos через
// прокси НА КОНКРЕТНОЙ корп-машине без необходимости пересобирать всё app
// через CI. Содержит только TestSspiMain.class. Запускается с classpath
// который включает bundled OTLD Helper jars (где WAFFLE-JNA уже есть).
tasks.register<Jar>("sspiTestJar") {
    group = "verification"
    description = "Минимальный test JAR для проверки JVM-SSPI Kerberos через прокси"
    archiveBaseName.set("otl-sspi-test")
    archiveClassifier.set("")
    archiveVersion.set("")
    dependsOn("compileKotlin")
    manifest {
        attributes["Main-Class"] = "com.example.otlhelper.desktop.test.TestSspiMainKt"
    }
    from(sourceSets.main.get().output) {
        include("com/example/otlhelper/desktop/test/**")
    }
}

compose.desktop {
    application {
        mainClass = "com.example.otlhelper.desktop.MainKt"

        // §TZ-DESKTOP 0.4.0 — JCEF (под капотом KCEF) использует sun.* internal
        // API для AWT-bridge. На JDK 17+ нужно явно открыть пакеты иначе
        // IllegalAccessException на старте Chromium.
        jvmArgs += listOf(
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
            "--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED",
            "--add-opens=java.desktop/com.apple.eawt.event=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
        )

        // §TZ-DESKTOP-DIST — ProGuard/R8 для release-сборок. Включается
        // только при `:desktop:packageRelease*` / `:desktop:runRelease`,
        // обычный `:desktop:run` (dev-loop) НЕ обфусцирует.
        //
        // Цель — Windows EXE: имена классов/методов/полей переименованы,
        // dead-code stripped, вызовы inlin'ены. Юзер с decompiler'ом
        // (jadx/CFR/Recaf) увидит a.b.c() вместо ApiClient.desktopLogin().
        // Mac DMG получает то же — не повредит, дешёвый bonus.
        //
        // Compose runtime / KCEF / JNA / Coil — keep-rules в proguard-rules.pro.
        buildTypes.release.proguard {
            obfuscate.set(true)
            // §TZ-DESKTOP-DIST — optimize=false. KCEF/JCEF тянут много
            // runtime-only пакетов (org.apache.thrift IPC, com.jogamp.opengl);
            // optimization phase ProGuard анализирует class hierarchy и падает
            // с IncompleteClassHierarchyException. Нам нужна только обфускация
            // имён (главная цель), не оптимизация — проигрыш в размере JAR
            // незначительный, а сборка стабильная.
            optimize.set(false)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

        nativeDistributions {
            // §TZ-DESKTOP-DIST 0.8.59 — bundled VLC (Win) живёт в
            // app-resources/windows/vlc/. Compose Desktop копирует ресурсы из
            // этой папки в установленный bundle (доступны через
            // System.getProperty("compose.application.resources.dir")).
            // Mac не качает VLC здесь — на Mac юзеры обычно ставят /Applications/VLC.app
            // отдельно (наш VideoPlayer ищет оба пути).
            //
            // Папка app-resources/ создаётся CI workflow'ом перед сборкой EXE
            // (download VLC zip → extract → app-resources/windows/vlc/).
            // Локально можно создать вручную для теста; в .gitignore.
            appResourcesRootDir.set(project.file("app-resources"))

            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "OTLD Helper"
            // §TZ-NEXT P1.1 — derived MSI/DMG version из BuildInfo.VERSION.
            // jpackage требует MAJOR ≥ 1, поэтому "0.X.Y" → "1.X.Y" (видна
            // юзеру в "Программы и компоненты" Windows и в About Mac). CI
            // sed'ит BuildInfo.VERSION перед билдом — packageVersion
            // подхватит actual version автоматически.
            val internalVer: String = run {
                val buildInfoFile = file("src/main/kotlin/com/example/otlhelper/desktop/BuildInfo.kt")
                val txt = if (buildInfoFile.exists()) buildInfoFile.readText() else ""
                Regex("""const val VERSION = "([^"]+)"""").find(txt)?.groupValues?.get(1) ?: "0.0.0"
            }
            val parts = internalVer.split('.').map { it.toIntOrNull() ?: 0 }
            // §1.0.1 — переход с beta-нумерации 0.X.Y.Z на стабильную 1.Y.Z.
            //
            // MSI ProductVersion поддерживает Major.Minor.Build (build max 65535).
            // Чтобы Windows Installer считал переход 0.11.14.3 → 1.0.1 как
            // UPGRADE (а не downgrade!), packageVersion должен монотонно расти.
            //
            // СТАРАЯ FORMULA для 0.X.Y.Z: "1.<Y>.<Z*100 + W>"
            //   0.11.14.3 → 1.11.1403
            //
            // НОВАЯ FORMULA для 1.X.Y (major >= 1): "2.<X>.<Y>"
            //   1.0.1 → 2.0.1   ← >1.11.1403 ✓ (Windows MSI считает upgrade)
            //   1.0.2 → 2.0.2
            //   1.1.0 → 2.1.0
            //
            // Юзеры в "Программы и компоненты" видят MSI ProductVersion
            // (2.0.1), но в самом приложении (About, логи, заголовок окна)
            // отображается BuildInfo.VERSION = "1.0.1". Это расхождение —
            // плата за monotonic upgrade compat со старой 0.X.Y.Z схемой.
            //
            // Когда наберём 1.X с большим X — внутренняя формула
            // продолжит работать (2.X.Y растёт).
            val major = parts.getOrElse(0) { 0 }
            val minor = parts.getOrElse(1) { 0 }
            val patch = parts.getOrElse(2) { 0 }
            val subpatch = parts.getOrElse(3) { 0 }
            packageVersion = if (major == 0) {
                // legacy 0.X.Y.Z → старая формула (для upgrade со старых релизов)
                val msiBuild = patch * 100 + subpatch
                "1.$minor.$msiBuild"
            } else {
                // new 1.X.Y → 2.X.Y (всегда > 1.11.* старых релизов)
                "${major + 1}.$minor.$patch"
            }
            // §TZ-2.4.4 — короткое description (WiX не любит длинные Unicode
            // строки в MSI metadata; jpackage падает на overflow).
            description = "OTLD Helper - desktop client"
            vendor = "OTL"
            copyright = "Copyright (c) 2025-2026 OTL"

            // §TZ-DESKTOP-DIST 0.5.1 — VLCJ ByteBufferFactory использует sun.misc.Unsafe
            // (из jdk.unsupported), а JNA подгружает nio/sunmisc bridges. Без этих
            // модулей bundled JRE падает NoClassDefFoundError на первом видео-фрейме.
            // §TZ-DESKTOP 0.4.0 — KCEF + JCEF тянут jdk.crypto.ec (TLS), java.naming
            // (DNS), java.sql (cookies SQLite), jdk.management (process info).
            // §TZ-DESKTOP-0.10.2 — jdk.crypto.mscapi: содержит SunMSCAPI provider
            // для KeyStore.getInstance("Windows-ROOT"). Без этого модуля JVM
            // не находит Windows trust store → Касперский TLS-MITM cert
            // не валидируется → SSLHandshakeException PKIX. Модуль существует
            // ТОЛЬКО в Windows JDK — на macOS host'е при сборке DMG его нет,
            // jpackage упадёт с "module not found". Поэтому conditional add.
            val baseModules = listOf(
                "jdk.unsupported",
                "java.naming",
                "java.sql",
                "jdk.crypto.ec",
                "jdk.management",
                "java.management",
            )
            val winOnlyModules = if (System.getProperty("os.name", "").lowercase().contains("win")) {
                listOf("jdk.crypto.mscapi")
            } else emptyList()
            modules(*(baseModules + winOnlyModules).toTypedArray())

            macOS {
                bundleID = "com.otl.otldhelper"
                iconFile.set(project.file("icons/app.icns"))
            }
            windows {
                // §TZ-DESKTOP-DIST — per-user EXE installer. Без admin-прав
                // ставится в %LOCALAPPDATA%\OTLD Helper\, обновляется тем же
                // installer'ом без UAC.
                perUserInstall = true
                // §TZ-2.4.4 — стабильный UUID, чтобы каждая новая версия
                // обновляла существующую установку, а не ставилась рядом.
                // ВАЖНО: не менять UUID никогда — иначе юзеры получат две
                // установки в "Programs and Features".
                upgradeUuid = "1d60e67c-0629-4488-8ad0-7bae0fa2510a"
                menu = true
                menuGroup = "OTL"
                shortcut = true
                // §TZ-DESKTOP-DIST 0.5.1 — иконка генерируется в CI через
                // ImageMagick (см. release-desktop.yml step "Generate Win icon").
                val winIcon = project.file("icons/app.ico")
                if (winIcon.exists()) {
                    iconFile.set(winIcon)
                }
            }
        }
    }
}

// §TZ-DESKTOP-DIST 0.5.2 — prebuild KCEF (Chromium) bundle для upload в R2.
//
// Используется ОТДЕЛЬНЫМ workflow `upload-kcef-bundles.yml` (не в production
// release flow). Идея: один раз prebuild bundle для каждой платформы (win-amd64,
// mac-arm64, mac-amd64), tar.gz, upload в R2 → клиент при первом запуске
// качает с `api.otlhelper.com/kcef-bundle/jcef-<os>-<arch>.tar.gz` через VPS.
//
// Зачем не в production release: KCEF download ~5-10 мин на CI runner, и
// сам bundle ~150 MB не помещается в installer через wrangler put (300 MB cap).
// Делаем bundle отдельным артефактом, EXE/DMG остаются ~50-80 MB.
val prebuildKcefBundle by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Pre-downloads JCEF Chromium runtime для текущей платформы (для upload в R2)."

    val outDir = project.layout.buildDirectory.dir("kcef-prebundle")
    outputs.dir(outDir)

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.otlhelper.desktop.tools.KcefPrebuildMainKt")

    systemProperty("kcef.installDir", outDir.get().asFile.absolutePath)
    systemProperty("kcef.timeoutSeconds", "900") // 15 мин — на медленный CI runner
    systemProperty("java.awt.headless", "true")

    // §TZ-DESKTOP 0.4.0 — те же --add-opens что и в production app, иначе
    // KCEF.init() в headless mode падает на sun.awt access.
    jvmArgs(
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        "--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
    )

    // §TZ-DESKTOP-DIST 0.5.2 — KCEF.init() в headless mode CRASH'ит JVM на
    // Mac (native code crash в init phase Chromium когда нет Display). Это
    // OK — bundle уже распакован и install.lock создан ДО crash. Игнорируем
    // non-zero exit, верификацию делаем по install.lock в doLast.
    isIgnoreExitValue = true

    doFirst {
        val dirFile = outDir.get().asFile
        if (dirFile.exists()) dirFile.deleteRecursively()
        dirFile.mkdirs()
        logger.lifecycle("[prebuildKcefBundle] downloading into $dirFile")
    }

    doLast {
        val lock = outDir.get().file("install.lock").asFile
        if (!lock.exists()) {
            throw GradleException("KCEF prebuild finished but install.lock missing in ${outDir.get().asFile}")
        }
        val sizeMB = outDir.get().asFile.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() } / 1024 / 1024
        logger.lifecycle("[prebuildKcefBundle] OK — bundle ~${sizeMB} MB ready")
    }
}
