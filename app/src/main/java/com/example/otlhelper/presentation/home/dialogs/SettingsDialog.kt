package com.example.otlhelper.presentation.home.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
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
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.otlhelper.BuildConfig
import com.example.otlhelper.core.security.BiometricLockManager
import com.example.otlhelper.core.settings.AppSettings
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.AccentSubtle
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.BgElevated
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
import com.example.otlhelper.core.theme.ThemeMode
import com.example.otlhelper.core.ui.components.DialogDragHandle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    settings: AppSettings,
    biometricLockManager: BiometricLockManager? = null,
    baseVersion: String = "",
    baseUpdatedAt: String = "",
    baseSyncStatus: com.example.otlhelper.data.sync.BaseSyncStatus = com.example.otlhelper.data.sync.BaseSyncStatus.Idle,
    onBaseSyncNow: (() -> Unit)? = null,
    isDeveloper: Boolean = false,
    onThemeChange: (ThemeMode) -> Unit = {},
    onDismiss: () -> Unit
) {
    var notifications by remember { mutableStateOf(settings.notificationsEnabled) }
    var sound by remember { mutableStateOf(settings.notificationSound) }
    var vibration by remember { mutableStateOf(settings.notificationVibration) }
    var autoRead by remember { mutableStateOf(settings.autoMarkRead) }
    var autoplay by remember { mutableStateOf(settings.autoplayVideos) }
    var haptics by remember { mutableStateOf(settings.hapticsEnabled) }
    var uiSounds by remember { mutableStateOf(settings.uiSoundsEnabled) }
    var biometricEnabled by remember { mutableStateOf(biometricLockManager?.isEnabled() ?: false) }
    val biometricAvailable = biometricLockManager?.canAuthenticate() ?: false
    var retentionDays by remember { mutableStateOf(settings.cacheRetentionDays) }
    var lockResumeSec by remember { mutableStateOf(settings.lockOnResumeSeconds) }
    var themeMode by remember { mutableStateOf(settings.themeMode) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgElevated,
        // Sheet must respect the device's bottom system bar (3-button nav /
        // gesture pill), otherwise the last few rows on long content get
        // clipped on phones where the nav bar overlaps.
        contentWindowInsets = { WindowInsets.systemBars },
        dragHandle = { DialogDragHandle() }
    ) {
        // Long settings list — wrap in a scroller so the bottom rows
        // ("Версия приложения" etc.) are reachable on small screens where
        // the half-expanded sheet doesn't fit them.
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            SettingsSectionHeader("Оформление")
            ThemePicker(
                selected = themeMode,
                onSelect = { mode ->
                    themeMode = mode
                    settings.themeMode = mode
                    onThemeChange(mode)
                }
            )

            Spacer(Modifier.height(8.dp))
            SettingsSectionHeader("Уведомления")
            ToggleRow(
                icon = Icons.Outlined.Notifications,
                title = "Push-уведомления",
                subtitle = "Получать напоминания о новостях и сообщениях",
                checked = notifications,
                onCheckedChange = {
                    notifications = it
                    settings.notificationsEnabled = it
                }
            )
            ToggleRow(
                icon = Icons.AutoMirrored.Outlined.VolumeUp,
                title = "Звук",
                subtitle = "Воспроизводить звук при уведомлении",
                enabled = notifications,
                checked = sound,
                onCheckedChange = {
                    sound = it
                    settings.notificationSound = it
                }
            )
            ToggleRow(
                icon = Icons.Outlined.Vibration,
                title = "Вибрация",
                subtitle = "Вибрация при уведомлении",
                enabled = notifications,
                checked = vibration,
                onCheckedChange = {
                    vibration = it
                    settings.notificationVibration = it
                }
            )

            Spacer(Modifier.height(8.dp))
            SettingsSectionHeader("Поведение")
            // "Mark as read on scroll" — developer-only knob (debug toggle).
            // Regular users always see auto-mark-on-scroll behaviour by default.
            if (isDeveloper) {
                ToggleRow(
                    icon = Icons.Outlined.Visibility,
                    title = "Отмечать как прочитанное",
                    subtitle = "При прокрутке к сообщению",
                    checked = autoRead,
                    onCheckedChange = {
                        autoRead = it
                        settings.autoMarkRead = it
                    }
                )
            }
            ToggleRow(
                icon = Icons.Outlined.PlayCircle,
                title = "Автовоспроизведение видео",
                subtitle = "Видео в ленте играют автоматически",
                checked = autoplay,
                onCheckedChange = {
                    autoplay = it
                    settings.autoplayVideos = it
                }
            )
            ToggleRow(
                icon = Icons.Outlined.Vibration,
                title = "Тактильный отклик",
                subtitle = "Лёгкие вибро-тики при нажатиях и переключениях",
                checked = haptics,
                onCheckedChange = {
                    haptics = it
                    settings.hapticsEnabled = it
                }
            )
            ToggleRow(
                icon = Icons.AutoMirrored.Outlined.VolumeUp,
                title = "Звуки интерфейса",
                subtitle = "Тихие кликлы при действиях (отдельно от push)",
                checked = uiSounds,
                onCheckedChange = {
                    uiSounds = it
                    settings.uiSoundsEnabled = it
                }
            )

            if (biometricLockManager != null) {
                Spacer(Modifier.height(8.dp))
                SettingsSectionHeader("Безопасность")
                ToggleRow(
                    icon = Icons.Outlined.Lock,
                    title = "Биометрический замок",
                    subtitle = if (biometricAvailable)
                        "FaceID / отпечаток при входе"
                    else
                        "Недоступно на этом устройстве",
                    enabled = biometricAvailable,
                    checked = biometricEnabled,
                    onCheckedChange = {
                        try {
                            biometricLockManager.setEnabled(it)
                            biometricEnabled = it
                        } catch (_: Throwable) {
                            biometricEnabled = false
                            try { biometricLockManager.setEnabled(false) } catch (_: Throwable) {}
                        }
                    }
                )
                // §TZ-2.3.37 — повторный запрос биометрии при возврате из фона.
                // Работает только если базовый биометрический замок включён.
                SelectRow(
                    icon = Icons.Outlined.LockClock,
                    title = "Запрашивать при возврате",
                    subtitle = formatLockResume(lockResumeSec),
                    enabled = biometricAvailable && biometricEnabled,
                    onClick = {
                        val options = intArrayOf(0, 1, 60, 300, 1800)
                        val next = options[((options.indexOf(lockResumeSec)
                            .takeIf { it >= 0 } ?: 0) + 1) % options.size]
                        lockResumeSec = next
                        settings.lockOnResumeSeconds = next
                    }
                )
            }

            Spacer(Modifier.height(8.dp))
            SettingsSectionHeader("Данные")
            // §TZ-2.3.37 — retention policy: сколько дней чатов хранить локально.
            // Сервер держит полную историю — старые данные подтянутся при
            // прокрутке. Меньше локального кэша = меньше forensics-surface.
            SelectRow(
                icon = Icons.Outlined.Schedule,
                title = "Хранить историю локально",
                subtitle = formatRetention(retentionDays),
                onClick = {
                    val options = intArrayOf(30, 60, 90, 0)
                    val next = options[((options.indexOf(retentionDays)
                        .takeIf { it >= 0 } ?: 0) + 1) % options.size]
                    retentionDays = next
                    settings.cacheRetentionDays = next
                }
            )

            Spacer(Modifier.height(12.dp))
            SettingsSectionHeader("Информация")
            // Database + кнопка ручной синхронизации + прогресс
            BaseDatabaseRow(
                baseVersion = baseVersion,
                baseUpdatedAt = baseUpdatedAt,
                status = baseSyncStatus,
                onSyncNow = onBaseSyncNow,
            )
            // App version — заменяет отдельный пункт «О приложении» в верхнем меню.
            InfoRow(
                icon = Icons.Outlined.Info,
                title = "Версия приложения",
                subtitle = "OTL Helper · v${BuildConfig.VERSION_NAME}"
            )
        }
    }
}

/**
 * Строка «База данных» с добавкой: прогресс-бар во время загрузки и
 * кнопкой «Обновить сейчас». Если таск уже идёт — кнопка заменена на
 * индикатор.
 */
/**
 * Стабильная (в плане высоты) inline-строка статуса справочника в Settings.
 *
 * §TZ-2.3.4: раньше клик «Обновить» открывал отдельный модальный
 * [com.example.otlhelper.presentation.home.HomeDialogsHost] BaseSyncDialog с
 * «Подождите», который блокировал UI и на WorkManager-backoff'е выглядел как
 * зависание. Теперь всё инлайн: мини-прогресс-бар живёт по нижней кромке
 * строки, статусный текст рядом с названием, кнопка справа меняет смысл
 * («Обновить» / «Идёт…» disabled / «Повторить» красный при Failed).
 * Фиксированная высота 56dp — ModalBottomSheet не скачет при смене статуса.
 */
@Composable
private fun BaseDatabaseRow(
    baseVersion: String,
    baseUpdatedAt: String,
    status: com.example.otlhelper.data.sync.BaseSyncStatus,
    onSyncNow: (() -> Unit)?,
) {
    val isRunning = status is com.example.otlhelper.data.sync.BaseSyncStatus.Running
    val hasLocalBase = baseVersion.isNotBlank()

    // §TZ-2.3.5: кнопка «Обновить» всегда даёт визуальный отклик.
    // Success(freshlyDownloaded=true)  → «База загружена» 3 сек → версия
    // Success(freshlyDownloaded=false) → «База актуальная» 2 сек → версия
    // (раньше up-to-date результат показывался без какой-либо реакции —
    // юзеру казалось что клик «съели»).
    var celebration by remember {
        mutableStateOf<CelebrationKind>(CelebrationKind.None)
    }
    androidx.compose.runtime.LaunchedEffect(status) {
        val success = status as? com.example.otlhelper.data.sync.BaseSyncStatus.Success
        if (success != null) {
            celebration = if (success.freshlyDownloaded)
                CelebrationKind.FreshlyLoaded
            else
                CelebrationKind.UpToDate
            kotlinx.coroutines.delay(
                if (success.freshlyDownloaded) 3_000 else 2_000
            )
            celebration = CelebrationKind.None
        }
    }

    val isQueued = status is com.example.otlhelper.data.sync.BaseSyncStatus.Queued
    val isFailed = status is com.example.otlhelper.data.sync.BaseSyncStatus.Failed
    val runningWithProgress = (status as? com.example.otlhelper.data.sync.BaseSyncStatus.Running)
        ?.takeIf { it.total > 0 }

    val versionLine = buildString {
        if (hasLocalBase) {
            append("v$baseVersion")
            val u = formatUpdatedAt(baseUpdatedAt)
            if (u.isNotBlank()) append(" · $u")
        } else append("не загружена")
    }
    val subtitle: String = when {
        runningWithProgress != null ->
            "Загрузка · ${runningWithProgress.loaded} из ${runningWithProgress.total} · ${runningWithProgress.percent}%"
        isRunning || isQueued -> "Проверяю версию…"
        celebration == CelebrationKind.FreshlyLoaded -> "База загружена"
        celebration == CelebrationKind.UpToDate -> "База актуальная"
        isFailed -> "Не удалось загрузить"
        else -> versionLine
    }

    val subtitleColor = when {
        celebration != CelebrationKind.None -> com.example.otlhelper.core.theme.StatusOkBorder
        isFailed -> com.example.otlhelper.core.theme.StatusErrorBorder
        else -> com.example.otlhelper.core.theme.TextSecondary
    }

    // Фиксированная высота всей строки — ModalBottomSheet не должен подпрыгивать
    // при смене статуса. 56dp хватает на 2 строки текста + стандартные отступы.
    // Прогресс-бар при активной загрузке рисуется абсолютно по нижней кромке
    // строки (Box с contentAlignment=BottomStart) — не меняет высоту.
    androidx.compose.foundation.layout.Box(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                contentDescription = null,
                tint = com.example.otlhelper.core.theme.Accent,
                modifier = androidx.compose.ui.Modifier.size(20.dp),
            )
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(12.dp))
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier.weight(1f)
            ) {
                androidx.compose.material3.Text(
                    "База данных",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                    color = com.example.otlhelper.core.theme.TextPrimary,
                    maxLines = 1,
                )
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(2.dp))
                // Плавный crossfade между статусами: «Проверяю…» → «База
                // загружена» (зелёным) → «v... · дата». AnimatedContent
                // переключается fade in/out ~220ms, попадает в язык motion'а
                // остального приложения (AppMotion.SpringStandard).
                androidx.compose.animation.AnimatedContent(
                    targetState = subtitle,
                    transitionSpec = {
                        androidx.compose.animation.fadeIn(
                            animationSpec = com.example.otlhelper.core.ui.animations.AppMotion.SpringStandard
                        ) togetherWith androidx.compose.animation.fadeOut(
                            animationSpec = com.example.otlhelper.core.ui.animations.AppMotion.SpringStandard
                        )
                    },
                    label = "base_row_subtitle",
                ) { text ->
                    androidx.compose.material3.Text(
                        text,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = subtitleColor,
                        maxLines = 1,
                    )
                }
            }
            // Кнопка справа — смысл меняется по статусу, но позиция стабильна:
            //  • «Повторить» при Failed (красный акцент)
            //  • «Обновить» в любом другом состоянии (Accent)
            //  • При активной загрузке — «Идёт…» disabled, чтобы визуально сигналить
            //    что синхронизация в процессе и дабл-клик не нужен.
            if (onSyncNow != null) {
                val (label, color, enabled) = when {
                    isFailed -> Triple(
                        "Повторить",
                        com.example.otlhelper.core.theme.StatusErrorBorder,
                        true,
                    )
                    isRunning || isQueued -> Triple(
                        "Идёт…",
                        com.example.otlhelper.core.theme.TextTertiary,
                        false,
                    )
                    else -> Triple(
                        "Обновить",
                        com.example.otlhelper.core.theme.Accent,
                        true,
                    )
                }
                androidx.compose.material3.TextButton(
                    onClick = onSyncNow,
                    enabled = enabled,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 6.dp,
                    ),
                ) {
                    androidx.compose.material3.Text(
                        label,
                        color = color,
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        // Индикатор прогресса — тонкая линия внизу строки. Detailed при
        // известных total/loaded; indeterminate при preparing (нет total ещё).
        if (runningWithProgress != null) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { runningWithProgress.loaded.toFloat() / runningWithProgress.total },
                modifier = androidx.compose.ui.Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(androidx.compose.ui.Alignment.BottomStart),
                color = com.example.otlhelper.core.theme.Accent,
                trackColor = com.example.otlhelper.core.theme.BgCard,
            )
        } else if (isRunning || isQueued) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(androidx.compose.ui.Alignment.BottomStart),
                color = com.example.otlhelper.core.theme.Accent,
                trackColor = com.example.otlhelper.core.theme.BgCard,
            )
        }
    }
}

private enum class CelebrationKind { None, FreshlyLoaded, UpToDate }

private fun formatUpdatedAt(raw: String): String {
    if (raw.isBlank()) return ""
    return try {
        val iso = if (raw.contains('T')) {
            if (raw.endsWith("Z") || raw.contains('+') || raw.lastIndexOf('-') > 10) raw
            else "${raw}Z"
        } else {
            "${raw.replace(' ', 'T')}Z"
        }
        val zdt = java.time.ZonedDateTime.parse(iso)
        val yek = zdt.withZoneSameInstant(java.time.ZoneId.of("Asia/Yekaterinburg"))
        yek.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy h:mm a", java.util.Locale.ENGLISH))
    } catch (_: Exception) {
        raw.take(16)
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        color = TextTertiary,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp)
    )
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    // §TZ-2.3.9 — haptic через общий handler: и Row.clickable, и Switch.thumb
    // дают одинаковый tactile feedback. Раньше Switch дергал haptic, а Row
    // (когда юзер тапал по title-области, а не по thumb'у) — нет.
    val feedback = com.example.otlhelper.core.feedback.LocalFeedback.current
    val hostView = androidx.compose.ui.platform.LocalView.current
    val handleToggle: (Boolean) -> Unit = { newValue ->
        feedback?.tap(hostView)
        onCheckedChange(newValue)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { handleToggle(!checked) }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(AccentSubtle, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) TextPrimary else TextTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) TextPrimary else TextTertiary,
                style = MaterialTheme.typography.bodyMedium
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    subtitle,
                    color = if (enabled) TextSecondary else TextTertiary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = handleToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextPrimary,
                checkedTrackColor = Accent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = BgCard,
                uncheckedBorderColor = BorderDivider
            )
        )
    }
}

/**
 * §TZ-2.3.37 — clickable row, циклически переключающая между опциями при тапе.
 * Тот же визуальный язык что у [ToggleRow], но без Switch'а — subtitle
 * показывает текущее значение. Haptic feedback через общий feedback handler,
 * как у остальных кликабельных компонентов в настройках (единый SF-2026 tick).
 */
@Composable
private fun SelectRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val feedback = com.example.otlhelper.core.feedback.LocalFeedback.current
    val hostView = androidx.compose.ui.platform.LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) {
                feedback?.tap(hostView)
                onClick()
            }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(AccentSubtle, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) TextPrimary else TextTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) TextPrimary else TextTertiary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                subtitle,
                color = if (enabled) TextSecondary else TextTertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun formatRetention(days: Int): String = when (days) {
    0 -> "Без ограничения"
    30 -> "30 дней"
    60 -> "60 дней"
    90 -> "90 дней"
    else -> "$days дней"
}

private fun formatLockResume(seconds: Int): String = when (seconds) {
    0 -> "Выключено"
    1 -> "Сразу"
    60 -> "Через 1 минуту"
    300 -> "Через 5 минут"
    1800 -> "Через 30 минут"
    else -> "Через $seconds сек"
}

/**
 * Три сегментных чипа для выбора темы: Стандарт / Тёмная / Светлая.
 * Вид как в топовых приложениях (Telegram, Linear, Notion) —
 * горизонтальный ряд кнопок с иконкой + подписью, активная подсвечена акцентом.
 */
@Composable
private fun ThemePicker(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val feedback = com.example.otlhelper.core.feedback.LocalFeedback.current
    val hostView = androidx.compose.ui.platform.LocalView.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemeChip(
            icon = Icons.Outlined.Palette,
            label = "Стандарт",
            isSelected = selected == ThemeMode.STANDARD,
            modifier = Modifier.weight(1f),
            onClick = {
                feedback?.tap(hostView)
                onSelect(ThemeMode.STANDARD)
            },
        )
        ThemeChip(
            icon = Icons.Outlined.DarkMode,
            label = "Тёмная",
            isSelected = selected == ThemeMode.DARK,
            modifier = Modifier.weight(1f),
            onClick = {
                feedback?.tap(hostView)
                onSelect(ThemeMode.DARK)
            },
        )
        ThemeChip(
            icon = Icons.Outlined.WbSunny,
            label = "Светлая",
            isSelected = selected == ThemeMode.LIGHT,
            modifier = Modifier.weight(1f),
            onClick = {
                feedback?.tap(hostView)
                onSelect(ThemeMode.LIGHT)
            },
        )
    }
}

@Composable
private fun ThemeChip(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg         = if (isSelected) AccentSubtle else BgCard
    val borderColor = if (isSelected) Accent      else BorderDivider
    val tint       = if (isSelected) Accent       else TextSecondary

    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .border(1.dp, borderColor, shape)
            .clip(shape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                color = tint,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(AccentSubtle, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(1.dp))
            Text(subtitle, color = TextTertiary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
