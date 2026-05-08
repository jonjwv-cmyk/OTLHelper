package com.example.otlhelper.domain.model

/**
 * Display-ready модель одной карточки ленты (новость или опрос) — §4.1 паспорта v3.
 *
 * Принцип: UI-композабл получает готовые флаги/строки и НЕ принимает решений о ролях.
 * Всё вычисляется в [com.example.otlhelper.domain.policy.FeedViewPolicy.toView].
 *
 * Фаза 2: сейчас эту модель строит клиент поверх сырого JSON (FeedViewPolicy).
 * Фаза 7: сервер начнёт отдавать эти поля напрямую, FeedViewPolicy превратится в passthrough.
 *
 * Фаза 13 (KMP): эта модель должна быть pure Kotlin — никаких android/androidx/json.
 */
data class FeedItemView(
    /** Серверный id (0 если локальная / pending). */
    val id: Long,
    /** Локальный id для pending-элементов (для диффов при оптимистичной вставке). */
    val localItemId: String,
    /** Тип отображения — новость или опрос. */
    val displayType: FeedDisplayType,
    /** Готовый текст заголовка карточки: «НОВОСТЬ», «ОПРОС», «НОВОСТЬ (важно)», «ОПРОС (важно)». */
    val headerLabel: String,
    /** Имя автора (ФИО / login). */
    val authorLabel: String,
    /** ISO-UTC timestamp создания — форматируется UI через formatDate(). */
    val createdAt: String,
    /** Карточка закреплена. */
    val isPinned: Boolean,
    /** Карточка помечена как «важно». В MVP совпадает с [isPinned] (§3.4). */
    val isImportant: Boolean,
    /** Показывать количество/проценты голосов в опросе (admin/developer — yes). */
    val canSeeVoteCounts: Boolean,
    /** Показывать кнопку ⋮ на карточке (admin/developer). */
    val canOpenMenu: Boolean,
    /** Возможность закрепить/открепить (admin/developer). */
    val canPin: Boolean,
    /** Возможность открыть полную статистику (кто прочитал / проголосовал). */
    val canSeeFullStats: Boolean,
    /** Показывать автора+дату создания (всем, §3.4). */
    val showAuthor: Boolean,
    /** Элемент был отредактирован хотя бы раз (§3.15.a.А). */
    val isEdited: Boolean = false,
    /** Aggregate реакций `{emoji → count}` — Phase 12b. Пустой map = реакций нет. */
    val reactionsAggregate: Map<String, Int> = emptyMap(),
    /** Мои эмодзи (которыми я уже отреагировал). */
    val myReactions: Set<String> = emptySet(),
)

/** Тип карточки для ветвления в UI. */
enum class FeedDisplayType {
    NEWS,
    POLL,
    /** На случай если сервер пришлёт незнакомый display_type — рендерим как NEWS c telemetry (§3.15.a.З). */
    UNKNOWN;
}
