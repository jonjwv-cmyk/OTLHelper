package com.example.otlhelper.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.otlhelper.core.theme.BorderDivider

// Small shared UI primitives used across screens, sheets, and dialogs.
// One file keeps all the "never re-implement this" visuals together.

/**
 * Drag handle for `ModalBottomSheet`.
 *
 *     ModalBottomSheet(..., dragHandle = { DialogDragHandle() }) { ... }
 *
 * Dimensions fixed by design — one visual language across every sheet.
 */
@Composable
fun DialogDragHandle() {
    Box(
        modifier = Modifier
            .padding(vertical = 10.dp)
            .size(36.dp, 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(BorderDivider)
    )
}

/**
 * Full-width 0.5dp divider line, [BorderDivider] colour. Use anywhere a
 * horizontal section break is needed — chat header → messages, pinned pills
 * → feed, toolbar → content, etc. Prefer this over inline
 * `Box(Modifier.fillMaxWidth().height(0.5.dp).background(BorderDivider))`.
 */
@Composable
fun ThinDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(BorderDivider)
    )
}
