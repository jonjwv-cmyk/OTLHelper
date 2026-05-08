package com.example.otlhelper.core.ui

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/**
 * Тонкий @Immutable-обёртка над raw JSONObject для стабильных Composable
 * параметров.
 *
 * ## Проблема
 * `NewsCard(item: JSONObject)` — JSONObject кажется Compose'у unstable.
 * Каждый poll создаёт НОВЫЕ JSONObject'ы (даже если контент тот же) →
 * NewsCard не может пропустить рекомпозицию → вся LazyColumn перерисуется.
 * На 50+ элементах ленты это жрёт CPU и вызывает jank.
 *
 * ## Решение
 * [StableJsonItem] компьютит hashCode по содержимому ОДИН РАЗ при
 * создании и использует его в equals. Если сервер вернул тот же контент
 * в новом JSONObject — новый обёрнутый объект будет `equals` старому,
 * Compose пропустит recompose.
 *
 * Стоимость: `toString().hashCode()` при создании обёртки — O(N) от
 * размера json, ~1μs на ~1000 символов. Оборачиваем в `remember(items)`
 * в call-site'е → стоимость амортизируется в ноль для стационарного
 * состояния.
 *
 * ## Приватность
 * Ничего не логгируется; hash это обычный `String.hashCode()`, не
 * шифрование.
 */
@Immutable
class StableJsonItem(val raw: JSONObject) {
    private val contentHash: Int = raw.toString().hashCode()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is StableJsonItem && other.contentHash == contentHash
    }

    override fun hashCode(): Int = contentHash
}

/** Быстрый мап-помощник для списков JSONObject → списки обёрток. */
fun List<JSONObject>.asStableItems(): List<StableJsonItem> = map { StableJsonItem(it) }

/**
 * Детерминистичный LazyColumn ключ для feed-элемента (новость / сообщение /
 * опрос). Используется во всех lists ленты и чата.
 *
 * Приоритет источников ключа:
 *  1. `local_item_id` — клиентский UUID, ставится при optimistic-send до
 *     подтверждения сервером. Гарантирует что ровно тот же Composable
 *     переживёт момент «pending → committed».
 *  2. `id` — серверный long PK после коммита.
 *  3. `sender_login + created_at` — fallback для редких записей где ни
 *     local_id ни id не пришли (например админские history-выборки).
 *
 * НЕ использует `hashCode()` (было раньше fallback'ом) — `JSONObject.hashCode()`
 * не стабилен между JVM-запусками и меняется при любом reshuffle ключей
 * в объекте. Это приводило к тому что одни и те же сообщения получали
 * разные ключи после reload → Compose трактовал их как новые → срабатывал
 * `animateItem()` на всём списке → видимый прыжок.
 */
fun stableFeedKey(item: JSONObject): String {
    val localId = item.optString("local_item_id")
    if (localId.isNotBlank()) return "l_$localId"
    val id = item.optLong("id", 0L)
    if (id > 0L) return "i_$id"
    val sender = item.optString("sender_login")
    val created = item.optString("created_at")
    return "s_${sender}_${created}"
}
