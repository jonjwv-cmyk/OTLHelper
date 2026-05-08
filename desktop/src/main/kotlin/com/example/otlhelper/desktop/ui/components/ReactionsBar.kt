package com.example.otlhelper.desktop.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddReaction
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.AccentSubtle
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary

/**
 * §TZ-DESKTOP — список разрешённых emoji (зеркалит app/.../Reactions.ALLOWED).
 *
 * §TZ-DESKTOP 0.3.1 — добавили U+FE0F (VARIATION SELECTOR-16) после ❤, без
 * него ❤ (U+2764) рендерится в ЧЁРНО-БЕЛОМ outline-варианте — glyph без
 * эмодзи-цвета. С \uFE0F Compose/Skia подхватывают colored emoji вариант
 * из системного шрифта (Apple Color Emoji на mac / Segoe UI Emoji на win).
 */
val AllowedEmojis = listOf("👍", "❤\uFE0F", "😂", "🎉", "✅")

/**
 * §TZ-DESKTOP-0.1.0 — копия app/core/ui/ReactionsBar. Показывает активные
 * реакции как chip'ы + кнопку «+» с popup-emoji-picker'ом. Работает
 * универсально для чата и новостей.
 *
 * - [onToggle] — клик по чипу (add если not selected, remove если selected)
 *   ИЛИ выбор emoji из popup'а (add реакцию).
 * - [onChipLongPress] — long-press/right-click по чипу (dev only → voters).
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun ReactionsBar(
    aggregate: Map<String, Int>,
    myReactions: Set<String>,
    onToggle: (emoji: String) -> Unit,
    modifier: Modifier = Modifier,
    showAddButton: Boolean = true,
    onChipLongPress: ((emoji: String) -> Unit)? = null,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val active = aggregate.filter { it.value > 0 }

    if (active.isEmpty() && !showAddButton) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((emoji, count) in active) {
            val selected = emoji in myReactions
            ReactionChip(
                emoji = emoji,
                count = count,
                selected = selected,
                onClick = { onToggle(emoji) },
                onLongClick = onChipLongPress?.let { cb -> { cb(emoji) } },
            )
        }

        if (!showAddButton) return@Row

        // «+» trigger + floating picker.
        Box {
            Icon(
                Icons.Outlined.AddReaction,
                contentDescription = "Добавить реакцию",
                tint = TextTertiary,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable { pickerOpen = !pickerOpen }
                    .padding(3.dp),
            )
            if (pickerOpen) {
                Popup(
                    alignment = Alignment.TopCenter,
                    offset = IntOffset(0, -120),
                    onDismissRequest = { pickerOpen = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Row(
                        modifier = Modifier
                            .shadow(12.dp, RoundedCornerShape(24.dp))
                            .background(BgElevated, RoundedCornerShape(24.dp))
                            .border(0.5.dp, BorderDivider, RoundedCornerShape(24.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (emoji in AllowedEmojis) {
                            Text(
                                emoji,
                                // §TZ-DESKTOP 0.3.2 — system emoji font для
                                // colored glyphs в picker'е (тот же fix что
                                // в ReactionChip для ❤️).
                                fontFamily = FontFamily.Default,
                                fontSize = 26.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        pickerOpen = false
                                        onToggle(emoji)
                                    }
                                    .padding(6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pill-shape chip. Клик → [onClick]. Long-press (удержание ЛКМ) ИЛИ правый
 * клик → [onLongClick]. Right-click consumes event, чтобы не срабатывал
 * parent-обработчик (action popup сообщения/карточки).
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun ReactionChip(
    emoji: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(50)
    val borderColor = if (selected) Accent else BorderDivider
    val bg = if (selected) AccentSubtle else BgCard

    Row(
        modifier = Modifier
            .height(26.dp)
            .clip(shape)
            .background(bg, shape)
            .border(0.5.dp, borderColor, shape)
            .then(
                if (onLongClick != null) {
                    Modifier
                        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                        .onPointerEvent(PointerEventType.Press) { e ->
                            if (e.buttons.isSecondaryPressed) {
                                // consume чтобы parent-handler на ChatBubble /
                                // NewsCard не открыл action-popup.
                                e.changes.forEach { it.consume() }
                                onLongClick()
                            }
                        }
                } else Modifier.clickable(onClick = onClick),
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // §TZ-DESKTOP 0.3.1 — центровка emoji и счётчика через LineHeightStyle
        // Center/Trim Both. До этого emoji (14sp, lineHeight 16) и count (11sp,
        // lineHeight 16) имели разные baselines внутри 26dp height Row →
        // счётчик сидел чуть ВЫШЕ emoji визуально ("не ровно рядом со
        // смайликом"). Теперь оба глифа геометрически по центру своих
        // line-box'ов.
        //
        // §TZ-DESKTOP 0.3.2 — fontFamily = FontFamily.Default для emoji
        // (ПРИНУДИТЕЛЬНО, не Inter). Почему: Inter не содержит emoji-glyph'ы,
        // а когда активный FontFamily — Inter (как у нас через MaterialTheme
        // типографику), Skia для codepoint'ов ❤ U+2764 сначала пробует Inter,
        // не находит colored-вариант, рисует MONOCHROME outline glyph. С
        // FontFamily.Default Skia сразу идёт в systemFont → Apple Color Emoji
        // на mac / Segoe UI Emoji на windows → ❤ рендерится КРАСНЫМ.
        val centerStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        )
        Text(
            emoji,
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontFamily = FontFamily.Default,
                fontSize = 14.sp,
                lineHeight = 14.sp,
                lineHeightStyle = centerStyle,
            ),
        )
        if (count > 0) {
            Spacer(Modifier.width(3.dp))
            Text(
                count.toString(),
                color = if (selected) Accent else TextSecondary,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    fontSize = 11.sp,
                    lineHeight = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    lineHeightStyle = centerStyle,
                ),
            )
        }
    }
}
