package com.example.otlhelper.desktop.theme

import androidx.compose.ui.graphics.Color

// §TZ-DESKTOP-0.1.0 — зеркалят app/core/theme/Color.kt. Пока копия ради
// безопасности (не трогаем :app), на этапе «shared» модуля — вынесем.

val BgApp      = Color(0xFF0E0E10)
val BgInput    = Color(0xFF141416)
val BgCard     = Color(0xFF1A1A1C)
val BgCardHover = Color(0xFF212125)  // §TZ-DESKTOP 0.2.2 — hover-fill карточек/строк
val BgElevated = Color(0xFF2A2A2E)

val TextPrimary   = Color(0xFFF0F0F2)
// §TZ-DESKTOP-0.10.2 — ещё прибавили luma для ночного режима экранов:
// (0.10.1 — C0C0C8 / 9494A0 — недостаточно читаемо).
val TextSecondary = Color(0xFFD8D8DD)
val TextTertiary  = Color(0xFFAAAAB4)

val Accent       = Color(0xFFD4A467)
val AccentMuted  = Color(0xFF9F7A3D)
val AccentSubtle = Color(0x14D4A467)

val BrandAmber = Color(0xFFF7C657)

val StatusOkBorder    = Color(0xFF22C55E)
val StatusErrorBorder = Color(0xFFEF4444)

val UnreadGreen = Color(0xFF22C55E)

val BorderDivider = Color(0xFF363640)

val BubbleOwn   = Color(0xFF3E2E1C)
val BubbleOther = Color(0xFF26262B)

val PresencePaused = Color(0xFFE5A83B)

val PollOptionNormal   = Color(0xFF16161A)
val PollOptionSelected = Color(0xFF1E2030)
