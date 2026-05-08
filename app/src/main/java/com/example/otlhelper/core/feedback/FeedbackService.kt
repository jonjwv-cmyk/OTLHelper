package com.example.otlhelper.core.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import com.example.otlhelper.core.settings.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §TZ-2.3.19 — единый сервис тактильной+звуковой обратной связи.
 *
 * **Haptic path**: всегда через `Vibrator` напрямую (не через
 * `View.performHapticFeedback`). Причина — на многих OEM сборках
 * Android 11+ константы `CONFIRM` / `GESTURE_START` обрабатываются как
 * "visual-only" → вибрация не срабатывает даже когда системный toggle
 * «Тактильная обратная связь» включён. Прямой `Vibrator.vibrate()`
 * работает единообразно на всех устройствах.
 *
 * **Sound policy** (2.3.19): звук играется ТОЛЬКО при:
 *   • `receive()` — входящее сообщение / новость
 *   • `messageSent(view)` — отправка чат-сообщения / новости
 * Все остальные haptic-вызовы (tap/confirm/warn/tick) — БЕЗ звука.
 * Юзер принимает решение: интерфейсные клики не должны щёлкать, но
 * отправка/получение сообщения — это событие, оно звучит.
 *
 * View параметр в публичных методах сохранён для совместимости с
 * callers'ами, но внутри игнорируется — вибрация идёт через Vibrator.
 */
@Singleton
class FeedbackService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
) {

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    // ── Haptic-only API (no sound) ───────────────────────────────────────────
    fun tick(view: View) = vibrate(Kind.TICK)
    fun tap(view: View)  = vibrate(Kind.TAP)
    fun confirm(view: View) = vibrate(Kind.CONFIRM)
    fun warn(view: View) = vibrate(Kind.WARN)

    fun tapRaw() = vibrate(Kind.TAP)
    fun confirmRaw() = vibrate(Kind.CONFIRM)
    fun warnRaw() = vibrate(Kind.WARN)

    // ── Haptic + sound API ───────────────────────────────────────────────────
    /** §TZ-2.3.19 — отправка chat message / news. Звук + haptic. */
    fun messageSent(view: View) {
        vibrate(Kind.CONFIRM)
        playSound(FeedbackSounds.confirmPcm)
    }

    /** §TZ-2.3.9 — входящее сообщение / новость. Звук (haptic у push-нотификации свой). */
    fun receive() {
        playSound(FeedbackSounds.receivePcm)
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun playSound(pcm: ShortArray) {
        if (!settings.uiSoundsEnabled) return
        FeedbackSounds.play(pcm)
    }

    private fun vibrate(kind: Kind) {
        if (!settings.hapticsEnabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            // §TZ-2.3.22 — амплитуды подняты до чётко ощутимого уровня.
            // Старые (8мс/amp80, 14мс/amp140) были ниже порога восприятия
            // на большинстве OEM — user сообщил что scroll-tick, tab-switch,
            // settings toggles не чувствуются. Теперь:
            //   TICK — 14мс/amp160 — лёгкий но однозначно ощущается (scroll).
            //   TAP  — 22мс/amp220 — чёткий отклик (кнопки, tab-switch, tap).
            //   CONFIRM — warm double-pulse с амплитудой 240 (send / success).
            //   WARN — длинный pulse 55мс на полной амплитуде (ошибка).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = when (kind) {
                    Kind.TICK    -> VibrationEffect.createOneShot(14, 160)
                    Kind.TAP     -> VibrationEffect.createOneShot(22, 220)
                    Kind.CONFIRM -> VibrationEffect.createWaveform(
                        longArrayOf(0, 22, 50, 28), intArrayOf(0, 220, 0, 240), -1
                    )
                    Kind.WARN    -> VibrationEffect.createOneShot(55, 255)
                }
                v.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                when (kind) {
                    Kind.TICK    -> v.vibrate(14)
                    Kind.TAP     -> v.vibrate(22)
                    Kind.CONFIRM -> v.vibrate(longArrayOf(0, 22, 50, 28), -1)
                    Kind.WARN    -> v.vibrate(55)
                }
            }
        } catch (_: Exception) {}
    }

    private enum class Kind { TICK, TAP, CONFIRM, WARN }
}
