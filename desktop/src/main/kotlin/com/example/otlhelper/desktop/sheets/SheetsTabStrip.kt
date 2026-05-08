package com.example.otlhelper.desktop.sheets

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.AccentSubtle
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.TextSecondary

/**
 * §TZ-DESKTOP 0.4.x — горизонтальная полоска вкладок над Chromium.
 *
 * Стиль pill-tabs (Linear / Notion 2026):
 *   • Активная: AccentSubtle bg + Accent text + Medium weight
 *   • Неактивная: transparent + TextSecondary + Normal weight
 *   • Hover на неактивной: BgElevated bg
 *
 * Click → onTabClick(gid) → caller инжектит JS `window.location.hash =
 * '#gid=N'` (НЕ loadURL — иначе теряется live-state Google).
 *
 * Overflow: горизонтальный scroll через LazyRow. Active tab автоматически
 * скроллится в видимую область при изменении `activeGid`. Если в будущем
 * понадобится «···»-dropdown — добавить над LazyRow trailing item с
 * остатком невидимых вкладок.
 */
@Composable
fun SheetsTabStrip(
    tabs: List<SheetTab>,
    activeOriginalName: String?,
    onTabClick: (SheetTab) -> Unit,
    modifier: Modifier = Modifier,
    // §TZ-DESKTOP-UX-2026-04 — третья right-aligned кнопка «Войти в Google»
    // (Anonymous mode) либо «Выход из Google» (SignedIn mode). Single слот,
    // меняется в зависимости от текущего state'а. Click → handler.
    //
    // §TZ-DESKTOP-UX-2026-05 0.8.59 — добавлен showCancelLoginButton для
    // Authenticating state. Юзер: «должна быть кнопка Отмена. нажали ее
    // и возвращаемся в таблицу». Заменяет «Войти в Google» pill пока юзер
    // на login form. Click → loginChoice=Anonymous → возврат на sheet.
    showGoogleLoginButton: Boolean = false,
    onGoogleLoginClick: () -> Unit = {},
    showGoogleLogoutButton: Boolean = false,
    onGoogleLogoutClick: () -> Unit = {},
    showCancelLoginButton: Boolean = false,
    onCancelLoginClick: () -> Unit = {},
) {
    val listState = rememberLazyListState()

    LaunchedEffect(activeOriginalName, tabs) {
        val idx = tabs.indexOfFirst { it.originalName == activeOriginalName }
        if (idx >= 0) {
            // Аккуратно: animateScrollToItem может бросить если элемент только
            // что появился; runCatching защищает на случай race.
            runCatching { listState.animateScrollToItem(idx) }
        }
    }

    // Без фиксированной height — пилюли определяют размер через
    // padding+content. 12sp шрифт + vertical=6dp + LazyRow vertical pad=5dp
    // → total ~34dp, русские буквы с descender («р» в «Импорт») не
    // обрезаются.
    Box(modifier = modifier.fillMaxWidth().background(BgCard)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f),
            ) {
                // Key = originalName, не gid: до live-resolve все placeholder-табы
                // имеют gid=-1 → key collision. originalName уникален.
                items(tabs, key = { it.originalName }) { tab ->
                    TabPill(
                        tab = tab,
                        isActive = tab.originalName == activeOriginalName,
                        onClick = { onTabClick(tab) },
                    )
                }
            }
            if (showCancelLoginButton) {
                // §TZ-DESKTOP-UX-2026-05 0.8.59 — Authenticating state:
                // занимает то же место где была «Войти в Google» pill.
                GoogleAuthPill(
                    icon = Icons.Outlined.Close,
                    label = "Отмена",
                    onClick = onCancelLoginClick,
                )
                Spacer(Modifier.width(8.dp))
            } else if (showGoogleLoginButton) {
                GoogleAuthPill(
                    icon = Icons.Outlined.Login,
                    label = "Войти в Google",
                    onClick = onGoogleLoginClick,
                )
                Spacer(Modifier.width(8.dp))
            } else if (showGoogleLogoutButton) {
                GoogleAuthPill(
                    icon = Icons.Outlined.Logout,
                    label = "Выход из Google",
                    onClick = onGoogleLogoutClick,
                )
                Spacer(Modifier.width(8.dp))
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .height(0.5.dp)
                .background(BorderDivider),
        )
    }
}

/**
 * §TZ-DESKTOP-UX-2026-04 — pill-кнопка справа в TabStrip: «Войти в Google»
 * (Anonymous mode) или «Выход из Google» (SignedIn mode). Один компонент,
 * параметризованный иконкой и подписью.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun GoogleAuthPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        targetValue = if (hovered) AccentSubtle else Color.Transparent,
        label = "authPillBg",
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(0.5.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)))
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(onClick = onClick)
            .height(30.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                color = Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TabPill(
    tab: SheetTab,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    // §TZ-DESKTOP-0.10.4 — Активный таб: «сияние в стиле приложения» —
    // pulsing Accent halo через drawBehind + AccentSubtle background.
    // Неактивный hover: cursorGlow modifier (как в MenuRow).
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val bg by animateColorAsState(
        targetValue = when {
            isActive -> AccentSubtle
            hovered -> BgElevated
            else -> Color.Transparent
        },
        label = "tabBg",
    )
    val tint by animateColorAsState(
        targetValue = if (isActive) Accent else TextSecondary,
        label = "tabTint",
    )

    // Pulsing alpha для glow вокруг активного таба
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "tabGlowPulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.40f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "tabGlow",
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            // Active tab — рисуем мягкое Accent сияние ВОКРУГ pill (stroke).
            .let {
                if (isActive) it.drawBehind {
                    val strokeW = 2f
                    drawRoundRect(
                        color = Accent.copy(alpha = pulseAlpha),
                        topLeft = androidx.compose.ui.geometry.Offset(-strokeW, -strokeW),
                        size = androidx.compose.ui.geometry.Size(size.width + strokeW * 2, size.height + strokeW * 2),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW * 2),
                    )
                } else it
            }
            // Non-active hover — radial cursorGlow (видно как мышка движется по табу)
            .let {
                if (!isActive) it.then(
                    Modifier.cursorGlowInternal(interaction, Accent)
                ) else it.hoverable(interaction)
            }
            .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)))
            .clickable(enabled = !isActive, onClick = onClick)
            .height(30.dp)
            .defaultMinSize(minWidth = 58.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            tab.label,
            color = tint,
            fontSize = 12.sp,
            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * §TZ-DESKTOP-0.10.4 — internal helper для cursor glow (как в MenuRow).
 * Не выносим в общий components/CursorGlowModifier.kt чтобы избежать
 * перекрёстных зависимостей desktop/sheets/ → desktop/ui/.
 */
@Composable
private fun Modifier.cursorGlowInternal(
    interaction: MutableInteractionSource,
    glowColor: Color,
): Modifier = composed {
    var cursor by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Unspecified) }
    val isHovered by interaction.collectIsHoveredAsState()

    this.hoverable(interaction)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val pos = event.changes.firstOrNull()?.position
                    if (pos != null) cursor = pos
                }
            }
        }
        .drawBehind {
            if (isHovered && cursor != androidx.compose.ui.geometry.Offset.Unspecified) {
                val r = 60.dp.toPx()
                drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = 0.18f),
                            glowColor.copy(alpha = 0.06f),
                            Color.Transparent,
                        ),
                        center = cursor,
                        radius = r,
                    ),
                    center = cursor,
                    radius = r,
                )
            }
        }
}
