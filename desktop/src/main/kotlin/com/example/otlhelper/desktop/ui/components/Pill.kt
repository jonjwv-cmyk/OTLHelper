package com.example.otlhelper.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * §TZ-DESKTOP 0.2.0 — единый pill-компонент. Приходит на замену UnreadBadge,
 * UnreadChip, inline status-бейджам, pinned-chip. Три размера:
 *
 *   Sm  — счётчик непрочитанных в tab-иконке (min 18dp, 10sp)
 *   Md  — счётчик в inbox-row / status-бейдж (min 22dp, 12sp)
 *   Lg  — inline-tag в шапке новости / крупные чипы (min 26dp, 13sp)
 */
enum class PillSize(
    val minSize: Dp,
    val fontSize: TextUnit,
    val horizontalPadding: Dp,
    val iconSize: Dp,
) {
    Sm(minSize = 18.dp, fontSize = 10.sp, horizontalPadding = 6.dp, iconSize = 10.dp),
    Md(minSize = 22.dp, fontSize = 12.sp, horizontalPadding = 8.dp, iconSize = 12.dp),
    Lg(minSize = 26.dp, fontSize = 13.sp, horizontalPadding = 10.dp, iconSize = 14.dp),
}

@Composable
fun Pill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    size: PillSize = PillSize.Md,
    borderColor: Color? = null,
    icon: ImageVector? = null,
    bold: Boolean = true,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = size.minSize, minHeight = size.minSize)
            .clip(shape)
            .background(containerColor, shape)
            .then(if (borderColor != null) Modifier.border(0.5.dp, borderColor, shape) else Modifier)
            .padding(horizontal = size.horizontalPadding, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(size.iconSize))
                Spacer(Modifier.width(4.dp))
            }
            // §TZ-DESKTOP 0.2.1 — тот же паттерн что в UserAvatar:
            // LineHeightStyle Center+Trim Both для точной вертикальной
            // центровки глифов внутри pill'а.
            Text(
                text,
                color = contentColor,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    fontSize = size.fontSize,
                    lineHeight = size.fontSize,
                    fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium,
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both,
                    ),
                ),
            )
        }
    }
}
