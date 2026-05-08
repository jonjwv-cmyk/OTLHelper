package com.example.otlhelper.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.otlhelper.desktop.theme.BorderDivider

/** §TZ-DESKTOP-0.1.0 — ThinDivider (0.5dp). Inset-вариант для разделения
 *  подгрупп внутри списка без подписей. */
@Composable
fun ThinDivider(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(0.5.dp)
            .background(BorderDivider),
    )
}
