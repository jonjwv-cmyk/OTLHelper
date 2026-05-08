package com.example.otlhelper.presentation.home.internal

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.delay

/**
 * §TZ-CLEANUP-2026-04-26 — extracted from HomeScreen.kt.
 *
 * Прокрутить список так, чтобы последний item был *полностью* виден внизу.
 *
 * Берём индекс из `layoutInfo.totalItemsCount`, а не из размера данных:
 *  - В новостях это совпадает.
 *  - В чате LazyColumn содержит дополнительные элементы — sticky-headers
 *    дат и Spacer'ы между группами разных отправителей. Если использовать
 *    индекс из data-list, прокрутка попадает «куда-то в середину».
 *
 * После первого `animateScrollToItem` запускаем серию корректирующих
 * пассов с короткой задержкой — потому что AsyncImage внутри высокого
 * пузыря дозамеряется позже первого frame'а; одного прохода не хватает,
 * чтобы поймать финальную высоту. Стоп когда нижняя кромка item'а
 * совпала/ушла выше нижней кромки viewport'а или исчерпан лимит.
 */
internal suspend fun LazyListState.scrollToBottom() {
    val total = layoutInfo.totalItemsCount
    if (total <= 0) return
    val lastIndex = total - 1
    animateScrollToItem(lastIndex)
    repeat(5) { pass ->
        delay(if (pass == 0) 50 else 90)
        // Re-resolve last index each pass — a new sticky-header could have
        // pushed it on the most recent layout pass.
        val nowLast = layoutInfo.totalItemsCount - 1
        if (nowLast < 0) return
        val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == nowLast }
            ?: run {
                // The last item isn't even visible — do another scroll-to and retry.
                animateScrollToItem(nowLast)
                return@repeat
            }
        val overhang = info.offset + info.size - layoutInfo.viewportEndOffset
        if (overhang <= 0) return
        animateScrollBy(overhang.toFloat())
    }
}
