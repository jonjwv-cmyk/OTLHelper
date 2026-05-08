package com.example.otlhelper.desktop.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.example.otlhelper.desktop.theme.BgApp
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.TextSecondary

private val isMac: Boolean =
    System.getProperty("os.name")?.lowercase()?.contains("mac") == true

/** §TZ-DESKTOP-0.0.1 — отступ слева под нативные mac traffic-lights
 *  (red/yellow/green кнопки которые рисует macOS когда window decorated).
 *  Они расположены в верхнем левом углу title-bar area и занимают ≈78px. */
private val MAC_TRAFFIC_LIGHT_RESERVED = 78.dp

/**
 * Полоса заголовка:
 * - **macOS**: window decorated (ОС даёт native рамку + скруглённые углы +
 *   traffic-lights). Мы просто красим tall bar (28dp) — OS traffic-lights
 *   лежат поверх в своих координатах. Оставляем слева отступ под них.
 *   Drag окна обрабатывается нативно.
 * - **Windows**: window decorated (системный title bar с кнопками свернуть/
 *   развернуть/закрыть) — наш AppTitleBar НЕ рисуется, чтобы не дублировать
 *   native chrome. (До 0.5.1 был undecorated + кастомный бар, но Compose
 *   Desktop на Win с MenuBar всё равно показывал тройной заголовок.)
 */
@Composable
fun FrameWindowScope.AppTitleBar(
    windowState: WindowState,
    onClose: () -> Unit,
) {
    if (!isMac) return // Win/Linux — используем native window chrome
    if (windowState.placement == WindowPlacement.Fullscreen) return
    MacTitleBar()
}

@Composable
private fun MacTitleBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(BgApp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Отступ под native traffic-lights (OS их рисует поверх этой области).
        Spacer(Modifier.width(MAC_TRAFFIC_LIGHT_RESERVED))
        runCatching {
            Image(
                painter = painterResource("icon.png"),
                contentDescription = null,
                modifier = Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            "OTLD Helper",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(BorderDivider),
    )
}

// §TZ-DESKTOP-DIST 0.5.1 — WindowsTitleBar + WinButton удалены: на Win
// используем native window chrome (см. Main.kt undecorated=false).
