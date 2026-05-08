package com.example.otlhelper.desktop.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.AccentSubtle
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.BgInput
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary
import com.example.otlhelper.desktop.ui.components.ThinDivider

/**
 * §TZ-DESKTOP 0.3.0 — Command Palette (⌘K / Cmd+K).
 *
 * §RULE-DESKTOP-OVERLAY-2026-04-25 — рендерится через [DialogWindow] (отдельное
 * native-окно OS), не lightweight Box-overlay. Без этого heavyweight Chromium
 * NSView (Sheets-зона) перекрывает палитру — её не видно. См. memory
 * feedback_compose_heavyweight_overlays.
 *
 * Стиль: Linear / Raycast / Superhuman — всплывающий центрированный список
 * команд с fuzzy-поиском. Клавиатура: ↑/↓ — навигация, Enter — выполнить,
 * Esc — закрыть.
 *
 * Архитектура:
 *   - [PaletteCommand] — структура команды (label + action)
 *   - [CommandPalette] композабл: DialogWindow с TextField + LazyColumn результатов
 *   - Фильтр: case-insensitive substring на label и optional keywords
 *   - Вызывающий код (MainScreen) строит список команд на лету из текущего
 *     state (currentTab, role, menuOpen, и т.д.)
 *
 * Комментарий для нового чата / разработчика бизнес-логики:
 *   Palette — это чисто UI overlay. Он НЕ меняет серверное поведение.
 *   Каждая команда — ссылка на существующий callback в MainScreen
 *   (например onTabChange, onLogout и т.п.). Если добавляете новую команду,
 *   просто добавьте PaletteCommand в buildCommands() в MainScreen.
 */
data class PaletteCommand(
    val id: String,
    val label: String,
    val icon: ImageVector? = null,
    val shortcut: String? = null,
    val keywords: List<String> = emptyList(),
    val action: () -> Unit,
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CommandPalette(
    commands: List<PaletteCommand>,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    val filtered = remember(query, commands) {
        if (query.isBlank()) commands
        else {
            val q = query.trim().lowercase()
            commands.filter { cmd ->
                cmd.label.lowercase().contains(q) ||
                    cmd.keywords.any { it.lowercase().contains(q) } ||
                    fuzzyMatch(cmd.label.lowercase(), q)
            }
        }
    }

    // Reset selection when filter changes.
    LaunchedEffect(query) { selectedIndex = 0 }
    // Auto-focus TextField on open. Задержка нужна чтобы DialogWindow
    // успел встать в focus chain до requestFocus().
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { focusRequester.requestFocus() }
    }

    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(
            position = WindowPosition.Aligned(Alignment.Center),
            size = DpSize(520.dp, 480.dp),
        ),
        undecorated = true,
        // §RULE-DESKTOP-OVERLAY-2026-04-25 — transparent=false **обязательно**.
        // На macOS transparent=true + heavyweight Chromium NSView в parent окне
        // = NSPanel/NSView focus/z-order конфликт → app crash при открытии.
        // Округлые углы получаем через window.shape (см. DisposableEffect ниже).
        transparent = false,
        resizable = false,
        focusable = true,
        alwaysOnTop = true,
        onPreviewKeyEvent = { ev ->
            if (ev.type != KeyEventType.KeyDown) return@DialogWindow false
            when (ev.key) {
                Key.Escape -> { onDismiss(); true }
                Key.DirectionDown -> {
                    if (filtered.isNotEmpty()) {
                        selectedIndex = (selectedIndex + 1) % filtered.size
                    }
                    true
                }
                Key.DirectionUp -> {
                    if (filtered.isNotEmpty()) {
                        selectedIndex = if (selectedIndex == 0) filtered.size - 1
                        else selectedIndex - 1
                    }
                    true
                }
                Key.Enter, Key.NumPadEnter -> {
                    val cmd = filtered.getOrNull(selectedIndex)
                    if (cmd != null) {
                        cmd.action()
                        onDismiss()
                    }
                    true
                }
                else -> false
            }
        },
    ) {
        // §TZ-DESKTOP 0.4.x — blur Sheets canvas через CSS-инжект (тот же
        // паттерн что в CentralSearchDialog). См. SheetsViewBridge.
        DisposableEffect(Unit) {
            com.example.otlhelper.desktop.sheets.SheetsViewBridge.setBlur(true)
            onDispose {
                com.example.otlhelper.desktop.sheets.SheetsViewBridge.setBlur(false)
            }
        }

        // Округлые углы + close-on-outside-click (см. CentralSearchDialog
        // для детального обоснования). Тот же паттерн.
        DisposableEffect(window) {
            val applyShape = {
                if (window.width > 0 && window.height > 0) {
                    window.shape = java.awt.geom.RoundRectangle2D.Float(
                        0f, 0f,
                        window.width.toFloat(), window.height.toFloat(),
                        14f, 14f,
                    )
                }
            }
            applyShape()
            val resizeListener = object : java.awt.event.ComponentAdapter() {
                override fun componentShown(e: java.awt.event.ComponentEvent?) = applyShape()
                override fun componentResized(e: java.awt.event.ComponentEvent?) = applyShape()
            }
            window.addComponentListener(resizeListener)

            var hasGainedFocus = false
            val focusListener = object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {
                    hasGainedFocus = true
                }
                override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                    if (hasGainedFocus) onDismiss()
                }
            }
            window.addWindowFocusListener(focusListener)

            onDispose {
                window.removeComponentListener(resizeListener)
                window.removeWindowFocusListener(focusListener)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgElevated)
                .border(0.5.dp, BorderDivider),
        ) {
            // Search input.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (query.isEmpty()) {
                        Text(
                            "Команда или поиск…",
                            color = TextTertiary,
                            fontSize = 14.sp,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                        cursorBrush = SolidColor(Accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                }
            }
            ThinDivider()
            // Results.
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Ничего не найдено",
                        color = TextTertiary,
                        fontSize = 13.sp,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    filtered.forEachIndexed { idx, cmd ->
                        CommandRow(
                            command = cmd,
                            selected = idx == selectedIndex,
                            onHover = { selectedIndex = idx },
                            onClick = {
                                cmd.action()
                                onDismiss()
                            },
                        )
                    }
                }
            }
            // Footer hint.
            ThinDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgInput)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShortcutHint("↑ ↓")
                Spacer(Modifier.width(6.dp))
                Text("навигация", color = TextTertiary, fontSize = 11.sp)
                Spacer(Modifier.width(16.dp))
                ShortcutHint("↵")
                Spacer(Modifier.width(6.dp))
                Text("выполнить", color = TextTertiary, fontSize = 11.sp)
                Spacer(Modifier.width(16.dp))
                ShortcutHint("Esc")
                Spacer(Modifier.width(6.dp))
                Text("закрыть", color = TextTertiary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun CommandRow(
    command: PaletteCommand,
    selected: Boolean,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) AccentSubtle else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (command.icon != null) {
            Icon(
                command.icon,
                contentDescription = null,
                tint = if (selected) Accent else TextSecondary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            command.label,
            color = if (selected) TextPrimary else TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (command.shortcut != null) {
            ShortcutHint(command.shortcut)
        }
    }
    @Suppress("UNUSED_EXPRESSION") onHover
}

@Composable
private fun ShortcutHint(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(BgElevated)
            .border(0.5.dp, BorderDivider, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Простой fuzzy-match: все символы запроса встречаются в label в порядке.
 * "зпл" → "Запланированные" (з,п,л — есть в нужном порядке).
 * Не так мощный как Linear / VSCode, но для 10-20 команд хватает с избытком.
 */
private fun fuzzyMatch(label: String, query: String): Boolean {
    if (query.isEmpty()) return true
    var i = 0
    for (ch in label) {
        if (ch.equals(query[i], ignoreCase = true)) {
            i++
            if (i == query.length) return true
        }
    }
    return false
}
