package com.example.otlhelper.presentation.home.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.BgElevated
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.theme.StatusErrorBorder
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
import com.example.otlhelper.core.theme.UnreadGreen
import com.example.otlhelper.core.update.AppUpdate
import com.example.otlhelper.core.update.UpdateDownloadService
import android.content.BroadcastReceiver
import android.content.Context as AndroidContext
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job

private enum class Phase { IDLE, DOWNLOADING, DONE, ERROR }

/**
 * Non-blocking in-app update dialog (soft update flow).
 *
 * §TZ-2.3.40 — Качаем APK через наш OkHttp downloadClient (VPS-override),
 * а не через системный DownloadManager. Прогресс — из колбэка.
 */
@Composable
fun SoftUpdateDialog(
    version: String,
    url: String,
    onDismiss: () -> Unit,
) {
    if (url.isBlank()) { onDismiss(); return }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember {
        mutableStateOf(
            if (AppUpdate.isApkReadyFor(context, version)) Phase.DONE else Phase.IDLE
        )
    }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    var errorMsg by remember { mutableStateOf("") }

    // §TZ-2.5.4 — слушаем progress/done/error broadcast от Foreground Service.
    // Свернул app — service продолжает; вернулся — увидит актуальный progress.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: AndroidContext?, intent: Intent?) {
                when (intent?.action) {
                    UpdateDownloadService.BROADCAST_PROGRESS -> {
                        val pct = intent.getIntExtra(UpdateDownloadService.EXTRA_PROGRESS_PCT, 0)
                        progress = (pct / 100f).coerceIn(0f, 1f)
                        if (phase == Phase.IDLE) phase = Phase.DOWNLOADING
                    }
                    UpdateDownloadService.BROADCAST_DONE -> {
                        progress = 1f
                        phase = Phase.DONE
                    }
                    UpdateDownloadService.BROADCAST_ERROR -> {
                        errorMsg = intent.getStringExtra(UpdateDownloadService.EXTRA_ERROR_MSG).orEmpty()
                        phase = Phase.ERROR
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UpdateDownloadService.BROADCAST_PROGRESS)
            addAction(UpdateDownloadService.BROADCAST_DONE)
            addAction(UpdateDownloadService.BROADCAST_ERROR)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            job?.cancel()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = phase != Phase.DOWNLOADING,
            usePlatformDefaultWidth = false,
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(BgElevated)
                .border(0.5.dp, BorderDivider, RoundedCornerShape(18.dp))
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
                        "Доступно обновление",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (version.isNotBlank()) {
                        Text(
                            "Версия $version",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            when (phase) {
                Phase.IDLE -> {
                    Text(
                        "Приложение скачает новый APK и предложит его установить.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = {
                            progress = 0f
                            phase = Phase.DOWNLOADING
                            // §TZ-2.5.4 — стартуем Foreground Service, юзер
                            // может свернуть app — скачивание продолжится.
                            UpdateDownloadService.start(
                                context = context,
                                url = url,
                                version = version,
                                expectedSha = AppUpdate.storedSha256(context),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = androidx.compose.ui.graphics.Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            "Скачать обновление",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Позже", color = TextTertiary, fontSize = 13.sp)
                    }
                }

                Phase.DOWNLOADING -> {
                    Text(
                        "Скачивание… ${(progress * 100).toInt()}%",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Accent,
                        trackColor = BgCard
                    )
                }

                Phase.DONE -> {
                    Text(
                        "Файл загружен. Установите, когда будет удобно — приложение закроется на время установки.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = { AppUpdate.installApk(context) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = UnreadGreen,
                            contentColor = androidx.compose.ui.graphics.Color(0xFF0A0A0A)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Установить", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Закрыть", color = TextTertiary, fontSize = 13.sp)
                    }
                }

                Phase.ERROR -> {
                    Text(
                        "Ошибка загрузки. Проверьте соединение и попробуйте ещё раз.",
                        color = StatusErrorBorder,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(18.dp))
                    OutlinedButton(
                        onClick = { phase = Phase.IDLE },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Повторить", color = TextPrimary)
                    }
                }
            }
        }
    }
}
