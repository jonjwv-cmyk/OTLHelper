package com.example.otlhelper.presentation.home.components

import androidx.compose.foundation.background
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.BgApp
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.StatusErrorBorder
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.UnreadGreen
import com.example.otlhelper.core.update.AppUpdate
import kotlinx.coroutines.Job

private enum class ApkPhase { IDLE, DOWNLOADING, DONE, ERROR }

/**
 * Hard-block overlay — full-screen takeover shown when the server declares
 * the app unavailable (system pause) or a forced update is required.
 * Inline APK download + install flow (version-tagged via [AppUpdate]).
 *
 * §TZ-2.3.40 — APK качается через OkHttp-клиент с VPS DNS-override (тот
 * же канал, что и остальные запросы). DownloadManager больше не используем.
 */
@Composable
internal fun BlockOverlay(
    title: String,
    message: String,
    updateUrl: String = "",
    updateVersion: String = "",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember(updateVersion) {
        mutableStateOf(
            if (updateUrl.isNotBlank() &&
                AppUpdate.isApkReadyFor(context, updateVersion)
            ) ApkPhase.DONE else ApkPhase.IDLE
        )
    }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            job?.cancel()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(BgApp.copy(alpha = 0.97f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("⚠️", fontSize = 56.sp)
            Spacer(Modifier.height(20.dp))
            Text(
                title.ifBlank { "Приложение недоступно" },
                color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                message, color = TextSecondary, fontSize = 14.sp,
                textAlign = TextAlign.Center, lineHeight = 21.sp
            )

            if (updateUrl.isNotBlank()) {
                Spacer(Modifier.height(32.dp))

                when (phase) {
                    ApkPhase.IDLE -> {
                        Button(
                            onClick = {
                                progress = 0f
                                phase = ApkPhase.DOWNLOADING
                                job = AppUpdate.downloadApk(
                                    context = context,
                                    url = updateUrl,
                                    version = updateVersion,
                                    scope = scope,
                                    onProgress = { progress = it },
                                    onDone = { phase = ApkPhase.DONE },
                                    onError = { phase = ApkPhase.ERROR },
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent, contentColor = BgApp
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Скачать обновление", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }

                    ApkPhase.DOWNLOADING -> {
                        Text(
                            "Скачивание... ${(progress * 100).toInt()}%",
                            color = TextSecondary, fontSize = 14.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Accent,
                            trackColor = BgCard
                        )
                    }

                    ApkPhase.DONE -> {
                        Text(
                            "✅ Файл загружен",
                            color = UnreadGreen, fontSize = 14.sp, fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { AppUpdate.installApk(context) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = UnreadGreen, contentColor = Color(0xFF0A0A0A)
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Установить", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }

                    ApkPhase.ERROR -> {
                        Text(
                            "❌ Ошибка загрузки",
                            color = StatusErrorBorder, fontSize = 14.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { phase = ApkPhase.IDLE },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Повторить", color = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}
