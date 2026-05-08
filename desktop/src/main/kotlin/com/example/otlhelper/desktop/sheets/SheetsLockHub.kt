package com.example.otlhelper.desktop.sheets

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * §TZ-DESKTOP 0.4.x round 12 — singleton state holder для multi-client
 * Apps Script lock.
 *
 * **Source of truth — server WS broadcast.** WsClient получает
 * `sheet_lock_acquired` / `sheet_lock_released` от Cloudflare Worker и
 * устанавливает state здесь. SheetsWorkspace observes через collectAsState
 * → overlay появляется одновременно у всех подключённых десктоп-клиентов.
 *
 * Для самого инициатора lock устанавливается синхронно при отправке
 * запроса (optimistic UI), серверный echo через WS подтверждает /
 * корректирует. После выполнения скрипта server бродкастит
 * sheet_lock_released → setLock(null) у всех.
 *
 * **Singleton** — lock global для всего приложения (один Sheets-зона
 * на десктоп). Не Composable scoped — нужно чтобы WS callbacks могли
 * доставать до state из любого места.
 */
object SheetsLockHub {

    private val _activeLock = MutableStateFlow<SheetsActionLock?>(null)
    val activeLock: StateFlow<SheetsActionLock?> = _activeLock.asStateFlow()

    fun setLock(lock: SheetsActionLock?) {
        _activeLock.value = lock
    }

    fun acquire(
        actionId: String,
        actionLabel: String,
        userName: String,
        tabName: String,
        lockedTabRawNames: List<String>,
    ) {
        _activeLock.value = SheetsActionLock(
            actionId = actionId,
            actionLabel = actionLabel,
            userName = userName,
            tabName = tabName,
            lockedTabRawNames = lockedTabRawNames,
        )
    }

    fun release(actionId: String) {
        // Release только если active lock = тот самый actionId. Если за
        // время выполнения сменился (race / multi-clients) — не трогаем.
        val current = _activeLock.value
        if (current?.actionId == actionId) {
            _activeLock.value = null
        }
    }

    /** Force clear — используется при WS reconnect или error states. */
    fun reset() {
        _activeLock.value = null
    }
}
