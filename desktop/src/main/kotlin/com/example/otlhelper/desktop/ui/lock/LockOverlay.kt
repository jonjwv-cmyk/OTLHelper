package com.example.otlhelper.desktop.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
 * §TZ-0.10.5 — Заблокированная PC-сессия.
 *
 * DialogWindow (heavyweight overlay — обязательно DialogWindow поверх Sheets
 * webview, см. memory feedback_compose_heavyweight_overlays.md). Юзер видит
 * full-screen dark экран с инструкцией "Сканируй QR с Android".
 *
 * onReQrLogin — открывает обычный LoginScreen с QR mode. После успешного
 * QR-redeem'а юзер получает новую сессию и LockOverlay скрывается.
 */
@Composable
fun LockOverlay(
    onReQrLogin: () -> Unit,
) {
    val state = rememberDialogState(
        position = WindowPosition(Alignment.Center),
        size = DpSize(560.dp, 360.dp),
    )
    DialogWindow(
        onCloseRequest = { /* нельзя закрыть — это блокировка */ },
        state = state,
        title = "OTLD Helper — Сессия заблокирована",
        resizable = false,
        undecorated = false,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(BgApp).padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("🔒", fontSize = 56.sp)
                Spacer(Modifier.height(14.dp))
                Text(
                    "Сессия заблокирована",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Нерабочее время. Разблокируйте через QR в Android-приложении:",
                    color = TextSecondary,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Меню → Войти на ПК → отсканируйте QR на этом экране.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(22.dp))
                Button(
                    onClick = onReQrLogin,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = BgCard,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(44.dp).width(220.dp),
                ) {
                    Text("Показать QR", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }
    }
}

/**
 * Banner-prompt "Продлить сессию ещё на 30 мин?" — показывается за 5 мин до
 * expiry если есть extension'ы. Тоже DialogWindow для надёжности поверх Sheets.
 */
@Composable
fun ExtensionPromptDialog(
    yekHm: String,
    extensionsRemaining: Int,
    onExtend: () -> Unit,
    onDismiss: () -> Unit,
) {
    // §0.10.26 — увеличен размер окна (220→320 высота). На Win
    // native title bar (~30dp) + DpSize 220 не хватало места для
    // bottom button "Завершить сессию". Юзер видел дефис "-"
    // вместо обрезанной нижней кнопки.
    // §0.11.4 — 320→230. Точный расчёт: padding 24 + title 22 +
    // sp 6 + text 18 + sp 18 + button 40 + sp 8 + textbutton 30 +
    // padding 24 + title bar 32 ≈ 222. С запасом 230.
    val state = rememberDialogState(
        position = WindowPosition(Alignment.Center),
        size = DpSize(460.dp, 230.dp),
    )
    DialogWindow(
        onCloseRequest = onDismiss,
        state = state,
        title = "Продление сессии",
        resizable = false,
        undecorated = false,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(BgApp).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Сессия закончится в $yekHm",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Осталось продлений: $extensionsRemaining",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = onExtend,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = BgCard,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(40.dp).width(220.dp),
                ) {
                    Text("Продлить +30 мин", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                // §TZ-DESKTOP-0.10.13 — onDismiss сейчас performLogout (не
                // просто скрытие диалога). Label поменян чтобы это было
                // явно — юзер выходит из сессии полностью, сразу видит QR.
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text("Завершить сессию", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}
