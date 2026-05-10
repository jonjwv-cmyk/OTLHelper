package com.example.otlhelper.desktop.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.example.otlhelper.desktop.BuildInfo
import com.example.otlhelper.desktop.core.update.AppUpdate
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.StatusErrorBorder
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary
import com.example.otlhelper.desktop.theme.UnreadGreen
import kotlinx.coroutines.Job

private enum class Phase { IDLE, DOWNLOADING, DONE, ERROR }

/**
 * §TZ-DESKTOP-DIST — desktop-аналог Android SoftUpdateDialog.
 *
 * Использует [DialogWindow] вместо обычного [androidx.compose.ui.window.Dialog]
 * — обёртка как в существующем правиле «heavyweight overlay для Chromium-зон»
 * (см. memory feedback_compose_heavyweight_overlays.md). Здесь это не строго
 * обязательно (диалог не накладывается на JCEF-канвас), но единообразие
 * проекта + лучше работает с фокусом окна.
 *
 * [force] = true → диалог не закрывается, юзер обязан обновиться (передаётся
 * когда server вернул `version_ok: false` либо `app_state != normal`).
 */
@Composable
fun SoftUpdateDialog(
    version: String,
    url: String,
    force: Boolean,
    onDismiss: () -> Unit,
) {
    if (url.isBlank()) {
        // Нечего качать — закрываемся (либо никогда не открывались, если force без url).
        if (!force) onDismiss()
        return
    }

    val scope = rememberCoroutineScope()
    var phase by remember {
        mutableStateOf(if (AppUpdate.isReadyFor(version)) Phase.DONE else Phase.IDLE)
    }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose { job?.cancel() }
    }

    // §0.10.26 — высота 280→360. На Win native title bar + текст
    // (3 строки описания) + progress bar + button не помещались.
    // §0.11.4 — 360→290. Юзер: «огромное пустое пространство под кнопками».
    // Точный размер по контенту: header ~50 + sp 18 + text 3-4 lines ~76 +
    // sp 18 + button 50 + sp 4 + textbutton 36 + padding 48 + title 32 ≈ 332.
    // На IDLE (text + 1 кнопка + textbutton) — ~290 хватает с запасом 10dp.
    val dialogState = rememberDialogState(size = DpSize(460.dp, 290.dp))

    DialogWindow(
        onCloseRequest = {
            // При force-update запретить закрытие крестиком.
            if (!force || phase == Phase.DONE) onDismiss()
        },
        state = dialogState,
        title = "Обновление",
        resizable = false,
    ) {
        Column(
            modifier = Modifier
                // §TZ-DESKTOP-NATIVE-2026-05 0.8.13 — fillMaxSize вместо fillMaxWidth.
                // Без fillMaxHeight Column занимал только высоту контента, а
                // DialogWindow имеет фиксированную высоту 280dp → остаток
                // покрывался белым default фоном (юзер видел "белую полосу снизу").
                .fillMaxSize()
                .background(BgElevated)
                .border(0.5.dp, BorderDivider, RoundedCornerShape(0.dp))
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.SystemUpdate,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (force) "Требуется обновление" else "Доступно обновление",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (version.isNotBlank()) {
                        Text(
                            "Версия $version (текущая ${BuildInfo.VERSION})",
                            color = TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            when (phase) {
                Phase.IDLE -> {
                    Text(
                        if (BuildInfo.IS_MAC)
                            "Скачаем и установим автоматически. Сессия и настройки сохранятся, после обновления приложение перезапустится и снова войдёт под вами."
                        else if (BuildInfo.IS_WINDOWS)
                            "Скачаем и установим автоматически (без прав администратора). Сессия и настройки сохранятся, после обновления приложение перезапустится."
                        else
                            "Скачаем установщик и запустим его.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = {
                            progress = 0f
                            phase = Phase.DOWNLOADING
                            job = AppUpdate.downloadInstaller(
                                url = url,
                                version = version,
                                scope = scope,
                                onProgress = { progress = it },
                                onDone = { phase = Phase.DONE },
                                onError = { phase = Phase.ERROR },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Color.Black,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Скачать обновление", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                    if (!force) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Позже", color = TextTertiary, fontSize = 13.sp)
                        }
                    }
                }

                Phase.DOWNLOADING -> {
                    Text(
                        "Скачивание… ${(progress * 100).toInt()}%",
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Accent,
                        trackColor = BgCard,
                    )
                }

                Phase.DONE -> {
                    Text(
                        "Файл загружен. По кнопке «Установить» приложение закроется, обновление установится автоматически и приложение запустится снова.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = { AppUpdate.runInstaller(version) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = UnreadGreen,
                            contentColor = Color(0xFF0A0A0A),
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Установить", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                    if (!force) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Закрыть", color = TextTertiary, fontSize = 13.sp)
                        }
                    }
                }

                Phase.ERROR -> {
                    Text(
                        "Ошибка загрузки. Проверьте соединение и попробуйте ещё раз.",
                        color = StatusErrorBorder,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    OutlinedButton(
                        onClick = { phase = Phase.IDLE },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Повторить", color = TextPrimary)
                    }
                }
            }
        }
    }
}
