package com.example.otlhelper.presentation.home.internal

import com.example.otlhelper.core.telemetry.Telemetry
import com.example.otlhelper.data.repository.MolRepository
import com.example.otlhelper.data.sync.BaseSyncManager
import com.example.otlhelper.data.sync.BaseSyncStatus
import com.example.otlhelper.domain.model.MolRecord
import com.example.otlhelper.domain.model.SearchMode
import com.example.otlhelper.domain.model.hasRealWarehouse
import com.example.otlhelper.presentation.home.HomeUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Search + МОЛ base slice of HomeViewModel.
 *
 * Owns:
 *  - search query → repository routing (phone/email/warehouse/name)
 *  - pinned-warehouse card (first hit when search detected as warehouse)
 *  - local base metadata (version + updated_at) displayed in Settings
 *  - BaseSync orchestration: enqueue, force, observe progress → splash
 *
 * Mutates the shared [uiState] under fields: searchQuery, searchResults,
 * searchLoading, searchMode, pinnedWarehouse, pinnedWarehouseExpanded,
 * baseVersion, baseUpdatedAt.
 *
 * Splash updates are delegated via [onSplashStatusChanged] — the splash
 * itself belongs to AppController, but base-download progress is the most
 * user-facing signal during cold start so it needs to surface there.
 */
internal class SearchController(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<HomeUiState>,
    private val molRepository: MolRepository,
    private val baseSyncManager: BaseSyncManager,
    private val telemetry: Telemetry,
    private val onSplashStatusChanged: (String) -> Unit,
) {

    private var searchJob: Job? = null

    // ── Search ───────────────────────────────────────────────────────────────
    fun onSearchQueryChanged(query: String) {
        // On ANY query change clear old results + pinned warehouse immediately.
        // Otherwise a stale card + sticky pill leak into the next query window.
        //
        // §TZ-2.3.9 — detectSearchMode вызываем сразу синхронно на трим'нутом
        // query. Раньше writeMode = NONE на каждый keystroke, потом через
        // 150мс debounce — правильный mode. Между этими двумя кадрами UI мог
        // успеть показать «Ничего не найдено» (generic NONE-шаблон) вместо
        // mode-specific «Склад/Телефон/Почта/Сотрудник не найден». Теперь
        // шаблон empty-state правильный с первой буквы.
        val trimmed = query.trim()
        val nextMode =
            if (trimmed.isBlank()) SearchMode.NONE
            else detectSearchMode(trimmed)
        uiState.update {
            it.copy(
                searchQuery = query,
                searchResults = emptyList(),
                pinnedWarehouse = null,
                searchMode = nextMode,
            )
        }
        searchJob?.cancel()
        if (query.isBlank()) return
        searchJob = scope.launch {
            delay(150)
            performSearch(query)
        }
    }

    fun togglePinnedWarehouseExpanded() {
        uiState.update { it.copy(pinnedWarehouseExpanded = !it.pinnedWarehouseExpanded) }
    }

    private suspend fun performSearch(query: String) {
        uiState.update { it.copy(searchLoading = true) }
        val q = query.trim()
        val detectedMode = detectSearchMode(q)
        val results: List<MolRecord> = withContext(Dispatchers.IO) {
            when (detectedMode) {
                SearchMode.EMAIL -> molRepository.searchByMail(q)
                SearchMode.PHONE -> molRepository.searchByPhone(q)
                SearchMode.WAREHOUSE -> {
                    // §TZ-2.3.7 — warehouse поиск только при complete pattern
                    // (4 цифры / 3 цифры + буква). Неполные prefix'ы (060, 07К)
                    // дают пустой список → «Склад не найден». Homoglyph
                    // fallback: если latin-вариант не нашёл — пробуем кириллический.
                    if (!isCompleteWarehouseQuery(q)) emptyList()
                    else {
                        val first = molRepository.searchByWarehouse(q)
                        if (first.isNotEmpty()) first
                        else {
                            val homoglyph = homoglyphCyrillic(q)
                            if (homoglyph != q) molRepository.searchByWarehouse(homoglyph)
                            else emptyList()
                        }
                    }
                }
                SearchMode.NAME -> molRepository.search(q)
                SearchMode.NONE -> emptyList()
            }
        }
        // Telemetry: count searches per user + detected mode + query length.
        // Privacy — the query itself is NOT logged (§3.14). Drives the
        // AppStatsDialog "Top actions" ranking.
        if (q.isNotBlank()) {
            telemetry.event("search_query", mapOf(
                "len" to q.length,
                "mode" to detectedMode.name.lowercase(),
                "matched" to results.size,
            ))
        }
        // Working contacts first (alphabetical by fio), non-working last.
        val sorted = results.sortedWith(Comparator { a, b ->
            val aWorking = a.status.trim().equals("работает", ignoreCase = true)
            val bWorking = b.status.trim().equals("работает", ignoreCase = true)
            when {
                aWorking && !bWorking -> -1
                !aWorking && bWorking -> 1
                else -> a.fio.compareTo(b.fio, ignoreCase = true)
            }
        })
        // Skip legacy "МОЛ" marker rows — they aren't real warehouses.
        val pinned = sorted.firstOrNull()?.takeIf { it.hasRealWarehouse() }

        uiState.update {
            it.copy(
                searchResults = sorted,
                searchLoading = false,
                pinnedWarehouse = pinned,
                searchMode = detectedMode,
            )
        }
    }

    // ── Base metadata (for Settings) ─────────────────────────────────────────
    suspend fun refreshBaseMetadata() {
        try {
            val v = molRepository.getLocalVersion()
            val u = molRepository.getLocalUpdatedAt()
            uiState.update { it.copy(baseVersion = v, baseUpdatedAt = u) }
        } catch (e: Exception) {
            // Local Room read — a failure means the DB is corrupted or SQLCipher
            // refused to open. Network doesn't flow into here, so silent swallow
            // would hide a real bug. Log.
            android.util.Log.w("SearchCtrl", "refreshBaseMetadata: local Room read failed", e)
        }
    }

    // ── Base sync orchestration ──────────────────────────────────────────────
    //
    // SF-2026 §RELIABLE-BASE (2026-04): previously a single `base_download`
    // request for the whole МОЛ dictionary (1400+ rows, ~700KB). Some users
    // on VPN/Kaspersky/unstable providers kept losing it → Settings showed
    // "not loaded", offline search broke.
    //
    // Now: WorkManager (BaseSyncManager → BaseSyncWorker).
    //  — chunked pagination 500 (adaptive down to 50 on failures)
    //  — 3× per-chunk retries with backoff
    //  — resume (SharedPrefs holds offset/chunkSize between runs)
    //  — foreground service (survives app close)
    //  — atomic replace (user never sees half a dictionary)
    fun syncBaseIfNeeded() {
        // Idempotent — enqueueing an already-running/planned task is a no-op.
        // Fast version check lives inside the Worker itself.
        baseSyncManager.enqueue()
    }

    fun triggerBaseSyncManual() {
        baseSyncManager.force()
    }

    /**
     * Watch the background МОЛ sync. Updates the splash status during a live
     * download, refreshes Settings metadata on success, leaves local cache
     * intact on failure.
     */
    fun observeBaseSyncStatus() {
        scope.launch {
            baseSyncManager.status().collect { status ->
                when (status) {
                    is BaseSyncStatus.Idle,
                    is BaseSyncStatus.Queued -> {
                        // Nothing to announce — splash already hidden or covered by another message.
                    }
                    is BaseSyncStatus.Running -> {
                        // If total is still 0, the worker is only probing the
                        // server version — don't disturb the user. Surface the
                        // splash hint only once real downloading is in flight.
                        if (status.total > 0 && uiState.value.splashVisible) {
                            onSplashStatusChanged("Справочник: ${status.loaded}/${status.total}")
                        }
                    }
                    is BaseSyncStatus.Success -> {
                        refreshBaseMetadata()
                    }
                    is BaseSyncStatus.Failed -> {
                        // Don't break the UI — let the user work with whatever
                        // cache they have. Next tick of syncBaseIfNeeded retries.
                    }
                }
            }
        }
    }

    fun cancel() {
        searchJob?.cancel()
    }

    companion object {
        // Extracted so SearchControllerTest can exercise mode detection without
        // spinning up the whole controller + coroutine machinery. The regex
        // patterns below encode the "how do we recognise a warehouse ID vs a
        // phone vs an email vs a name" contract — changing them directly moves
        // user-facing search behaviour.
        // Complete warehouse patterns — по ним ВЫПОЛНЯЕТСЯ поиск.
        // Неполные (2-3 digits без letter) → mode=WAREHOUSE, но поиск
        // возвращает пустой список → empty-state «Склад не найден».
        private val WAREHOUSE_COMPLETE = Regex("^\\d{4}$|^\\d{3}[A-Za-zА-Яа-яЁё]$")
        // Early classifier — 1-4 digit prefix или короткая digits+letter, даже
        // если еще не complete. Даёт правильный empty-state text.
        private val WAREHOUSE_EARLY = Regex("^\\d{1,4}$|^\\d{1,3}[A-Za-zА-Яа-яЁё]$")
        private val EMAIL_PATTERN = Regex("[^\\s@]+@[^\\s@]+")
        private val LATIN_LETTERS = Regex("[A-Za-z]")
        private val CYRILLIC_LETTERS = Regex("[А-Яа-яЁё]")

        /**
         * Классификация поискового запроса для роутинга в repository +
         * empty-state text.
         *
         * §TZ-2.3.7:
         *  • Склад: начиная с первой цифры query помечается WAREHOUSE. Поиск
         *    выполняется только при COMPLETE pattern (4 digit / 3 digit +
         *    буква), иначе возвращает [] → empty-state «Склад не найден».
         *  • Телефон: ≥5 цифр подряд.
         *  • E-mail: латиница или `@`.
         *  • ФИО: кириллица.
         */
        fun detectSearchMode(trimmedQuery: String): SearchMode {
            if (trimmedQuery.isBlank()) return SearchMode.NAME

            if (EMAIL_PATTERN.containsMatchIn(trimmedQuery)) return SearchMode.EMAIL

            val digitRun = trimmedQuery.filter { it.isDigit() }
            if (digitRun.length >= 5) return SearchMode.PHONE

            if (WAREHOUSE_EARLY.matches(trimmedQuery)) return SearchMode.WAREHOUSE

            if (LATIN_LETTERS.containsMatchIn(trimmedQuery) &&
                !CYRILLIC_LETTERS.containsMatchIn(trimmedQuery)
            ) return SearchMode.EMAIL

            return SearchMode.NAME
        }

        /** true только когда query — COMPLETE warehouse pattern. */
        fun isCompleteWarehouseQuery(q: String): Boolean =
            WAREHOUSE_COMPLETE.matches(q)

        /**
         * §TZ-2.3.7 — Homoglyph normalization: кирил ↔ латин визуально
         * идентичные. Используется для warehouse-поиска: «0609Т» (кирилл)
         * и «0609T» (латин) должны найти одно и то же. Возвращает `Pair`
         * ровно-как-есть + нормализованный вариант (кирилл-вариант) для
         * второго SQL LIKE.
         */
        fun homoglyphCyrillic(q: String): String {
            val map = mapOf(
                'A' to 'А', 'B' to 'В', 'C' to 'С', 'E' to 'Е', 'H' to 'Н',
                'K' to 'К', 'M' to 'М', 'O' to 'О', 'P' to 'Р', 'T' to 'Т',
                'X' to 'Х', 'Y' to 'У',
                'a' to 'а', 'c' to 'с', 'e' to 'е', 'o' to 'о', 'p' to 'р',
                'x' to 'х', 'y' to 'у',
            )
            return q.map { map[it] ?: it }.joinToString("")
        }
    }
}
