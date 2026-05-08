package com.example.otlhelper.desktop.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.TextPrimary

/**
 * §TZ-DESKTOP 0.2.0 — hover-tooltip для icon-only кнопок. Появляется через
 * 600ms на hover. Используем foundation.TooltipArea (не material3.TooltipBox),
 * он проще на desktop и не требует state-объекта.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TooltipArea(
        tooltip = {
            Box(
                modifier = Modifier
                    .shadow(8.dp, RoundedCornerShape(6.dp))
                    .background(BgElevated, RoundedCornerShape(6.dp))
                    .border(0.5.dp, BorderDivider, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(text, color = TextPrimary, fontSize = 12.sp)
            }
        },
        delayMillis = 600,
        modifier = modifier,
    ) {
        content()
    }
}
