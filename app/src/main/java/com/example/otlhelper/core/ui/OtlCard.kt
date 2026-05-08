package com.example.otlhelper.core.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.BorderDivider
import androidx.compose.foundation.background

val CardShape = RoundedCornerShape(16.dp)
val CardShapeSmall = RoundedCornerShape(12.dp)

@Composable
fun OtlCard(
    modifier: Modifier = Modifier,
    color: Color = BgCard,
    borderColor: Color = BorderDivider,
    borderWidth: Dp = 0.5.dp,
    cornerRadius: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(color, shape)
            .border(borderWidth, borderColor, shape)
            .padding(16.dp),
        content = content
    )
}
