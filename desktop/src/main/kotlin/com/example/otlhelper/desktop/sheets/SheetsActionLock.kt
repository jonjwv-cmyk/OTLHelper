package com.example.otlhelper.desktop.sheets

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.theme.BgApp
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * §TZ-DESKTOP 0.4.x round 11 — Apps Script execution lock.
 *
 * **Семантика:** когда юзер кликает action-button (Apps Script trigger),
 * сервер бродкастит lock-event через WebSocket → ВСЕ desktop клиенты
 * получают [SheetsActionLock] для этой sheet tab → блокируется работа
 * с этой вкладкой пока скрипт работает. После завершения скрипта
 * сервер бродкастит unlock → все клиенты убирают overlay.
 *
 * Это предотвращает race condition: два юзера не могут одновременно
 * запустить макрос на одной таблице (Google Apps Script throttle, плюс
 * data corruption если скрипты пишут в те же ячейки).
 *
 * Phase 1 (визуальный прототип) — lock устанавливается локально в
 * SheetsWorkspace, держится 3 секунды (mock timer). Phase 2 — серверный
 * endpoint + WS broadcast.
 */
data class SheetsActionLock(
    val actionId: String,
    val actionLabel: String,
    val userName: String,
    val tabName: String,
    /**
     * rawNames листов которые currently locked. Overlay показывается
     * только когда юзер на одном из этих tabs; на остальных tabs всё
     * работает нормально (можно листать, кликать ячейки, запускать
     * actions других scope'ов).
     */
    val lockedTabRawNames: List<String>,
)

/**
 * Полноэкранный overlay поверх Sheets browser slot. Cat splash + warm
 * bronze halo (тот же pattern что cold-start splash) + статус «Имя
 * пользователя — запущен макрос (Имя действия)».
 *
 * **Зависит от того что webview скрыт** (SheetsBrowserController.setVisible
 * (false)) пока lock активен — иначе heavyweight WKWebView NSView съедает
 * lightweight Compose Box visibility (см. memory rule).
 */
@Composable
fun SheetsActionLockOverlay(
    lock: SheetsActionLock,
    modifier: Modifier = Modifier,
) {
    // §TZ-DESKTOP-UX-2026-04 — sequenced reveal как у action button:
    //   Phase 0 (0-1.5s): "Запускаем…" + 3 пульс-точки. Объясняет
    //                     юзеру что что-то происходит до того как Lottie
    //                     успеет load+первый кадр.
    //   Phase 1 (после 1.5s): cat fade-in + halo + статус «<имя> — макрос».
    //
    // Re-trigger при смене actionId (новый lock = новый запуск = снова с phase 0).
    var showCat by remember(lock.actionId) { mutableStateOf(false) }
    LaunchedEffect(lock.actionId) {
        delay(1500)
        showCat = true
    }
    val catAlpha by animateFloatAsState(
        targetValue = if (showCat) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "lock_cat_alpha",
    )
    val launchTextAlpha by animateFloatAsState(
        targetValue = if (showCat) 0f else 1f,
        animationSpec = tween(durationMillis = 400),
        label = "lock_launch_alpha",
    )

    Box(
        modifier = modifier.fillMaxSize().background(BgApp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.widthIn(max = 480.dp),
        ) {
            Box(
                modifier = Modifier.size(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                // «Запускаем…» phase — text + dots вместо кота.
                if (launchTextAlpha > 0.01f) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.alpha(launchTextAlpha),
                    ) {
                        Text(
                            "Запускаем",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        SplashProgressDots()
                    }
                }
                // Cat phase — поверх через alpha; halo + Lottie.
                if (catAlpha > 0.01f) {
                    Box(
                        modifier = Modifier.fillMaxSize().alpha(catAlpha),
                        contentAlignment = Alignment.Center,
                    ) {
                        ActionLockHalo()
                        LottieCatAnimation(modifier = Modifier.size(180.dp))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            // Текст пользователя/действия — fade-in вместе с котом, чтобы
            // в phase 0 был только лаконичный «Запускаем…».
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.alpha(catAlpha),
            ) {
                Text(
                    lock.userName,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "запущен макрос",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    lock.actionLabel,
                    color = Color(0xFFD4A467),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                SplashProgressDots()
            }
        }
    }
}

/**
 * Дышащий warm-bronze halo — порт с Sheets cold-start splash. Чуть более
 * intense alpha values для action lock (0.6→0.95 vs 0.55→0.85), чтобы
 * визуально signaled «занят, не трогай».
 */
@Composable
private fun ActionLockHalo() {
    val transition = rememberInfiniteTransition(label = "lock_halo")
    val haloScale by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "halo_scale",
    )
    val haloAlpha by transition.animateFloat(
        initialValue = 0.60f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "halo_alpha",
    )
    val brandAmber = Color(0xFFF7C657)
    val accentBronze = Color(0xFFD4A467)
    val accentMuted = Color(0xFF9F7A3D)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val rOuter = (size.minDimension / 2f) * haloScale
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to brandAmber.copy(alpha = haloAlpha * 0.60f),
                    0.45f to accentBronze.copy(alpha = haloAlpha * 0.32f),
                    0.80f to accentMuted.copy(alpha = haloAlpha * 0.12f),
                    1.00f to Color.Transparent,
                ),
                radius = rOuter,
            ),
            radius = rOuter,
        )
        val rInner = (size.minDimension / 3.0f) * haloScale
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to brandAmber.copy(alpha = haloAlpha * 0.50f),
                    0.55f to brandAmber.copy(alpha = haloAlpha * 0.20f),
                    1.00f to Color.Transparent,
                ),
                radius = rInner,
            ),
            radius = rInner,
        )
    }
}

// §TZ-DESKTOP-UX-2026-04 — три точки переехали в SheetsSplashOverlay как
// `SplashProgressDots()` (single-source: action lock и обычный splash
// делят один и тот же индикатор для unified UX).
