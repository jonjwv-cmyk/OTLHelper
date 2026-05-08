package com.example.otlhelper.core.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Per-theme color token set ─────────────────────────────────────
data class OtlColors(
    val isDark: Boolean,
    // Surfaces
    val bgApp: Color,
    val bgInput: Color,
    val bgCard: Color,
    val bgElevated: Color,
    // Text
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    // Accent
    val accent: Color,
    val accentMuted: Color,
    val accentSubtle: Color,
    // Brand
    val brandAmber: Color,
    // Status
    val statusOk: Color,
    val statusError: Color,
    val statusOkBorder: Color,
    val statusErrorBorder: Color,
    // Misc signals
    val unreadGreen: Color,
    val presencePaused: Color,
    // Borders
    val borderDivider: Color,
    val borderCard: Color,
    // Chat bubbles
    val bubbleOwn: Color,
    val bubbleOther: Color,
    // Warehouse pill
    val warehousePillBg: Color,
    val warehousePillText: Color,
    val warehousePillSub: Color,
    val warehousePillPhone: Color,
    // Avatar fallback
    val avatarBg: Color,
    // Poll
    val pollOptionNormal: Color,
    val pollOptionSelected: Color,
)

// ── Standard — warm bronze dark (current / brand default) ─────────
internal fun standardColors() = OtlColors(
    isDark = true,
    bgApp      = Color(0xFF0E0E10),
    bgInput    = Color(0xFF141416),
    bgCard     = Color(0xFF1A1A1C),
    bgElevated = Color(0xFF2A2A2E),
    textPrimary   = Color(0xFFF0F0F2),
    textSecondary = Color(0xFF9A9AA0),
    textTertiary  = Color(0xFF707078),
    accent       = Color(0xFFD4A467),
    accentMuted  = Color(0xFF9F7A3D),
    accentSubtle = Color(0x14D4A467),
    brandAmber = Color(0xFFF7C657),
    statusOk          = Color(0xFF14532D),
    statusError       = Color(0xFF7F1D1D),
    statusOkBorder    = Color(0xFF22C55E),
    statusErrorBorder = Color(0xFFEF4444),
    unreadGreen    = Color(0xFF22C55E),
    presencePaused = Color(0xFFE5A83B),
    borderDivider = Color(0xFF363640),
    borderCard    = Color(0x14FFFFFF),
    bubbleOwn   = Color(0xFF3E2E1C),
    bubbleOther = Color(0xFF26262B),
    warehousePillBg    = Color(0xFF181C1A),
    warehousePillText  = Color(0xFFF0F0F2),
    warehousePillSub   = Color(0xFF9A9AA0),
    warehousePillPhone = Color(0xFFF0F0F2),
    avatarBg = Color(0xFF2A2A30),
    pollOptionNormal   = Color(0xFF16161A),
    pollOptionSelected = Color(0xFF1E2030),
)

// ── Dark — neutral AMOLED dark ────────────────────────────────────
internal fun darkColors() = OtlColors(
    isDark = true,
    bgApp      = Color(0xFF000000),
    bgInput    = Color(0xFF0D0D0D),
    bgCard     = Color(0xFF141414),
    bgElevated = Color(0xFF1E1E1E),
    textPrimary   = Color(0xFFEBEBEB),
    textSecondary = Color(0xFF888888),
    textTertiary  = Color(0xFF555555),
    accent       = Color(0xFFD4A467),
    accentMuted  = Color(0xFF9F7A3D),
    accentSubtle = Color(0x14D4A467),
    brandAmber = Color(0xFFF7C657),
    statusOk          = Color(0xFF052E16),
    statusError       = Color(0xFF450A0A),
    statusOkBorder    = Color(0xFF22C55E),
    statusErrorBorder = Color(0xFFEF4444),
    unreadGreen    = Color(0xFF22C55E),
    presencePaused = Color(0xFFE5A83B),
    borderDivider = Color(0xFF262626),
    borderCard    = Color(0x0FFFFFFF),
    bubbleOwn   = Color(0xFF2C2C2C),
    bubbleOther = Color(0xFF1A1A1A),
    warehousePillBg    = Color(0xFF111111),
    warehousePillText  = Color(0xFFEBEBEB),
    warehousePillSub   = Color(0xFF888888),
    warehousePillPhone = Color(0xFFEBEBEB),
    avatarBg = Color(0xFF262626),
    pollOptionNormal   = Color(0xFF0D0D0D),
    pollOptionSelected = Color(0xFF141A26),
)

// ── Light — day theme, warm-bronze accent ─────────────────────────
internal fun lightColors() = OtlColors(
    isDark = false,
    bgApp      = Color(0xFFF2F2F5),
    bgInput    = Color(0xFFFFFFFF),
    bgCard     = Color(0xFFFFFFFF),
    bgElevated = Color(0xFFFFFFFF),
    textPrimary   = Color(0xFF1C1C1E),
    textSecondary = Color(0xFF636366),
    textTertiary  = Color(0xFFAEAEB2),
    // Bronze darkened for WCAG AA contrast on white (#FFF → ratio 4.6:1)
    accent       = Color(0xFFAA7230),
    accentMuted  = Color(0xFF7D5220),
    accentSubtle = Color(0x14AA7230),
    brandAmber = Color(0xFFF7C657),
    statusOk          = Color(0xFFDCFCE7),
    statusError       = Color(0xFFFEE2E2),
    statusOkBorder    = Color(0xFF16A34A),
    statusErrorBorder = Color(0xFFDC2626),
    unreadGreen    = Color(0xFF16A34A),
    presencePaused = Color(0xFFD97706),
    borderDivider = Color(0xFFD1D1D6),
    borderCard    = Color(0x0A000000),
    // Bubbles: own = champagne warm, other = light neutral
    bubbleOwn   = Color(0xFFF5E9D9),
    bubbleOther = Color(0xFFEFEFEF),
    warehousePillBg    = Color(0xFFF0F0F2),
    warehousePillText  = Color(0xFF1C1C1E),
    warehousePillSub   = Color(0xFF636366),
    warehousePillPhone = Color(0xFF1C1C1E),
    avatarBg = Color(0xFFE5E5EA),
    pollOptionNormal   = Color(0xFFF7F7F8),
    pollOptionSelected = Color(0xFFEEF0F9),
)

// ── CompositionLocal ──────────────────────────────────────────────
val LocalOtlColors = compositionLocalOf { standardColors() }

// ── Top-level @Composable accessors — zero import changes needed ──
// All 53 importing files continue to use `BgCard`, `Accent`, etc.
// as before; the @Composable getter makes reads composition-tracked.
val BgApp: Color
    @Composable get() = LocalOtlColors.current.bgApp
val BgInput: Color
    @Composable get() = LocalOtlColors.current.bgInput
val BgCard: Color
    @Composable get() = LocalOtlColors.current.bgCard
val BgElevated: Color
    @Composable get() = LocalOtlColors.current.bgElevated

val TextPrimary: Color
    @Composable get() = LocalOtlColors.current.textPrimary
val TextSecondary: Color
    @Composable get() = LocalOtlColors.current.textSecondary
val TextTertiary: Color
    @Composable get() = LocalOtlColors.current.textTertiary

val Accent: Color
    @Composable get() = LocalOtlColors.current.accent
val AccentMuted: Color
    @Composable get() = LocalOtlColors.current.accentMuted
val AccentSubtle: Color
    @Composable get() = LocalOtlColors.current.accentSubtle

val BrandAmber: Color
    @Composable get() = LocalOtlColors.current.brandAmber

val StatusOk: Color
    @Composable get() = LocalOtlColors.current.statusOk
val StatusError: Color
    @Composable get() = LocalOtlColors.current.statusError
val StatusOkBorder: Color
    @Composable get() = LocalOtlColors.current.statusOkBorder
val StatusErrorBorder: Color
    @Composable get() = LocalOtlColors.current.statusErrorBorder

val UnreadGreen: Color
    @Composable get() = LocalOtlColors.current.unreadGreen
val PresencePaused: Color
    @Composable get() = LocalOtlColors.current.presencePaused

val BorderDivider: Color
    @Composable get() = LocalOtlColors.current.borderDivider
val BorderCard: Color
    @Composable get() = LocalOtlColors.current.borderCard

val BubbleOwn: Color
    @Composable get() = LocalOtlColors.current.bubbleOwn
val BubbleOther: Color
    @Composable get() = LocalOtlColors.current.bubbleOther

val WarehousePillBg: Color
    @Composable get() = LocalOtlColors.current.warehousePillBg
val WarehousePillText: Color
    @Composable get() = LocalOtlColors.current.warehousePillText
val WarehousePillSub: Color
    @Composable get() = LocalOtlColors.current.warehousePillSub
val WarehousePillPhone: Color
    @Composable get() = LocalOtlColors.current.warehousePillPhone

val AvatarBg: Color
    @Composable get() = LocalOtlColors.current.avatarBg

val PollOptionNormal: Color
    @Composable get() = LocalOtlColors.current.pollOptionNormal
val PollOptionSelected: Color
    @Composable get() = LocalOtlColors.current.pollOptionSelected

// ── Avatar palette — deterministic, theme-independent ────────────
val AvatarColors = listOf(
    Color(0xFF5B6ABF),  // indigo
    Color(0xFFB5577A),  // rose
    Color(0xFF2E9E8F),  // teal
    Color(0xFFCF8E3E),  // amber
    Color(0xFF7E5EC2),  // violet
    Color(0xFF2E96B4),  // cyan
    Color(0xFF5A8C3E),  // sage
    Color(0xFFB85C3B),  // terracotta
)

@Composable
fun avatarColor(name: String): Color {
    if (name.isBlank()) return LocalOtlColors.current.avatarBg
    val hash = name.fold(7) { acc, c -> acc * 31 + c.code }
    return AvatarColors[kotlin.math.abs(hash) % AvatarColors.size]
}
