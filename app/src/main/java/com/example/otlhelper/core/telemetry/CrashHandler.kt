package com.example.otlhelper.core.telemetry

/**
 * Ловушка неперехваченных исключений. Устанавливается в `OtlApp.onCreate()`.
 *
 * Поведение (§3.14 паспорта):
 *  1. Формирует LogError через [Telemetry.reportCrash] (локальная очередь).
 *  2. Делегирует управление ОРИГИНАЛЬНОМУ handler'у — чтобы процесс всё равно
 *     завершился как ожидается, Logcat получил stacktrace, ANR/Play Console
 *     увидели крэш штатно.
 *
 * Запись в очередь синхронна (под капотом `Dispatchers.IO.launch`), но процесс
 * может умереть до того, как PendingActionFlusher доставит её на сервер —
 * это окей, при следующем запуске запись всё ещё в Room и улетит.
 */
class CrashHandler(
    private val telemetry: Telemetry,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            telemetry.reportCrash(throwable)
        } catch (_: Throwable) {
            // Падение внутри reporter не должно перебивать оригинальный крэш.
        }
        // Всегда отдаём дальше — не глотаем крэш.
        previous?.uncaughtException(thread, throwable)
            ?: run {
                // Если default'а нет (нетипично) — завершаем процесс явно.
                android.os.Process.killProcess(android.os.Process.myPid())
            }
    }

    companion object {
        /** Устанавливает глобальный обработчик. Идемпотентно — повторные вызовы безопасны. */
        fun install(telemetry: Telemetry) {
            val existing = Thread.getDefaultUncaughtExceptionHandler()
            // Защита от двойной установки: если уже наш handler — ничего не делаем.
            if (existing is CrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(telemetry, existing))
        }
    }
}
