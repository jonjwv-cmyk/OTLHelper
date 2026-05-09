package com.example.otlhelper.desktop.sheets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.otlhelper.desktop.data.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * §TZ-DESKTOP-0.10.13 — Sheets registry, **загружается с сервера**.
 *
 * До 0.10.12 здесь были hardcoded:
 *   • Spreadsheet IDs и tab gids
 *   • Apps Script URLs (script.google.com/macros/s/AKfycb...)
 *   • Hardcoded passwords (action.requiresPassword)
 * После публикации репо как public — всё чувствительное вынесено на сервер
 * (`server-modular/sheets-registry.js`). Клиент дёргает action
 * `get_client_config` после login и получает sanitized JSON: ids/gids/labels
 * без URL'ов и без plaintext паролей (только bool флаг `requiresPassword`).
 *
 * Lifecycle:
 *   1. App start → SheetsRegistry.state = Loading
 *   2. После login → App.kt вызывает loadFromServer() → state = Loaded
 *   3. UI наблюдает state, рендерит TabStrip когда Loaded
 *   4. На logout → reset() → state снова Loading
 *   5. На re-login → loadFromServer() заново
 */

/** Один Apps Script триггер (UI-кнопка в TopBar). */
data class SheetAction(
    /** Стабильный ID для server-side dispatch (`run_script` шлёт сюда action_id). */
    val id: String,
    val label: String,
    val icon: ImageVector,
    /** Если non-null — показать confirm-dialog с этим заголовком перед запуском. */
    val confirmTitle: String? = null,
    /**
     * §TZ-DESKTOP-0.10.13 — Boolean (раньше String? с plaintext password).
     * Сервер хранит реальный пароль в sheets-registry.js, валидирует при
     * `run_script`. Клиент только знает что нужен password input, не сам
     * пароль.
     */
    val requiresPassword: Boolean = false,
    /**
     * §TZ-DESKTOP-0.10.13 — Boolean (раньше String? с statusUrl). Polling
     * статуса делается через server endpoint `check_sheet_action_status`.
     */
    val hasStatusUrl: Boolean = false,
    /**
     * Список rawName'ов листов которые блокируются пока action работает.
     * UI behaviour: на listed tabs показывается overlay + actions disabled.
     */
    val locksTabs: List<String> = emptyList(),
)

/** Статически зарегистрированный лист с захардкоженным gid. */
data class StaticTab(
    val gid: Long,
    val rawName: String,
    /** Что видит юзер на пилюле. null = берём rawName. */
    val displayName: String? = null,
    val hidden: Boolean = false,
    val actions: List<SheetAction> = emptyList(),
)

/** Готовая к показу в UI вкладка. */
data class SheetTab(
    val gid: Long,
    val label: String,
    val originalName: String,
    val actions: List<SheetAction> = emptyList(),
)

data class SheetsFile(
    /** Google Drive document ID. */
    val id: String,
    /** Human-readable заголовок. */
    val title: String,
    /** Короткая иконка (legacy). */
    val emoji: String,
    /** Все листы файла (включая hidden). */
    val staticTabs: List<StaticTab>,
) {
    /** Видимые листы (hidden=false), готовые для UI. */
    fun visibleTabs(): List<SheetTab> = staticTabs
        .filter { !it.hidden }
        .map { st ->
            SheetTab(
                gid = st.gid,
                label = st.displayName ?: st.rawName,
                originalName = st.rawName,
                actions = st.actions,
            )
        }

    fun firstVisibleGid(): Long = staticTabs.firstOrNull { !it.hidden }?.gid ?: 0L

    fun firstTabUrl(): String =
        "https://docs.google.com/spreadsheets/d/$id/edit#gid=${firstVisibleGid()}"

    fun defaultUrl(): String = "https://docs.google.com/spreadsheets/d/$id/edit"
}

/**
 * §TZ-DESKTOP-0.10.13 — Stateful registry, populated from server after login.
 *
 * До этого был `object SheetsRegistry { val WORKFLOW = SheetsFile(...) }` —
 * compile-time singleton. Теперь данные приходят с сервера, поэтому
 * registry — stateful holder с `StateFlow<List<SheetsFile>>`.
 *
 * Существующие consumers вызывают `SheetsRegistry.WORKFLOW`, `OTIF5`, `files`.
 * Эти accessor'ы теперь читают current state. Если state ещё пустой
 * (config не загружен) — возвращают `null` или пустой список соответственно.
 *
 * `EMPTY_FALLBACK` — заглушка с теми же IDs что были в старом hardcode.
 * Не используется в production (сервер всегда отвечает) — нужен только
 * для legacy-кода который не выдержит null. Будет удалён после полного
 * перехода consumers на nullable API.
 */
object SheetsRegistry {

    private val _files = MutableStateFlow<List<SheetsFile>>(emptyList())
    val files: StateFlow<List<SheetsFile>> = _files.asStateFlow()

    /** True если хотя бы один раз успешно загрузили с сервера. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Lookup по title (`"WORKFLOW"`, `"OTIF5"`). Null если нет/не загружено. */
    fun byTitle(title: String): SheetsFile? = _files.value.firstOrNull { it.title == title }

    /** Lookup по spreadsheet ID. */
    fun byId(id: String): SheetsFile? = _files.value.firstOrNull { it.id == id }

    /**
     * Backward-compat accessors для legacy-кода. После загрузки с сервера
     * возвращают актуальный SheetsFile; до загрузки — null.
     *
     * NB: всё больше consumer'ов должны переехать на `byTitle()` чтобы
     * корректно обрабатывать loading-state. Эти shortcut'ы — на переходный
     * период.
     */
    val WORKFLOW: SheetsFile? get() = byTitle("WORKFLOW")
    val OTIF5: SheetsFile? get() = byTitle("OTIF5")

    /** Все файлы (тот же что `files.value`). Для legacy-кода. */
    val filesList: List<SheetsFile> get() = _files.value

    /**
     * §TZ-DESKTOP-0.10.13 — Загрузить config с сервера. Вызывается из App.kt
     * после успешного login. На logout call `reset()`, на re-login снова
     * loadFromServer().
     *
     * Returns true если config успешно получен и распарсен.
     */
    suspend fun loadFromServer(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val resp = ApiClient.request("get_client_config") { /* empty body */ }
            if (!resp.optBoolean("ok", false)) return@withContext false
            val config = resp.optJSONObject("config") ?: return@withContext false
            val parsed = parseConfig(config)
            _files.value = parsed
            _loaded.value = true
            true
        }.getOrElse { e ->
            System.err.println("[OTLD][registry] loadFromServer failed: ${e.message}")
            false
        }
    }

    /** Сбросить state (на logout). Следующий loadFromServer заполнит заново. */
    fun reset() {
        _files.value = emptyList()
        _loaded.value = false
    }

    /** Парсит JSON ответа сервера в список SheetsFile. */
    private fun parseConfig(config: JSONObject): List<SheetsFile> {
        val filesArr = config.optJSONArray("files") ?: return emptyList()
        val out = mutableListOf<SheetsFile>()
        for (i in 0 until filesArr.length()) {
            val fileObj = filesArr.optJSONObject(i) ?: continue
            val tabsArr = fileObj.optJSONArray("tabs") ?: JSONArray()
            val tabs = (0 until tabsArr.length()).mapNotNull { j ->
                val tabObj = tabsArr.optJSONObject(j) ?: return@mapNotNull null
                val actsArr = tabObj.optJSONArray("actions") ?: JSONArray()
                val actions = (0 until actsArr.length()).mapNotNull { k ->
                    val a = actsArr.optJSONObject(k) ?: return@mapNotNull null
                    SheetAction(
                        id = a.optString("id"),
                        label = a.optString("label"),
                        icon = iconFromString(a.optString("icon")),
                        confirmTitle = a.optString("confirmTitle").takeIf { it.isNotEmpty() },
                        requiresPassword = a.optBoolean("requiresPassword", false),
                        hasStatusUrl = a.optBoolean("hasStatusUrl", false),
                        locksTabs = a.optJSONArray("locksTabs")?.let { arr ->
                            (0 until arr.length()).map { arr.optString(it) }
                        } ?: emptyList(),
                    )
                }
                StaticTab(
                    gid = tabObj.optLong("gid"),
                    rawName = tabObj.optString("rawName"),
                    displayName = tabObj.optString("displayName").takeIf { it.isNotEmpty() },
                    hidden = tabObj.optBoolean("hidden", false),
                    actions = actions,
                )
            }
            out.add(SheetsFile(
                id = fileObj.optString("id"),
                title = fileObj.optString("title"),
                emoji = fileObj.optString("emoji"),
                staticTabs = tabs,
            ))
        }
        return out
    }

    /** Maps server-side icon string id → Material ImageVector. */
    private fun iconFromString(name: String): ImageVector = when (name) {
        "swap_vert" -> Icons.Outlined.SwapVert
        "sync" -> Icons.Outlined.Sync
        "play_arrow" -> Icons.Outlined.PlayArrow
        "cloud_download" -> Icons.Outlined.CloudDownload
        "functions" -> Icons.Outlined.Functions
        else -> Icons.Outlined.PlayArrow  // default fallback
    }
}
