package com.example.otlhelper.desktop.sheets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * §TZ-DESKTOP 0.4.x — реестр Google-таблиц со **статическими gid'ами**.
 *
 * **Source of truth.** Юзер в 2026-04-26 предоставил полный список листов
 * с реальными gid'ами. Захардкодены сюда. Это даёт:
 *   • Мгновенную навигацию hash-nav без зависимости от live-resolve DOM
 *     (раньше был JS poller — глючил, требовал bootstrap, гонки с CSS).
 *   • Предсказуемое поведение (что в Registry — то в UI).
 *   • Robust к изменениям Google: если переименуют классы DOM, наша
 *     навигация продолжит работать (gids не меняются у листов).
 *   • Hidden листы прячутся через flag, не через CSS-фильтр в DOM.
 *
 * **Когда добавлять новый лист:** юзер шлёт URL с gid → добавляешь
 * `StaticTab(gid, rawName, displayName?, hidden?)` в нужный файл.
 * Никакого AUTO-discovery — оно неустойчиво. Только manual control.
 */

/** Один Apps Script триггер (UI-кнопка в TopBar). */
data class SheetAction(
    /** Стабильный ID для WS lock-broadcast (`sheet_action_lock` payload). */
    val id: String,
    val label: String,
    val icon: ImageVector,
    /** Apps Script web-app URL: `https://script.google.com/macros/s/.../exec?fn=...`. */
    val scriptUrl: String,
    /** Если non-null — показать confirm-dialog с этим заголовком перед запуском. */
    val confirmTitle: String? = null,
    /**
     * Apps Script alive-endpoint (обычно `?action=alive`) — periodic poll для
     * проверки что macro ещё работает. Phase 2 server side: после run,
     * клиент периодически дёргает statusUrl; если status==alive=false →
     * считаем скрипт завершённым → broadcast unlock. Без statusUrl
     * полагаемся на fixed timeout.
     */
    val statusUrl: String? = null,
    /**
     * Если non-null — перед run требуем password input. Юзер вводит,
     * клиент локально сравнивает с этой строкой; match → run, else отказ.
     * **Не secure против reverse-engineering** (пароль вшит в клиент),
     * но матчит существующий UX Google Sheets sidebar где пароль также
     * вшит в Apps Script.
     */
    val requiresPassword: String? = null,
    /**
     * Список rawName'ов листов которые блокируются пока action работает.
     * Apps Script может писать в несколько листов — например MOL/VGH
     * sync обновляет одновременно `workflow` и `wf_import`. Если empty
     * → блокируется только тот лист на котором запущена кнопка.
     *
     * UI behaviour: на listed tabs показывается overlay + actions
     * disabled; на остальных tabs всё работает нормально.
     */
    val locksTabs: List<String> = emptyList(),
)

/**
 * Статически зарегистрированный лист. Захардкоженный gid.
 *
 * @param hidden — лист есть в файле и через прямой URL переход возможен,
 * но в нашей tab-strip не показываем (служебные листы: header, log, эмодзи).
 */
data class StaticTab(
    val gid: Long,
    val rawName: String,
    /** Что видит юзер на пилюле. null = берём rawName. */
    val displayName: String? = null,
    val hidden: Boolean = false,
    val actions: List<SheetAction> = emptyList(),
)

/** Готовая к показу в UI вкладка (derived из [StaticTab.visibleTabs]). */
data class SheetTab(
    val gid: Long,
    /** Что видит юзер на пилюле. */
    val label: String,
    /** Имя в Google (для отладки и matching). */
    val originalName: String,
    val actions: List<SheetAction> = emptyList(),
)

data class SheetsFile(
    /** Google Drive document ID. */
    val id: String,
    /** Human-readable заголовок (для file-switcher pill). */
    val title: String,
    /** Короткая иконка (legacy, теперь не используется в TopBar). */
    val emoji: String,
    /** Все листы файла (включая hidden). */
    val staticTabs: List<StaticTab>,
) {
    /** Видимые листы (hidden=false), готовые для UI. Order = как в Registry. */
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

    /** gid первого видимого листа (для file switch). */
    fun firstVisibleGid(): Long = staticTabs.firstOrNull { !it.hidden }?.gid ?: 0L

    /** URL с gid первого видимого листа (для loadURL при file switch). */
    fun firstTabUrl(): String =
        "https://docs.google.com/spreadsheets/d/$id/edit#gid=${firstVisibleGid()}"

    /** Дефолтный URL без gid. */
    fun defaultUrl(): String = "https://docs.google.com/spreadsheets/d/$id/edit"
}

object SheetsRegistry {

    /**
     * §TZ-DESKTOP 0.4.x — WORKFLOW: основная рабочая таблица.
     *
     * gid'ы захардкожены 2026-04-26 на основе данных юзера. Если Google
     * переименует листы — gid'ы не изменятся, навигация продолжит работать.
     *
     * Hidden листы (🚚, workflow, 💩, LOG) — служебные/header'ы. Не
     * показываем в tab-strip; для прямого перехода доступны через URL.
     *
     * tabActions сейчас пустые — заполнятся в commit 6 (Apps Script
     * интеграция + WS lock-broadcast).
     */
    val WORKFLOW = SheetsFile(
        id = "1UzeJcle23W053hAZgo_wV0jy4xxzKld7yCIDRT9w4aA",
        title = "WORKFLOW",
        emoji = "🚚",
        staticTabs = listOf(
            // Лист 🚚 теперь visible — юзер дал action для него (Сортировка
            // отдельный URL от workflow листа). hidden=false чтобы юзер
            // мог переключиться и запустить.
            StaticTab(
                gid = 1982235754L,
                rawName = "🚚",
                // Юзер: «вместо имени смайлик 🚚». Только emoji, без текста.
                displayName = "🚚",
                actions = listOf(
                    SheetAction(
                        id = "truck_sort",
                        label = "Сортировка",
                        icon = Icons.Outlined.SwapVert,
                        scriptUrl = "https://script.google.com/macros/s/AKfycbyA3rmqMju_4DyKUyhQRW8VXi7Isbu7P-U_v4Iq6gx7SRn6sL7Iti63MnSZcS3MtRly/exec?action=run",
                    ),
                ),
            ),
            StaticTab(
                gid = 0L,
                rawName = "workflow",
                displayName = "WORKFLOW",
                actions = listOf(
                    SheetAction(
                        id = "workflow_sort",
                        label = "Сортировка",
                        icon = Icons.Outlined.SwapVert,
                        scriptUrl = "https://script.google.com/macros/s/AKfycbw6zNvGlT8fP_Wd-QavmhwaeAjoqL2yiPqGvJgyF8JEbUxjzX4Pp_if3IDBmo6dR69e/exec?action=run",
                    ),
                    SheetAction(
                        id = "workflow_mol_vgh",
                        label = "МОЛы/ВГХ",
                        icon = Icons.Outlined.Sync,
                        scriptUrl = "https://script.google.com/macros/s/AKfycbw5lfiinKJo_nTqtin3cvH0hGlJggPqgb84AVpA9oQm8YmDeQrIlvn_bB6uvUe4Ptnupg/exec?action=run",
                        statusUrl = "https://script.google.com/macros/s/AKfycbw5lfiinKJo_nTqtin3cvH0hGlJggPqgb84AVpA9oQm8YmDeQrIlvn_bB6uvUe4Ptnupg/exec?action=alive",
                        locksTabs = listOf("workflow", "wf_import"),
                    ),
                    SheetAction(
                        id = "workflow_tech_name",
                        label = "TECH NAME",
                        icon = Icons.Outlined.PlayArrow,
                        scriptUrl = "https://script.google.com/macros/s/AKfycbxFgdMUrrTF_I78YwxusdYvew38RU2BsEdIxAS8nlzhzeE0LsrRrrbuSGkxFslacdqI/exec?action=run",
                        requiresPassword = "филактерия",
                        locksTabs = listOf("workflow", "wf_import"),
                    ),
                ),
            ),
            // §TZ-DESKTOP-UX-2026-05 0.8.56 — юзер: «добавить лист 💩 как
            // машинку для Mac и Win после workflow». hidden=false; emoji-only
            // displayName (как у 🚚).
            StaticTab(
                gid = 1549314588L,
                rawName = "💩",
                displayName = "💩",
            ),
            StaticTab(gid = 129894376L, rawName = "wf_custodians", displayName = "МОЛы"),
            StaticTab(
                gid = 1421604338L,
                rawName = "wf_plan",
                displayName = "План",
                actions = listOf(
                    SheetAction(
                        id = "plan_mol_vgh",
                        label = "МОЛы/ВГХ",
                        icon = Icons.Outlined.Sync,
                        scriptUrl = "https://script.google.com/macros/s/AKfycbw5lfiinKJo_nTqtin3cvH0hGlJggPqgb84AVpA9oQm8YmDeQrIlvn_bB6uvUe4Ptnupg/exec?action=run",
                        statusUrl = "https://script.google.com/macros/s/AKfycbw5lfiinKJo_nTqtin3cvH0hGlJggPqgb84AVpA9oQm8YmDeQrIlvn_bB6uvUe4Ptnupg/exec?action=alive",
                        locksTabs = listOf("workflow", "wf_import"),
                    ),
                ),
            ),
            StaticTab(
                gid = 1865519811L,
                rawName = "recipients",
                displayName = "Рассылка",
                actions = listOf(
                    SheetAction(
                        id = "recipients_pull",
                        label = "Подтянуть",
                        icon = Icons.Outlined.CloudDownload,
                        scriptUrl = "https://script.google.com/macros/s/AKfycbxrmLVtKj9cjGhu6BcNujcF-LJCI-dvC3ENrZwSP4dUUCaw3bUzDpz86vT3fBK6gSWNfA/exec?action=run",
                    ),
                ),
            ),
            StaticTab(gid = 1772902932L, rawName = "wf_warehouses", displayName = "Склады"),
            StaticTab(
                gid = 911759389L,
                rawName = "wf_import",
                displayName = "Импорт",
                actions = listOf(
                    SheetAction(
                        id = "import_workflow_refresh",
                        label = "workflow",
                        icon = Icons.Outlined.Sync,
                        scriptUrl = "https://script.google.com/macros/s/AKfycbz4ez4Mu8GhMSlNfwqY04ivVf6NXT57A8uf-7Mi4LPZfKTIzJs9hoOaLiuwKbDX9pJa0w/exec?action=run",
                        statusUrl = "https://script.google.com/macros/s/AKfycbz4ez4Mu8GhMSlNfwqY04ivVf6NXT57A8uf-7Mi4LPZfKTIzJs9hoOaLiuwKbDX9pJa0w/exec?action=alive",
                        locksTabs = listOf("workflow", "wf_import"),
                    ),
                    SheetAction(
                        id = "import_mol_db",
                        label = "БД МОЛов",
                        icon = Icons.Outlined.Sync,
                        scriptUrl = "https://script.google.com/macros/s/AKfycbzTFFOoXkILW87YeVKyseGBJjUewE82NmcXtAuS4xEUhB2k2jOZAsrvKEvfv5SKuSHrIA/exec?action=run",
                    ),
                ),
            ),
            StaticTab(gid = 1702904663L, rawName = "📊schedule", displayName = "График ТМЦ"),
            // §TZ-DESKTOP-UX-2026-04 — LOG в самом конце TabStrip workflow,
            // только просмотр, без actions. Раньше был hidden — юзер попросил показать.
            StaticTab(gid = 822800394L, rawName = "LOG", displayName = "LOG"),
        ),
    )

    /**
     * §TZ-DESKTOP 0.4.x — OTIF5: показываем все листы оригинальными именами.
     *
     * Юзер: «для файла OTIF5 все вкладки также перенести как есть с
     * названиями вверх» — никаких rename, никаких hidden.
     */
    val OTIF5 = SheetsFile(
        id = "1RK8W5tpRwkeh2OLopo21FFvrFVcTfIH6lSMkpVnAStY",
        title = "OTIF5",
        emoji = "📊",
        staticTabs = listOf(
            StaticTab(gid = 1737863350L, rawName = "OTIF5"),
            StaticTab(gid = 135499303L, rawName = "ZM_VL"),
            StaticTab(gid = 422242815L, rawName = "СЭД"),
            StaticTab(gid = 34499025L, rawName = "ОТЧЕТ"),
            StaticTab(gid = 1363034598L, rawName = "DELETED"),
        ),
    )

    val files: List<SheetsFile> = listOf(WORKFLOW, OTIF5)

    /** Иконка-намекалка для будущих авторов скриптов: PlayArrow для запуска,
     *  Functions для свод-операций. */
    @Suppress("unused")
    val DEFAULT_ACTION_ICONS: Map<String, ImageVector> = mapOf(
        "run" to Icons.Outlined.PlayArrow,
        "summary" to Icons.Outlined.Functions,
    )
}
