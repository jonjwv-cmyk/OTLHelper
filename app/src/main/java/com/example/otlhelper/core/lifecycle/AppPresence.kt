package com.example.otlhelper.core.lifecycle

/**
 * Глобальное состояние app-жизненного цикла для presence-логики.
 *
 * Сервер по полю `app_state` в heartbeat вычисляет статус пользователя
 * (foreground → online, background → paused, отсутствие — offline). Чтобы и
 * периодический heartbeat из ViewModel, и lifecycle-observer из MainActivity
 * работали с одним и тем же актуальным значением — храним его здесь, а не
 * по копии в каждом VM.
 */
object AppPresence {
    @Volatile
    var state: String = "foreground"
        private set

    /**
     * Обновить состояние. Возвращает true если значение реально поменялось —
     * вызывающая сторона может на этом основании дёрнуть немедленный heartbeat.
     */
    fun set(newState: String): Boolean {
        if (newState == state) return false
        state = newState
        return true
    }
}
