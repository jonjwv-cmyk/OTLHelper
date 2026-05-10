package com.example.otlhelper.desktop.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.example.otlhelper.desktop.BuildInfo
import com.example.otlhelper.desktop.core.session.SessionLifecycleManager
import com.example.otlhelper.desktop.core.update.VersionInfo
import kotlinx.coroutines.launch
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.AccentSubtle
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary

/**
 * §TZ-DESKTOP-UX-2026-04 — Настройки. Содержит блок «Версии» (Android,
 * БД, Desktop) с данными от сервера и кнопку «Обновить» если доступна
 * новая версия desktop'а.
 *
 * Кнопка «Обновить» открывает confirm-диалог; на «Да» вызывается
 * [onUpdateRequest] — App.kt подхватит и покажет полный SoftUpdateDialog
 * (download + sha + install pipeline) как при обычном soft-update.
 */
@Composable
fun SettingsSheet(
    versionInfo: VersionInfo,
    installedVersion: String = BuildInfo.VERSION,
    onUpdateRequest: () -> Unit,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    sessionLifecycle: SessionLifecycleManager? = null,
) {
    var showConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycleState = sessionLifecycle?.state?.collectAsState()?.value

    BottomSheetShell(
        onDismiss = onDismiss,
        title = "Настройки",
        onBack = onBack ?: onDismiss,
    ) {
        val hasUpdate = versionInfo.desktopCurrent.isNotBlank() &&
            versionInfo.desktopCurrent != installedVersion &&
            versionInfo.desktopUpdateUrl.isNotBlank()

        Spacer(Modifier.height(8.dp))

        // §TZ-0.10.6 — Session info блок (если PC-сессия активна).
        if (lifecycleState != null && lifecycleState.isPc) {
            SectionHeader("Сессия")
            Spacer(Modifier.height(6.dp))
            SessionInfoRow(
                lockKind = lifecycleState.sessionKind,
                yekHm = lifecycleState.yekHm,
                remainingMs = lifecycleState.remainingMs,
                extensionsUsed = lifecycleState.extensionsUsed,
                extensionsRemaining = lifecycleState.extensionsRemaining,
                deviceLabel = lifecycleState.deviceLabel,
                showExtendButton = lifecycleState.shouldShowExtensionPrompt,
                onExtend = { scope.launch { sessionLifecycle.extend() } },
            )
            Spacer(Modifier.height(20.dp))
        }

        SectionHeader("Последние версии")
        Spacer(Modifier.height(6.dp))
        VersionRow("Android", versionInfo.androidCurrent.ifBlank { "—" })
        // База данных + дата/время отдельной подстрочкой.
        VersionRow(
            label = "База данных",
            value = versionInfo.baseVersion.ifBlank { "—" },
            sublabel = formatBaseUpdatedAt(versionInfo.baseUpdatedAt),
        )
        VersionRow("Desktop актуальная", versionInfo.desktopCurrent.ifBlank { "—" })
        VersionRow(
            "Desktop установленная",
            installedVersion,
            isInstalled = true,
        )

        if (hasUpdate) {
            Spacer(Modifier.height(20.dp))
            UpdateBanner(
                serverVersion = versionInfo.desktopCurrent,
                onUpdateRequest = { showConfirm = true },
            )
        }

        // §TZ-DESKTOP-0.10.4 — REMOVED DiagnosticPanel + SecurityCheckButton.
        // Юзер: «убираем дебаг из десктопа, всё перенесем в Android меню
        // только для роли developer/admin». Здесь оставляем только version
        // info (выше) — debug+security audit будут в 0.10.5 в Android.
        Spacer(Modifier.height(8.dp))
    }

    // §TZ-DESKTOP-UX-2026-04 — confirm "Обновить? Да/Нет".
    // DialogWindow — отдельное OS-окно, гарантированно поверх всего
    // (Sheet + heavyweight Chromium). На Yes → onUpdateRequest()
    // (App.kt откроет полный SoftUpdateDialog с download/install).
    if (showConfirm) {
        UpdateConfirmDialog(
            onYes = {
                showConfirm = false
                onUpdateRequest()
                onDismiss()
            },
            onNo = { showConfirm = false },
        )
    }
}

@Composable
private fun UpdateConfirmDialog(onYes: () -> Unit, onNo: () -> Unit) {
    // §0.10.26 — увеличен размер 220→320. Native title bar Win + 24dp
    // padding + text 3 lines + spacers + buttons 44dp не помещались
    // в 220dp → юзер видел кнопки "Нет"/"Да" обрезанными снизу.
    // §0.11.4 — 340→260. Юзер: «много пустоты под кнопками». Точный
    // расчёт: padding top 24 + title 28 + sp 8 + text 4 lines 76 +
    // sp 20 + buttons 44 + padding bottom 24 + title bar 32 ≈ 256.
    val state = rememberDialogState(size = DpSize(440.dp, 260.dp))
    DialogWindow(
        onCloseRequest = onNo,
        state = state,
        title = "Обновление",
        resizable = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(com.example.otlhelper.desktop.theme.BgElevated)
                .padding(24.dp),
        ) {
            Text(
                "Обновить приложение?",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Скачаем установщик и установим автоматически. Сессия " +
                    "и настройки сохранятся, после обновления приложение " +
                    "снова войдёт под вами.",
                color = TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onNo,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Нет", color = TextPrimary)
                }
                Button(
                    onClick = onYes,
                    modifier = Modifier.weight(1f).height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = Color.Black,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Да", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// §TZ-DESKTOP-0.10.4 — REMOVED: SecurityCheckButton + DiagnosticPanel.
// Юзер: «убираем дебаг из десктопа, всё в Android меню для developer/admin».
// SecurityAuditDialog.kt тоже удаляем как неиспользуемый.
// Возможно вернём в Android в 0.10.5 как часть нового developer screen.

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = TextTertiary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun VersionRow(
    label: String,
    value: String,
    isInstalled: Boolean = false,
    sublabel: String = "",
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            if (sublabel.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    sublabel,
                    color = TextTertiary,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = if (isInstalled) TextPrimary else Accent,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(BorderDivider.copy(alpha = 0.4f)),
    )
}

/**
 * §TZ-0.10.6 — Session info row (countdown + extend button).
 * Показывается только когда session_kind = pc_qr / pc_password.
 */
@Composable
private fun SessionInfoRow(
    lockKind: String,
    yekHm: String,
    remainingMs: Long,
    extensionsUsed: Int,
    extensionsRemaining: Int,
    deviceLabel: String,
    showExtendButton: Boolean,
    onExtend: () -> Unit,
) {
    val kindLabel = when (lockKind) {
        "pc_qr" -> "PC (QR)"
        "pc_password" -> "PC (пароль)"
        else -> lockKind
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AccentSubtle)
            .border(0.5.dp, Accent.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔓", fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Сессия активна ${SessionLifecycleManager.formatExpiryYek(yekHm)}",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${SessionLifecycleManager.formatRemaining(remainingMs)} · $kindLabel · продлений: $extensionsUsed/3",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                if (deviceLabel.isNotBlank()) {
                    Text(deviceLabel, color = TextTertiary, fontSize = 11.sp)
                }
            }
        }
        if (showExtendButton && extensionsRemaining > 0) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onExtend,
                modifier = Modifier.fillMaxWidth().height(36.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color.Black,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Продлить +30 мин (осталось $extensionsRemaining)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * §TZ-DESKTOP-UX-2026-04 — server `base_updated_at` приходит как
 * `2026-04-26 14:30:00` UTC. Конвертируем в Asia/Yekaterinburg и
 * форматируем 12-часовым с AM/PM: «26.04.2026 7:30 PM».
 *
 * Locale.ENGLISH — чтобы AM/PM были латинскими (а не «дп/пп» в ru).
 * `h:mm` (без leading zero у часа) — компактнее на тёмной теме.
 */
private fun formatBaseUpdatedAt(raw: String): String {
    if (raw.isBlank()) return ""
    return runCatching {
        val zoneYek = java.time.ZoneId.of("Asia/Yekaterinburg")
        val parsed = if (raw.contains('T')) {
            val iso = if (raw.endsWith("Z") || raw.contains('+')) raw else raw + "Z"
            java.time.ZonedDateTime.parse(iso)
        } else {
            // SQLite-style "yyyy-MM-dd HH:mm:ss" UTC
            val ldt = java.time.LocalDateTime.parse(raw.replace(' ', 'T'))
            ldt.atZone(java.time.ZoneOffset.UTC)
        }
        val local = parsed.withZoneSameInstant(zoneYek)
        val fmt = java.time.format.DateTimeFormatter
            .ofPattern("dd.MM.yyyy h:mm a", java.util.Locale.ENGLISH)
        local.format(fmt)
    }.getOrDefault(raw)
}

@Composable
private fun UpdateBanner(serverVersion: String, onUpdateRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AccentSubtle)
            .border(0.5.dp, Accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .width(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.SystemUpdate,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.padding(8.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Доступно обновление",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Версия $serverVersion",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onUpdateRequest,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
                contentColor = Color.Black,
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Обновить", fontWeight = FontWeight.SemiBold)
        }
    }
}
