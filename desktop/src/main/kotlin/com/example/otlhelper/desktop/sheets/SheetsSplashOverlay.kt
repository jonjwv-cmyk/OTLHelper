package com.example.otlhelper.desktop.sheets

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.BgApp
import com.example.otlhelper.desktop.theme.StatusErrorBorder
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import io.github.alexzhirkevich.compottie.Compottie
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.animateLottieCompositionAsState
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter

@Composable
fun SheetsSplashOverlay(
    state: SheetsRuntime.State,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    // §TZ-DESKTOP-NATIVE-2026-05 — Win показывает "Запускаем" подпись над
    // точками (default = true на Win, false на Mac per TZ-2.4.5). Юзер
    // запросил для Win при rendering Sheets-зоны и file switch'ах.
    showTitle: Boolean = isWindowsPlatform(),
) {
    val (title, subtitle, progress, error) = describe(state)
    val showRestart = state is SheetsRuntime.State.Error
    Box(
        modifier = modifier.fillMaxSize().background(BgApp).alpha(alpha),
        contentAlignment = Alignment.Center,
    ) {
        SheetsSplashCard(
            title = title,
            subtitle = subtitle,
            progress = progress,
            error = error,
            showTitle = showTitle,
            onRestart = if (showRestart) ({ ::handleRestart.invoke() }) else null,
        )
    }
}

private fun isWindowsPlatform(): Boolean =
    System.getProperty("os.name")?.lowercase()?.contains("win") == true

/**
 * §TZ-DESKTOP-DIST 0.5.1 — обработчик кнопки «Перезапустить» в Error state.
 * Сначала через [AppUpdate.restartApp] запускает новый instance, потом
 * fallback просто exit (юзер откроет вручную).
 */
private fun handleRestart() {
    runCatching { SheetsRuntime.dispose() }
    val ok = runCatching {
        com.example.otlhelper.desktop.core.update.AppUpdate.restartApp()
    }.getOrDefault(false)
    if (!ok) {
        kotlin.system.exitProcess(0)
    }
}

@Composable
fun SheetsLoadingOverlay(alpha: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(BgApp).alpha(alpha),
        contentAlignment = Alignment.Center,
    ) {
        SheetsSplashCard(
            // §TZ-DESKTOP-UX-2026-04 — без "..." в тексте: пульсирующие
            // точки ниже сами визуально передают «in progress».
            title = "Запускаем",
            subtitle = "",
            progress = null,
            error = false,
        )
    }
}

@Composable
private fun SheetsSplashCard(
    title: String,
    subtitle: String,
    progress: Float?,
    error: Boolean,
    showTitle: Boolean = false,
    onRestart: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.width(420.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(modifier = Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            CatHalo()
            LottieCatAnimation(modifier = Modifier.size(180.dp))
        }
        // §TZ-2.4.5 — title/subtitle убраны по запросу юзера. Только cat +
        // halo + анимированные точки (для error-state — кнопка "Перезапустить").
        // §TZ-DESKTOP-NATIVE-2026-05 — Win UX возвращает "Запускаем" опц.
        if (error) {
            Spacer(Modifier.height(20.dp))
            Text(
                title,
                color = StatusErrorBorder,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        } else {
            if (showTitle && title.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    title,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
            } else {
                Spacer(Modifier.height(14.dp))
            }
            SplashProgressDots()
        }
        // §TZ-DESKTOP-DIST 0.5.1 — кнопка "Перезапустить" в Error state. Часто
        // KCEF после первой настройки требует JVM-restart (onRestartRequired) —
        // юзер не должен сам гадать что делать, даём явный action.
        if (error && onRestart != null) {
            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.Button(
                onClick = onRestart,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color.White,
                ),
            ) {
                Text("Перезапустить приложение", fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * §TZ-DESKTOP-UX-2026-04 — три пульсирующие точки (stagger ритм). Тот же
 * паттерн что у [SheetsActionLock] и у Login button progress в release-flow.
 * Single-source: чтобы action lock и splash выглядели одинаково.
 */
@Composable
internal fun SplashProgressDots() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "splashDots")
    val dotColor = Accent
    val dot1 by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(900),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "splashDot1",
    )
    val dot2 by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(900, delayMillis = 150),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "splashDot2",
    )
    val dot3 by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(900, delayMillis = 300),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "splashDot3",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(dot1, dot2, dot3).forEach { alpha ->
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(color = dotColor.copy(alpha = alpha), shape = CircleShape),
            )
        }
    }
}

@Composable
internal fun CatHalo() {
    val transition = rememberInfiniteTransition(label = "cat_halo")
    val haloScale by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "halo_scale",
    )
    val haloAlpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "halo_alpha",
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val rOuter = (size.minDimension / 2f) * haloScale
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to Color(0xFFF7C657).copy(alpha = haloAlpha * 0.55f),
                    0.45f to Color(0xFFD4A467).copy(alpha = haloAlpha * 0.30f),
                    0.80f to Color(0xFF9F7A3D).copy(alpha = haloAlpha * 0.10f),
                    1.00f to Color.Transparent,
                ),
                radius = rOuter,
            ),
            radius = rOuter,
        )

        val rInner = (size.minDimension / 3.2f) * haloScale
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to Color(0xFFF7C657).copy(alpha = haloAlpha * 0.45f),
                    0.55f to Color(0xFFF7C657).copy(alpha = haloAlpha * 0.18f),
                    1.00f to Color.Transparent,
                ),
                radius = rInner,
            ),
            radius = rInner,
        )
    }
}

@Composable
internal fun LottieCatAnimation(modifier: Modifier = Modifier) {
    val json = catJson
    if (json == null) {
        Text("🐱", fontSize = 82.sp, modifier = modifier)
        return
    }

    val result = rememberLottieComposition { LottieCompositionSpec.JsonString(json) }
    if (result.isFailure || (result.isComplete && !result.isSuccess)) {
        Text("🐱", fontSize = 82.sp, modifier = modifier)
        return
    }
    // §TZ-DESKTOP-NATIVE-2026-05 0.8.35 — REVERT 0.8.33 emoji fallback.
    // Юзер: «при холодном запуске какойто еше кот смайлик добавился который
    // быстро исчезает». Emoji мелькает ~100-300ms между halo и Lottie cat —
    // выглядит как баг. Возвращаем оригинальное поведение (Mac имеет такое же):
    // пока Lottie composition парсится — показываем только halo (≤300ms).
    if (!result.isComplete) {
        return
    }

    val composition = result.value
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = Compottie.IterateForever,
    )
    val painter: Painter = rememberLottiePainter(
        composition = composition,
        progress = { progress },
    )

    Canvas(modifier = modifier) {
        with(painter) {
            draw(size = size)
        }
    }
}

private val catJson: String? by lazy {
    object {}.javaClass.classLoader
        ?.getResourceAsStream("cat.json")
        ?.use { it.readBytes().decodeToString() }
}

private data class SplashTexts(
    val title: String,
    val subtitle: String,
    val progress: Float?,
    val error: Boolean,
)

private fun describe(state: SheetsRuntime.State): SplashTexts = when (state) {
    SheetsRuntime.State.Idle,
    SheetsRuntime.State.Ready -> SplashTexts(
        // §TZ-DESKTOP-UX-2026-04 — без "..." в тексте; пульсирующие точки
        // ниже визуально показывают что работа идёт.
        title = "Запускаем",
        subtitle = "",
        progress = null,
        error = false,
    )
    is SheetsRuntime.State.Locating -> SplashTexts(
        title = "Запускаем",
        subtitle = state.message,
        progress = null,
        error = false,
    )
    // §TZ-DESKTOP-DIST 0.5.2 — первый запуск Sheets качает Chromium-bundle
    // ~150 MB через VPS. Юзер видит процент чтобы понимать что не висим.
    is SheetsRuntime.State.Downloading -> SplashTexts(
        title = "Загружаем библиотеки",
        subtitle = "${state.percent}%",
        progress = state.percent / 100f,
        error = false,
    )
    is SheetsRuntime.State.Extracting -> SplashTexts(
        title = "Запускаем",
        subtitle = state.message,
        progress = null,
        error = false,
    )
    is SheetsRuntime.State.Initializing -> SplashTexts(
        title = "Запускаем",
        subtitle = state.message,
        progress = null,
        error = false,
    )
    is SheetsRuntime.State.Error -> SplashTexts(
        title = "Не удалось запустить таблицы",
        subtitle = state.message,
        progress = null,
        error = true,
    )
}
