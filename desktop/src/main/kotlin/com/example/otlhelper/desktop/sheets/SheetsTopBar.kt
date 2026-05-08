package com.example.otlhelper.desktop.sheets

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.ui.components.Tooltip

/**
 * §TZ-DESKTOP 0.4.x — верхняя плашка Sheets-зоны.
 *
 * Структура (~40dp):
 * ```
 *   [file-switcher]    — — — —    [▶ action] [Σ action] [↻ reload]
 *   ↑ слева                                  ↑ справа: контекстные actions + utility
 * ```
 *
 * **File-switcher** эволюционирует с числом файлов:
 *   • 1-3 файла → inline pill-toggle (segmented control стиля iOS)
 *   • 4-9 → dropdown с chevron
 *   • 10+ → dropdown + поиск (TODO когда понадобится)
 *
 * **Apps Script кнопки** читаются по `activeFile.id × activeSheet.gid` из
 * [SheetsRegistry] и передаются сюда параметром `actions`. Если пусто —
 * правая часть пустая (никаких stub-кнопок).
 *
 * Паттерн навигации: Linear / Arc / Notion 2026 (top-bar + контекстный
 * toolbar справа). См. memory `project_sheets_nav_architecture`.
 */
@Composable
fun SheetsTopBar(
    files: List<SheetsFile>,
    activeFile: SheetsFile,
    onFileChange: (SheetsFile) -> Unit,
    actions: List<SheetAction>,
    onAction: (SheetAction) -> Unit,
    onReload: () -> Unit,
    onGoogleMenu: (GoogleSheetsNativeMenu) -> Unit,
    modifier: Modifier = Modifier,
    // §TZ-DESKTOP 0.4.x round 11 — actions disabled когда sheet locked
    // (Apps Script выполняется). Юзер видит кнопку greyed out + non-clickable
    // вместо возможности дёрнуть второй раз.
    actionsEnabled: Boolean = true,
    // §TZ-DESKTOP-UX-2026-04 — refresh-кнопка disabled пока идёт reload
    // (overlay с котом, browser скрыт), чтобы юзер не мог запустить
    // второй reload поверх первого.
    reloading: Boolean = false,
) {
    Box(modifier = modifier.fillMaxWidth().background(BgCard)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 36dp матчит height боковой PanelToggleBar — нижняя
                // граница TopBar Sheets и sidebar выровнена в одну линию.
                .height(40.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileSwitcher(
                files = files,
                activeFile = activeFile,
                onFileChange = onFileChange,
            )

            Spacer(Modifier.weight(1f))

            // §TZ-DESKTOP 0.4.x — Apps Script action buttons.
            // Пустой список → правая часть пустая. Никаких stub'ов / placeholder'ov.
            actions.forEach { action ->
                ActionButton(
                    action = action,
                    enabled = actionsEnabled,
                    onClick = { onAction(action) },
                )
                Spacer(Modifier.width(6.dp))
            }

            GoogleSheetsNativeMenu.entries.forEach { menu ->
                // §TZ-DESKTOP-UX-2026-04 — нативные пункты Google (Правка /
                // Вставка / Данные) блокируем в Anonymous так же как наши
                // Apps Script кнопки. Юзер без login физически не может
                // редактировать таблицу, поэтому disabled = read-only signal.
                GoogleMenuButton(
                    menu = menu,
                    onClick = { onGoogleMenu(menu) },
                    enabled = actionsEnabled,
                )
                Spacer(Modifier.width(4.dp))
            }

            // Utility — reload Sheets (browser.reload).
            UtilityIconButton(
                icon = Icons.Outlined.Refresh,
                contentDescription = "Перезагрузить таблицу",
                onClick = onReload,
                enabled = !reloading,
            )
        }
        // Bottom border — отделяет TopBar от TabStrip.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .height(0.5.dp)
                .background(BorderDivider),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun GoogleMenuButton(
    menu: GoogleSheetsNativeMenu,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    var hovered by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        targetValue = if (hovered && enabled) BgElevated else Color.Transparent,
        label = "googleMenuBg",
    )
    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> TextSecondary.copy(alpha = 0.4f)
            hovered -> TextPrimary
            else -> TextSecondary
        },
        label = "googleMenuTint",
    )
    val borderColor = if (enabled) BorderDivider else BorderDivider.copy(alpha = 0.4f)
    Tooltip(
        text = if (enabled) "Открыть меню ${menu.title}"
        else "Доступно после входа в Google",
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .border(0.5.dp, borderColor, RoundedCornerShape(8.dp))
                .pointerHoverIcon(
                    PointerIcon(java.awt.Cursor.getPredefinedCursor(
                        if (enabled) java.awt.Cursor.HAND_CURSOR else java.awt.Cursor.DEFAULT_CURSOR
                    ))
                )
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .clickable(enabled = enabled, onClick = onClick)
                .height(30.dp)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                menu.title,
                color = tint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ── File switcher ──────────────────────────────────────────────────────────

@Composable
private fun FileSwitcher(
    files: List<SheetsFile>,
    activeFile: SheetsFile,
    onFileChange: (SheetsFile) -> Unit,
) {
    if (files.size <= 3) {
        SegmentedFileSwitcher(files, activeFile, onFileChange)
    } else {
        DropdownFileSwitcher(files, activeFile, onFileChange)
    }
}

/** iOS-style segmented control. Подходит для 1-3 файлов. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SegmentedFileSwitcher(
    files: List<SheetsFile>,
    activeFile: SheetsFile,
    onFileChange: (SheetsFile) -> Unit,
) {
    // §TZ-DESKTOP-0.10.4 — Active file: pulsing Accent halo + AccentSubtle bg.
    // Non-active hover: cursor glow.
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "fileSwitcherPulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.40f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "fileSwitcherGlow",
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BgElevated)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        files.forEach { file ->
            val isActive = file.id == activeFile.id
            val interaction = remember(file.id) { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val bg by animateColorAsState(
                targetValue = if (isActive) BgCard else Color.Transparent,
                label = "fileSegBg",
            )
            val tint by animateColorAsState(
                targetValue = if (isActive) Accent else TextSecondary,
                label = "fileSegTint",
            )
            var cursor by remember(file.id) { mutableStateOf(androidx.compose.ui.geometry.Offset.Unspecified) }
            val isHovered by interaction.collectIsHoveredAsState()

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg)
                    .let {
                        if (isActive) it.drawBehind {
                            val sw = 2f
                            drawRoundRect(
                                color = Accent.copy(alpha = pulseAlpha),
                                topLeft = androidx.compose.ui.geometry.Offset(-sw, -sw),
                                size = androidx.compose.ui.geometry.Size(size.width + sw * 2, size.height + sw * 2),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw * 2),
                            )
                        } else it
                            .hoverable(interaction)
                            .pointerInput(file.id) {
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
                                                Accent.copy(alpha = 0.16f),
                                                Accent.copy(alpha = 0.06f),
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
                    .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)))
                    .clickable(enabled = !isActive) { onFileChange(file) }
                    .height(30.dp)
                    .defaultMinSize(minWidth = 72.dp)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    file.title,
                    color = tint,
                    fontSize = 12.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

/** Dropdown-кнопка с chevron. Для 4+ файлов. */
@Composable
private fun DropdownFileSwitcher(
    files: List<SheetsFile>,
    activeFile: SheetsFile,
    onFileChange: (SheetsFile) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(BgElevated)
                .clickable { expanded = true }
                .height(30.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                activeFile.title,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            files.forEach { file ->
                DropdownMenuItem(
                    text = {
                        Text(
                            file.title,
                            color = if (file.id == activeFile.id) Accent else TextPrimary,
                            fontWeight = if (file.id == activeFile.id) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onFileChange(file)
                        expanded = false
                    },
                )
            }
        }
    }
}

// ── Action button (Apps Script) ────────────────────────────────────────────

/**
 * SF 2026 stylish action button — warm bronze gradient на hover, soft
 * border, refined typography. Designed как «affordance кнопка» — visible
 * accent (subtle bronze fill) даже в idle state чтобы юзер сразу понял
 * что кнопка интерактивна (не часть chrome'a). Disabled state — заметно
 * приглушён (50% opacity) + cursor default.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ActionButton(
    action: SheetAction,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val active = hovered && enabled
    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> AccentSubtle.copy(alpha = 0.06f)
            active -> Accent.copy(alpha = 0.22f)
            else -> AccentSubtle
        },
        label = "actionBg",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> BorderDivider.copy(alpha = 0.4f)
            active -> Accent.copy(alpha = 0.55f)
            else -> Accent.copy(alpha = 0.30f)
        },
        label = "actionBorder",
    )
    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> TextSecondary.copy(alpha = 0.5f)
            active -> Accent
            else -> Accent.copy(alpha = 0.92f)
        },
        label = "actionTint",
    )
    Tooltip(text = action.confirmTitle ?: action.label) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .border(0.5.dp, borderColor, RoundedCornerShape(8.dp))
                .pointerHoverIcon(
                    if (enabled) {
                        PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR))
                    } else {
                        PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.DEFAULT_CURSOR))
                    },
                )
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .clickable(enabled = enabled, onClick = onClick)
                .height(30.dp)
                .padding(horizontal = 12.dp),
            // Icon (16dp) и Text вертикально centered через Box wrap
            // каждого — Compose Text имеет invisible top/bottom padding
            // (line-height > glyph height), что смещает baseline относительно
            // square icon. Box(Alignment.Center) align'ит обоих визуально.
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    action.icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
            }
            Box(contentAlignment = Alignment.Center) {
                Text(
                    action.label,
                    color = tint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}

// ── Utility icon button (reload, etc.) ─────────────────────────────────────

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun UtilityIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    var hovered by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        targetValue = if (hovered && enabled) BgElevated else Color.Transparent,
        label = "utilBg",
    )
    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> TextSecondary.copy(alpha = 0.4f)
            hovered -> TextPrimary
            else -> TextSecondary
        },
        label = "utilTint",
    )
    Tooltip(text = contentDescription) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(if (enabled) java.awt.Cursor.HAND_CURSOR else java.awt.Cursor.DEFAULT_CURSOR)))
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
