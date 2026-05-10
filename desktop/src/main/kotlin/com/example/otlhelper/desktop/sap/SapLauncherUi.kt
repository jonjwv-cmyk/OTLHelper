package com.example.otlhelper.desktop.sap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.BgApp
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary

/**
 * §0.11.9 — корневая Compose-интеграция SAP triple-copy launcher'а.
 *
 * Зовётся из главного App composable один раз. Делает:
 *  • DisposableEffect стартует SapClipboardLauncher.init() при mount,
 *    SapClipboardLauncher.shutdown() при unmount (т.е. при выходе из app).
 *  • Подписана на events flow → если non-null → показывает DialogWindow
 *    с описанием ошибки. Dialog auto-dismiss через 4 сек (logic в launcher).
 *
 * Heavyweight DialogWindow — обязательно поверх Sheets webview зоны
 * (memory feedback_compose_heavyweight_overlays.md).
 */
@Composable
fun SapLauncherIntegration() {
    // §0.11.9 — triple Ctrl+C launcher (буфер → SAP)
    DisposableEffect(Unit) {
        SapClipboardLauncher.init()
        onDispose { SapClipboardLauncher.shutdown() }
    }

    // §0.11.10 — Ctrl+Q listener (SAP → OTLHelper inspector)
    DisposableEffect(Unit) {
        if (com.example.otlhelper.desktop.BuildInfo.IS_WINDOWS) {
            GlobalSapHotkey.start { SapInspectorController.trigger() }
            com.example.otlhelper.desktop.core.debug.DebugLogger.log(
                "SAP_INSPECTOR", "Ctrl+Q hotkey registered"
            )
        }
        onDispose {
            if (com.example.otlhelper.desktop.BuildInfo.IS_WINDOWS) {
                GlobalSapHotkey.stop()
            }
        }
    }

    // Inspector dialog (отображается когда state != Hidden)
    SapInspectorRoot()

    val event by SapClipboardLauncher.events.collectAsState()
    val current = event ?: return

    val title: String
    val message: String
    when (current) {
        is SapLauncherEvent.InvalidFormat -> {
            title = "SAP — формат не распознан"
            message = "Скопированное значение не похоже на номер заказа или поставки.\n\n" +
                "Допустимо:\n" +
                "  • Заказ: 10 цифр, начало 42 / 43 / 44\n" +
                "  • Поставка: 7 или 8 цифр, начало 5 / 6\n\n" +
                "В буфере: \"${current.clipPreview}\""
        }
        is SapLauncherEvent.InternalError -> {
            title = "SAP — ошибка запуска"
            message = "Не удалось запустить SAP-макрос.\n\n${current.message}"
        }
    }

    val dialogState = rememberDialogState(
        position = WindowPosition(Alignment.Center),
        size = DpSize(460.dp, 270.dp),
    )

    DialogWindow(
        onCloseRequest = { SapClipboardLauncher.dismissEvent() },
        state = dialogState,
        title = "OTL — SAP",
        resizable = false,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(BgApp).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    title,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    message,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { SapClipboardLauncher.dismissEvent() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = BgCard,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(40.dp),
                ) {
                    Text("OK", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
