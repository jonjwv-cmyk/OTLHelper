package com.example.otlhelper.core.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SF-2026 §3.15.a.Д — биометрический замок на вход после первого логина.
 *
 * Контракт:
 *  1. Пользователь включил toggle в настройках → [setEnabled](true).
 *  2. Следующий холодный старт приложения на залогиненном устройстве —
 *     [shouldPromptOnColdStart] == true → UI показывает `BiometricPrompt`.
 *  3. Успех → открытие главного экрана как обычно.
 *  4. Отказ / ошибка → экран блокировки «Требуется биометрия» без контента.
 *
 * Логин/пароль нужен только при первом входе или после token_revoked
 * (AuthEvents уже обрабатывает). Биометрия — это второй фактор ПОВЕРХ
 * уже существующей сессии, а не замена.
 *
 * Ключ шифрования / secret — не нужен в MVP: сессия-токен уже лежит в
 * SharedPreferences (не в Keystore), биометрия это UX-фильтр. Для полной
 * защиты от извлечения токена — отдельная итерация с AndroidX Security
 * EncryptedSharedPreferences + Keystore-bound key.
 */
@Singleton
class BiometricLockManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("otl_biometric", Context.MODE_PRIVATE)

    /** Пользователь включил биометрический замок в настройках. */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Доступна ли биометрия на устройстве и зарегистрирована ли
     * (FaceID/отпечаток настроен в системе). Пробуем сильную биометрию
     * с фолбэком на device credential (PIN/pattern) где это поддерживается;
     * на старых API — только биометрию.
     */
    fun canAuthenticate(): Boolean {
        val manager = BiometricManager.from(context)
        val result = manager.canAuthenticate(authenticators())
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Нужно ли показывать BiometricPrompt прямо сейчас (на холодном старте).
     * `true` только когда: enabled + биометрия доступна + сессия текущая
     * не прошла проверку в этом процессе.
     */
    fun shouldPromptOnColdStart(): Boolean =
        isEnabled() && canAuthenticate() && !sessionVerified

    /**
     * Флаг «в этом процессе биометрия уже успешно подтверждена» — чтобы
     * после первого успеха на старте приложения не спрашивать снова при
     * каждой навигации. Сбрасывается при перезапуске процесса.
     */
    @Volatile private var sessionVerified: Boolean = false

    fun markVerified() {
        sessionVerified = true
        lastVerifiedMs = System.currentTimeMillis()
        _needsUnlock.value = false
    }

    /**
     * §TZ-2.3.36 Phase 5 — блокировка на resume из background.
     * Вызывается при `ON_STOP` (приложение уходит в фон).
     */
    fun onBackgrounded() {
        lastBackgroundedMs = System.currentTimeMillis()
    }

    /**
     * Вызывается при `ON_START` (возврат из фона). Если юзер включил
     * `lockOnResumeSeconds > 0` и прошло больше этого времени в фоне —
     * инвалидируем сессию → `needsUnlock` → true → UI показывает biometric.
     */
    fun checkResumeLock(lockAfterSeconds: Int) {
        if (lockAfterSeconds <= 0) return
        if (!isEnabled()) return
        val bg = lastBackgroundedMs
        if (bg <= 0) return
        val elapsedSec = (System.currentTimeMillis() - bg) / 1000
        if (elapsedSec >= lockAfterSeconds) {
            sessionVerified = false
            _needsUnlock.value = canAuthenticate()
        }
    }

    /**
     * StateFlow для Compose: `true` когда UI должен показать biometric lock.
     * Обновляется из lifecycle observer'а через [checkResumeLock] и [markVerified].
     */
    private val _needsUnlock = MutableStateFlow(false)
    val needsUnlock: StateFlow<Boolean> = _needsUnlock.asStateFlow()

    @Volatile private var lastVerifiedMs: Long = 0
    @Volatile private var lastBackgroundedMs: Long = 0

    /**
     * Показать BiometricPrompt. Вызывать из FragmentActivity (MainActivity).
     *
     * Любые неожиданные исключения от `BiometricPrompt` ловим и проталкиваем
     * как `onError` — никогда не даём упасть всему приложению из-за
     * несовместимой конфигурации на конкретном устройстве.
     */
    fun prompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (code: Int, message: String) -> Unit = { _, _ -> },
    ) {
        try {
            val executor = ContextCompat.getMainExecutor(activity)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    markVerified()
                    onSuccess()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errorCode, errString.toString())
                }
            }
            val prompt = BiometricPrompt(activity, executor, callback)
            val auth = authenticators()
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("OTL Helper")
                .setSubtitle("Подтвердите вход")
                .setAllowedAuthenticators(auth)
                .apply {
                    // Контракт androidx.biometric:
                    //   setNegativeButtonText ОБЯЗАТЕЛЕН, если DEVICE_CREDENTIAL
                    //   НЕ в allowedAuthenticators, и ЗАПРЕЩЁН, если в них.
                    // Нарушение ⇒ IllegalArgumentException при build() / authenticate().
                    if ((auth and BiometricManager.Authenticators.DEVICE_CREDENTIAL) == 0) {
                        setNegativeButtonText("Отмена")
                    }
                }
                .build()
            prompt.authenticate(info)
        } catch (t: Throwable) {
            Log.w(TAG, "BiometricPrompt failed: ${t.message}", t)
            onError(-1, t.message ?: "Биометрия недоступна")
        }
    }

    /**
     * API 30+ поддерживает `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` (фолбэк на
     * PIN/паттерн). На более ранних версиях такая комбинация либо не работает,
     * либо требует отдельный KeyguardManager-путь — проще ограничиться только
     * биометрией и показать кнопку «Отмена».
     */
    private fun authenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

    companion object {
        private const val TAG = "BiometricLock"
        private const val KEY_ENABLED = "biometric_enabled"
    }
}
