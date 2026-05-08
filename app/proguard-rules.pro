# =============================================================================
# OTL Helper — R8 / ProGuard rules for release builds
# =============================================================================
# Most of our dependencies ship their own consumer ProGuard rules in their
# AARs (Compose, Hilt, Room, Firebase, Media3, Coil, Lottie, OkHttp,
# kotlinx-coroutines). Everything below covers the remaining gaps.

# Preserve useful stack-trace metadata. Crashlytics / Google Play Console
# uploads the mapping.txt separately; these attributes let symbolication
# work even before the mapping is applied.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# -----------------------------------------------------------------------------
# Kotlin
# -----------------------------------------------------------------------------
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# -----------------------------------------------------------------------------
# SQLCipher 4.9.0 (JNI entry points)
# -----------------------------------------------------------------------------
# sqlcipher-android looks up JNI classes by name; obfuscating them breaks
# the native <-> Java bridge at runtime (NoSuchMethodError / UnsatisfiedLinkError).
-keep class net.sqlcipher.** { *; }
-keep class net.zetetic.database.** { *; }

# -----------------------------------------------------------------------------
# Media3 / ExoPlayer — extra safety for reflective MediaSource lookup
# -----------------------------------------------------------------------------
# The AARs keep the core classes, but the datasource / extractor factories
# resolve some implementations by class name — keep a broad surface so
# runtime media pipelines don't NPE after shrinking.
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.extractor.** { *; }
-keep class androidx.media3.datasource.** { *; }
-dontwarn androidx.media3.**

# -----------------------------------------------------------------------------
# Firebase Messaging
# -----------------------------------------------------------------------------
# Firebase handles itself via consumer rules; this is a belt-and-braces
# keep for the service we register in the manifest.
-keep class com.example.otlhelper.core.push.OtlFirebaseMessagingService { *; }

# -----------------------------------------------------------------------------
# Our JSON-backed UiState (we inspect JSONObject / JSONArray via org.json —
# those are platform classes, no rule needed). Nothing else to keep here.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# §TZ-2.3.31 Phase 4c — Aggressive obfuscation.
# -----------------------------------------------------------------------------
# NOTE 2.3.31+: `-repackageclasses 'a'` + `-allowaccessmodification` ломало
# Coil 3 fetcher pipeline на НЕзашифрованных URL'ах (старые медиа/аватары
# переставали грузиться). Оставляем стандартный R8 full-mode без
# aggressive renaming — безопаснее, а обфускацию имён R8 и так делает.
# -repackageclasses 'a'
# -allowaccessmodification

# Custom Coil Fetcher / ExoPlayer DataSource регистрируются через reflection
# (SingletonImageLoader.Factory) — keep чтобы не отвалилось после shrink.
-keep class com.example.otlhelper.core.security.EncBlobFetcher { *; }
-keep class com.example.otlhelper.core.security.EncBlobFetcher$Factory { *; }
-keep class com.example.otlhelper.core.security.EncBlobDataSource { *; }
-keep class com.example.otlhelper.core.security.EncBlobDataSourceFactory { *; }

# §TZ-2.3.31 — Coil 3 fetcher/mapper pipeline. Без этого R8 аггрессивный
# ломал fallback-цепочку Uri→NetworkRequest→OkHttpNetworkFetcher для
# НЕзашифрованных URL'ов (старые аватары/медиа переставали грузиться).
-keep class coil3.** { *; }
-keep interface coil3.** { *; }
-keep class coil3.network.** { *; }
-keep class coil3.network.okhttp.** { *; }
-dontwarn coil3.**

# OkHttp — consumer rules в AAR есть, но R8 full-mode + repackageclasses
# ломал что-то в Call/Dispatcher пути. Keep публичную поверхность.
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# HttpClientFactory lambdas — callFactory = { ... } внутри
# OkHttpNetworkFetcherFactory ctor. После repackage lambda могла терять
# ссылку на imageClient(). Keep factory целиком.
-keep class com.example.otlhelper.data.network.HttpClientFactory { *; }
-keep class com.example.otlhelper.data.network.HttpClientFactory$* { *; }

# IntegrityGuard + BuildConfig поле EXPECTED_SIGNING_SHA256 — нельзя inline'ить
# или удалять, иначе tamper-check превращается в no-op.
-keep class com.example.otlhelper.BuildConfig { *; }
-keep class com.example.otlhelper.core.security.IntegrityGuard { *; }

# -----------------------------------------------------------------------------
# Release hardening — strip debug surface from production APKs so anyone
# pulling an OTA build can't trivially inspect runtime state.
# -----------------------------------------------------------------------------
# Strip all android.util.Log calls at the R8 optimisation pass. Crash
# telemetry reaches the server via Telemetry.reportCrash, which is
# deliberate; logcat leakage of query strings, tokens, bubble rects etc.
# from production builds is not. Works only with
# proguard-android-OPTIMIZE.txt (already enabled).
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static int println(...);
}

# Belt and braces — strip our own Telemetry-side System.out in case it
# ever leaks through. (Standard output in a release APK is either
# redirected or written to logcat anyway; we don't use it, but the rule
# costs nothing.)
-assumenosideeffects class java.io.PrintStream {
    public void println(%);
    public void println(**);
}

# Kotlin intrinsic parameter null-checks (checkParameterIsNotNull etc.)
# throw on hostile callers but also leak parameter names in the stack
# trace. Strip them from release so `hackers` staring at our APK don't
# see "$this$apply", "url$iv", argument labels etc.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkParameterIsNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkExpressionValueIsNotNull(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkReturnedValueIsNotNull(...);
    public static void checkFieldIsNotNull(...);
    public static void throwUninitializedPropertyAccessException(...);
}
