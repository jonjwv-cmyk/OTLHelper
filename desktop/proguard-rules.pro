# §TZ-DESKTOP-DIST — ProGuard/R8 правила для Compose Desktop release.
#
# Цель: обфускация бизнес-кода (наш com.example.otlhelper.desktop.**) для
# затруднения чтения декомпилированного EXE. Compose runtime / нативные
# bridges (JCEF, JNA, VLCJ, sarxos) трогать нельзя — они зовут классы по
# имени через reflection или JNI, обфускация их сломает на старте.
#
# Применяется при packageRelease*/runRelease задачах (см. build.gradle.kts
# buildTypes.release.proguard {}).

# ── Базовые атрибуты ─────────────────────────────────────────────────────
# Сохраняем сигнатуры дженериков и аннотации — Compose-плагин и kotlinx
# делают runtime-проверки по ним. SourceFile/LineNumberTable затёрты в
# release, но trace остаётся читабельным благодаря mapping.txt.
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes Exceptions
-keepattributes RuntimeVisible*Annotations
-keepattributes RuntimeInvisible*Annotations

# Смягчаем ошибки на missing classes из platform-specific JDK API.
-dontwarn java.beans.**
-dontwarn javax.swing.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**

# §TZ-DESKTOP-DIST — JCEF/KCEF тянет много runtime-only зависимостей которые
# не присутствуют в classpath (Thrift IPC, JOGL OpenGL, X11, Java EE).
# Без этих -dontwarn ProGuard падает с 41k+ unresolved references.
-dontwarn org.apache.thrift.**
-dontwarn com.jogamp.**
-dontwarn javax.media.**
-dontwarn javax.servlet.**
-dontwarn jogamp.**
-dontwarn org.bridj.**
-dontwarn com.sun.javafx.**
-dontwarn javafx.**
-dontwarn android.**
-dontwarn org.jetbrains.skia.impl.**
-dontwarn org.jetbrains.skiko.**
-dontwarn org.jetbrains.compose.**
-dontwarn dev.datlag.**
-dontwarn me.friwi.**
-dontwarn org.cef.**
-dontwarn com.jetbrains.cef.**
-dontwarn uk.co.caprica.**
-dontwarn com.github.sarxos.**
-dontwarn com.sun.jna.**
-dontwarn org.json.**
-dontwarn kotlin.reflect.jvm.internal.**
-dontwarn java.awt.**
-dontwarn sun.**
-dontwarn jdk.**
# Глобально игнорируем оставшиеся unresolved — иначе единичный transitive
# JNI-stub валит весь обфускацию.
-ignorewarnings

# ── Точка входа ──────────────────────────────────────────────────────────
# Compose-DSL ищет main() reflection'ом — имя класса должно остаться.
-keep class com.example.otlhelper.desktop.MainKt {
    public static void main(java.lang.String[]);
}
-keep class com.example.otlhelper.desktop.BuildInfo { *; }

# ── Kotlin metadata + reflection ─────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.coroutines.Continuation
-keepclassmembers class kotlin.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**

# kotlinx.coroutines использует ServiceLoader для Dispatchers.Main.
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.coroutines.swing.SwingDispatcherFactory { *; }
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep interface kotlinx.coroutines.CoroutineExceptionHandler { *; }

# ── Compose runtime / Material / UI ──────────────────────────────────────
# JetBrains official Compose-Multiplatform proguard recommendations.
-keep class androidx.compose.** { *; }
-keep class org.jetbrains.compose.** { *; }
-keep class org.jetbrains.skia.** { *; }
-keep class org.jetbrains.skiko.** { *; }
-dontwarn androidx.compose.**
-dontwarn org.jetbrains.compose.**
-dontwarn org.jetbrains.skia.**
-dontwarn org.jetbrains.skiko.**

# @Composable функции / state holders — переименовывать нельзя, рантайм
# инспектит имена для Compose tooling.
-keepclasseswithmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ── KCEF / JCEF / Chromium native bridge ─────────────────────────────────
# JNI-вызовы native → Java идут по имени класса/метода. Обфускация ломает
# loadCEFLibraries и весь embedded Chromium.
-keep class dev.datlag.kcef.** { *; }
-keep class org.cef.** { *; }
-keep class me.friwi.jcefmaven.** { *; }
-keep class com.jetbrains.cef.** { *; }
-dontwarn dev.datlag.kcef.**
-dontwarn org.cef.**
-dontwarn me.friwi.jcefmaven.**

# ── JNA / native libraries ───────────────────────────────────────────────
# JNA маппит Java-интерфейсы на shared library exports по имени метода.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep class * extends com.sun.jna.Union { *; }
-keep class com.sun.jna.platform.** { *; }
-dontwarn com.sun.jna.**

# Наша WKWebView ObjC-bridge через JNA.
-keep class com.example.otlhelper.desktop.sheets.** { *; }

# ── VLCJ — JNA-bridge к libVLC ───────────────────────────────────────────
-keep class uk.co.caprica.** { *; }
-dontwarn uk.co.caprica.**

# ── sarxos webcam-capture — нативные backends ────────────────────────────
-keep class com.github.sarxos.** { *; }
-keep class org.bridj.** { *; }
-dontwarn com.github.sarxos.**
-dontwarn org.bridj.**

# ── OkHttp / Okio ────────────────────────────────────────────────────────
# OkHttp поставляет свои consumer-rules в JAR'е — всё уже учтено, но
# duplicate-keep безопасен.
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ── org.json — рефлексия на JSONObject getters ───────────────────────────
-keep class org.json.** { *; }
-dontwarn org.json.**

# ── Compottie / Lottie ───────────────────────────────────────────────────
-keep class io.github.alexzhirkevich.compottie.** { *; }
-dontwarn io.github.alexzhirkevich.compottie.**

# ── Наш domain-код ───────────────────────────────────────────────────────
# Shared API constants / DTO — в JSON-сериализации фигурируют их строковые
# значения констант, но JSON не тянет имена полей классов (мы хардкодим
# string-keys через ApiFields). Можно обфусцировать смело.
#
# Однако SessionStore.save/load использует property-name'ы в .properties
# файле (token=, login=). Если мы их не используем reflection'ом, всё OK —
# имена ключей хранятся как String literals и ProGuard их не трогает.
#
# Для подстраховки сохраняем enum'ы — Compose использует name() для save:
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Сериализуемые data-классы (если будут использоваться через kotlinx.serialization).
-keepclassmembers class **.shared.api.** { *; }

# ── §TZ-2.4.1 — Google Tink (E2E encryption) ─────────────────────────────
# Tink subtle API — pure Java, без consumer-rules в JAR. Без keep rules
# ProGuard релиза удалит/переименует X25519/Hkdf → E2EInterceptor.encryptRequest
# silently throws → запрос идёт plaintext → server enforce returns HTTP 426
# `e2e_required`. 0.6.0 release был сломан именно из-за этого.
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ── §TZ-2.4.1 — наши security / network / metrics классы ─────────────────
# E2EInterceptor / E2ECrypto / RouteState / NetworkMetricsBuffer — ProGuard
# может inlin'ить или удалять, и тогда interceptor chain становится пустой
# или metrics не льются. Защищаем явно.
-keep class com.example.otlhelper.desktop.core.security.** { *; }
-keep class com.example.otlhelper.desktop.core.network.** { *; }
-keep class com.example.otlhelper.desktop.core.metrics.** { *; }
-keep class com.example.otlhelper.desktop.data.network.** { *; }
-keep class com.example.otlhelper.desktop.data.security.** { *; }

# ── §TZ-DESKTOP-0.10.2 — Windows-ROOT TrustManager (SunMSCAPI provider) ──
# В 0.10.1 в логе на Windows 11 наблюдался:
#   "Windows-ROOT load failed: KeyStoreException: Windows-ROOT not found"
# причина: ProGuard выкидывал sun.security.mscapi.* классы которые JVM
# использует для Windows-ROOT KeyStore (они грузятся через SecurityManager
# по string-имени). Без них KeyStore.getInstance("Windows-ROOT") падает.
#
# Корп-AV (Касперский Endpoint) делает TLS interception с своим CA cert
# который импортирован в Windows trust store через GPO. Без Windows-ROOT
# Java cacerts не валидирует chain → SSLHandshakeException PKIX path.
# В 0.10.1 для sslip.io (LE cert) JVM cacerts хватает, но если корп решит
# MITM'ить sslip — упадёт. Защищаем заранее.
-keep class sun.security.mscapi.** { *; }
-keep class sun.security.provider.** { *; }
-keepclassmembers class sun.security.mscapi.** { *; }
-dontwarn sun.security.mscapi.**

# Windows-ROOT KeyStore Service registered through Provider mechanism —
# нельзя обфусцировать.
-keep class * extends java.security.Provider { *; }
-keep class * extends java.security.KeyStoreSpi { *; }
-keepnames class * extends java.security.KeyStoreSpi

# §TZ-0.10.5 — nayuki/qrcodegen для QR rendering на login screen.
-keep class io.nayuki.qrcodegen.** { *; }
-dontwarn io.nayuki.qrcodegen.**

# ── Финальная зачистка ───────────────────────────────────────────────────
# Удаляем log-statements в release (мелкая защита от reverse-engineering
# через runtime-print).
-assumenosideeffects class kotlin.io.ConsoleKt {
    public static *** println(...);
    public static *** print(...);
}
-assumenosideeffects class java.io.PrintStream {
    public *** println(...);
    public *** print(...);
}
