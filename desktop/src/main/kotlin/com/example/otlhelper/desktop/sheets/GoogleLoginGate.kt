package com.example.otlhelper.desktop.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.ui.AppOverlay

/**
 * §TZ-DESKTOP-UX-2026-04 — состояния Google Sheets зоны. Упрощённая
 * state machine (после ревизии 2026-04):
 *
 *   Detecting → [SignedIn | Anonymous]       (silent URL probe ~3s)
 *   Anonymous → Authenticating               (3-я кнопка «Войти в Google»)
 *   Authenticating → [SignedIn | Anonymous]  (30-сек URL poll)
 *   SignedIn → Anonymous                     («Выход из Google» + confirm)
 *
 * - [Detecting] cold-start. Грузим ServiceLogin URL невидимо; если Google
 *   уже залогинен (cookie живой) — он сам redirect'нёт на sheet через
 *   `continue`. Polling browser.currentUrl ≤3s; если ушёл на
 *   `docs.google.com/spreadsheets` → [SignedIn], иначе → [Anonymous]
 *   (без диалога Нет/Да — юзер сразу видит таблицу в просмотре).
 * - [Anonymous] просмотр без login. Action buttons disabled, в TabStrip
 *   кнопка «Войти в Google».
 * - [Authenticating] юзер нажал «Войти в Google». Browser visible с login
 *   form. 30-сек poll: ушёл на sheet → [SignedIn]; timeout → [Anonymous].
 * - [SignedIn] юзер вошёл; actions включены, в TabStrip кнопка
 *   «Выход из Google» (с confirm-диалогом).
 */
enum class GoogleLoginChoice { Detecting, Authenticating, SignedIn, Anonymous }

/**
 * §TZ-DESKTOP-UX-2026-04 — confirm dialog при нажатии «Выход из Google»
 * в TabStrip. Cat-splash сверху + card с двумя кнопками — единый стиль
 * для всех Sheets-prompt'ов.
 */
@Composable
fun GoogleLogoutConfirmDialog(
    onYes: () -> Unit,
    onNo: () -> Unit,
    rightInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    AppOverlay {
        DisposableEffect(Unit) {
            SheetsViewBridge.setBlur(true)
            onDispose { SheetsViewBridge.setBlur(false) }
        }
        GoogleSheetsPromptScaffold(
            title = "Выйти из Google?",
            subtitle = "Cookie аккаунта будут удалены. Можно снова войти кнопкой " +
                "«Войти в Google» в панели вкладок.",
            primaryLabel = "Выйти",
            secondaryLabel = "Отмена",
            onPrimary = onYes,
            onSecondary = onNo,
            modifier = modifier,
        )
    }
}

/** Общий scaffold для 2-кнопочных prompt'ов в Sheets. */
@Composable
private fun GoogleSheetsPromptScaffold(
    title: String,
    subtitle: String,
    primaryLabel: String,
    secondaryLabel: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().background(Color(0xFF111113)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            // Тот же визуальный паттерн что cold-start splash и action lock —
            // 220dp Box с halo за Lottie-котом. Узнаваемо для юзера.
            Box(
                modifier = Modifier.size(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                CatHalo()
                LottieCatAnimation(modifier = Modifier.size(180.dp))
            }
            Spacer(Modifier.height(20.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(BgElevated)
                    .border(0.5.dp, BorderDivider, RoundedCornerShape(18.dp))
                    .padding(PaddingValues(horizontal = 24.dp, vertical = 22.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    subtitle,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onSecondary,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(secondaryLabel, color = TextPrimary, fontSize = 14.sp)
                    }
                    Button(
                        onClick = onPrimary,
                        modifier = Modifier.weight(1f).height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Color.Black,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(primaryLabel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
