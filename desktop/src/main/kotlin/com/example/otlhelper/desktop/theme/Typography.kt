package com.example.otlhelper.desktop.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

/**
 * §TZ-DESKTOP 0.2.2 — SF-tier Inter font, 3 веса: Regular / Medium / SemiBold.
 *
 * TTFs лежат в desktop/src/main/resources/fonts/ (rsms Inter v4.0, ~1.2 MB суммарно).
 * Font() API загружает из classpath. Этот же шрифт используют Linear, Figma,
 * Vercel — де-факто стандарт для desktop/web UI в топ-студиях 2020+.
 *
 * На mac + Windows визуально одинаково → больше нет «на windows выглядит хуже».
 */
private val Inter = FontFamily(
    Font(resource = "fonts/Inter-Regular.ttf", weight = FontWeight.Normal),
    Font(resource = "fonts/Inter-Medium.ttf", weight = FontWeight.Medium),
    Font(resource = "fonts/Inter-SemiBold.ttf", weight = FontWeight.SemiBold),
)

/**
 * §TZ-DESKTOP 0.2.0 — SF-style шкала типографики, 8 уровней.
 *
 * Принципы:
 *  • только 3 веса: Normal (400), Medium (500), SemiBold (600) — Bold избегаем
 *    на dark-теме, он ощущается тяжёлым
 *  • letterSpacing отрицательный на крупных размерах (−0.1..−0.2) и
 *    положительный на uppercase-labels (+0.3..+0.5)
 *  • lineHeight строго пропорционален размеру (1.3..1.4)
 */
private val Family = Inter

val OtldTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Family, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
        lineHeight = 34.sp, letterSpacing = (-0.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Family, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
        lineHeight = 28.sp, letterSpacing = (-0.1).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Family, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        lineHeight = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Family, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        lineHeight = 20.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Family, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Family, fontSize = 14.sp, fontWeight = FontWeight.Normal,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Family, fontSize = 13.sp, fontWeight = FontWeight.Normal,
        lineHeight = 18.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Family, fontSize = 12.sp, fontWeight = FontWeight.Normal,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Family, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        lineHeight = 16.sp, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Family, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        lineHeight = 16.sp, letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Family, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        lineHeight = 14.sp, letterSpacing = 0.3.sp,
    ),
)

/**
 * §TZ-DESKTOP 0.2.2 — экспорт FontFamily для мест где прямо задаётся стиль
 * (не через MaterialTheme.typography). Например inline Text() с custom fontSize.
 * Импортируется как `OtldFontFamily` и подставляется в Text(fontFamily = ...).
 */
val OtldFontFamily: FontFamily = Inter
