package com.example.otlhelper.desktop.theme

import androidx.compose.ui.unit.dp

/**
 * §TZ-DESKTOP 0.2.0 — 4-point spacing scale. Используется вместо dp-литералов
 * во всём desktop UI чтобы держать отступы на сетке.
 *
 *   xs  =  4dp   — внутри chip'а / icon gap
 *   sm  =  8dp   — между связанными элементами (avatar↔name)
 *   md  = 12dp   — внутренний padding карточек / списков
 *   lg  = 16dp   — между секциями внутри экрана
 *   xl  = 24dp   — между крупными блоками
 *   xxl = 32dp   — между разделами / вокруг hero-элементов
 */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}
