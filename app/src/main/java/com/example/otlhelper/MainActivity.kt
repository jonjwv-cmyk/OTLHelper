package com.example.otlhelper

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.otlhelper.BuildConfig
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.otlhelper.core.auth.AuthEvents
import com.example.otlhelper.core.security.BiometricLockManager
import com.example.otlhelper.core.theme.BgApp
import com.example.otlhelper.core.theme.OtlTheme
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.ThemeMode
import com.example.otlhelper.presentation.changepassword.ChangePasswordScreen
import com.example.otlhelper.presentation.home.HomeScreen
import com.example.otlhelper.presentation.login.LoginScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// FragmentActivity (супер-класс ComponentActivity) нужен для androidx.biometric.
// Никаких других изменений в поведении — BiometricPrompt требует этот класс.
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var session: SessionManager

    @Inject
    lateinit var biometricLockManager: BiometricLockManager

    @Inject
    lateinit var appSettings: com.example.otlhelper.core.settings.AppSettings

    // Android 13+: POST_NOTIFICATIONS is a runtime permission. Without an
    // explicit grant the system silently drops every nm.notify() call — which
    // looked like "pushes don't work" even though FCM was delivering fine.
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i("MainActivity", "POST_NOTIFICATIONS granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install the splash screen BEFORE super.onCreate so the system never
        // flashes the default launcher-icon splash. The splash uses our
        // Theme.App.Starting, whose icon drawable is the same color as the
        // background — i.e. invisible.
        val splash = installSplashScreen()
        // Keep the system splash on only for a tiny window (until Compose is
        // ready to draw our own Lottie welcome overlay). Once the first frame
        // is composed, setKeepOnScreenCondition returns false and the system
        // splash dismisses with a smooth cross-fade to our content.
        var contentReady = false
        splash.setKeepOnScreenCondition { !contentReady }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // FLAG_SECURE — блокирует системные скриншоты и скрывает превью
        // приложения в recents на lock screen. Только в release builds:
        // debug-сборки оставляем без флага чтобы скриншоты для баг-репортов
        // продолжали работать. Приложение — корпоративное с личными
        // данными в чатах (§3.14 privacy), поэтому уровень защиты — all-app,
        // без per-screen переключений.
        if (!BuildConfig.DEBUG) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        // Ask for notification permission on first launch (Android 13+).
        // No-op on older versions or when the user has already granted/denied
        // permanently — Android itself rate-limits repeat prompts.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val perm = android.Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, perm) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(perm)
            }
        }
        // Release the splash as soon as onCreate finishes setting content
        window.decorView.post { contentReady = true }

        // Presence lifecycle — глобально следим, в foreground/background ли
        // приложение. На переходе шлём heartbeat с новым app_state, чтобы
        // сервер мгновенно перевёл presence (online↔paused) — без него
        // пользователь висел зелёным онлайн пока процесс не убит.
        val presenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        // §TZ-2.3.36 Phase 5 — фиксируем момент ухода в фон для
                        // биометрического re-lock'а по таймауту.
                        biometricLockManager.onBackgrounded()
                    }
                    Lifecycle.Event.ON_START -> {
                        // Если юзер в Settings выбрал lock-on-resume > 0 и прошло
                        // больше этого порога — BiometricLockManager выставит
                        // needsUnlock=true, Composable ниже среагирует.
                        biometricLockManager.checkResumeLock(appSettings.lockOnResumeSeconds)
                    }
                    else -> {}
                }
                val newState = when (event) {
                    Lifecycle.Event.ON_START -> "foreground"
                    Lifecycle.Event.ON_STOP -> "background"
                    else -> return@LifecycleEventObserver
                }
                if (com.example.otlhelper.core.lifecycle.AppPresence.set(newState)) {
                    presenceScope.launch {
                        runCatching { ApiClient.heartbeat(newState) }
                    }
                }
            }
        )

        setContent {
            // Тема — реактивный стейт, меняется мгновенно из SettingsDialog.
            var themeMode by remember { mutableStateOf(appSettings.themeMode) }

            OtlTheme(themeMode = themeMode) {
                val navController = rememberNavController()

                // SF-2026 §3.15.a.Д — биометрический gate на холодный старт +
                // §TZ-2.3.36 Phase 5 — re-lock на resume по таймауту (если юзер
                // включил в Settings lockOnResumeSeconds > 0). Observer
                // biometricLockManager.needsUnlock триггерит lock UI обратно.
                val loggedIn = session.isLoggedIn()
                val needsColdBiometric = loggedIn && biometricLockManager.shouldPromptOnColdStart()
                val needsUnlockFromResume by biometricLockManager.needsUnlock.collectAsStateWithLifecycle()
                var biometricUnlocked by remember { mutableStateOf(!needsColdBiometric) }
                var biometricError by remember { mutableStateOf<String?>(null) }

                // Re-lock при возврате из фона: когда needsUnlock становится true.
                LaunchedEffect(needsUnlockFromResume) {
                    if (needsUnlockFromResume && biometricUnlocked) {
                        biometricUnlocked = false
                    }
                }

                LaunchedEffect(biometricUnlocked) {
                    if (!biometricUnlocked && loggedIn && biometricLockManager.isEnabled()) {
                        biometricLockManager.prompt(
                            activity = this@MainActivity,
                            onSuccess = {
                                biometricUnlocked = true
                                biometricError = null
                            },
                            onError = { _, message -> biometricError = message }
                        )
                    }
                }

                // Force-logout on server-side token death. Without this the
                // UI sits in a zombie state where every action silently
                // fails with 401 (token_revoked etc.) — user thinks
                // features are broken, in fact the session just expired.
                val ctx = androidx.compose.ui.platform.LocalContext.current
                LaunchedEffect(Unit) {
                    AuthEvents.events.collect { reason ->
                        Log.w("MainActivity", "Auth event: $reason — forcing logout")
                        val message = when (reason) {
                            "token_revoked" -> "Выполнен вход на другом устройстве"
                            "token_expired" -> "Сессия истекла — войдите снова"
                            "password_reset" -> "Пароль был изменён — войдите снова"
                            else -> "Войдите снова"
                        }
                        android.widget.Toast.makeText(
                            ctx, message, android.widget.Toast.LENGTH_LONG
                        ).show()
                        ApiClient.clearAuth()
                        session.clearSession()
                        navController.navigate("login") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }

                // Determine start destination based on session state
                val startDestination = when {
                    !session.isLoggedIn() -> "login"
                    session.mustChangePassword() -> "change_password?forced=true"
                    else -> "home"
                }

                if (!biometricUnlocked) {
                    BiometricLockScreen(
                        errorMessage = biometricError,
                        onRetry = {
                            biometricError = null
                            biometricLockManager.prompt(
                                activity = this@MainActivity,
                                onSuccess = { biometricUnlocked = true },
                                onError = { _, message -> biometricError = message }
                            )
                        }
                    )
                    return@OtlTheme
                }

                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable("login") {
                        LoginScreen(
                            onNavigateToHome = {
                                navController.navigate("home") {
                                    popUpTo("login") { inclusive = true }
                                }
                            },
                            onNavigateToChangePassword = {
                                navController.navigate("change_password?forced=true") {
                                    popUpTo("login") { inclusive = true }
                                }
                            }
                        )
                    }

                    composable("home") {
                        HomeScreen(
                            onNavigateToLogin = {
                                navController.navigate("login") {
                                    popUpTo("home") { inclusive = true }
                                }
                            },
                            onNavigateToChangePassword = {
                                navController.navigate("change_password?forced=false")
                            },
                            onThemeChange = { themeMode = it },
                        )
                    }

                    composable("change_password?forced={forced}") { backStackEntry ->
                        val forced = backStackEntry.arguments?.getString("forced") == "true"
                        ChangePasswordScreen(
                            forced = forced,
                            onNavigateToHome = {
                                navController.navigate("home") {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                            onCancel = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Экран-заглушка пока биометрия не подтверждена (§3.15.a.Д). Кнопка «Повторить»
 * открывает BiometricPrompt снова — например, если пользователь промахнулся.
 */
@androidx.compose.runtime.Composable
private fun BiometricLockScreen(errorMessage: String?, onRetry: () -> Unit) {
    // §TZ-2.3.38 — текст в SF-2026 стиле, центрирован, с нормальными переносами
    // по смыслу (не обрезается на узких экранах, держит воздух).
    Column(
        modifier = Modifier.fillMaxSize().background(BgApp).padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🔒", fontSize = 64.sp)
        Text(
            "Подтвердите вход",
            color = TextPrimary,
            fontSize = 20.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            "Используйте отпечаток пальца или распознавание лица",
            color = TextSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
        if (errorMessage != null) {
            Text(
                errorMessage,
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(),
            modifier = Modifier.padding(top = 28.dp),
        ) {
            Text("Повторить")
        }
    }
}
