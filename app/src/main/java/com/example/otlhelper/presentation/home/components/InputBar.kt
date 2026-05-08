package com.example.otlhelper.presentation.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.AccentMuted
import com.example.otlhelper.core.theme.BgApp
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.BgInput
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
import com.example.otlhelper.core.ui.AttachmentThumbnailRow
import com.example.otlhelper.core.ui.components.ThinDivider
import com.example.otlhelper.core.ui.components.rememberAppPressFeel
import com.example.otlhelper.presentation.home.AttachmentItem

/**
 * Composer input bar (SF-2026 pattern):
 *  - Left:  single «+» (action menu) — no round fill, just the icon.
 *  - Centre: OutlinedTextField; Schedule + Attach live inside as trailing
 *           icons so the right-hand column stays reserved for Send only.
 *  - Right: Send — accent-fill when there's something to send, neutral
 *           when empty.
 *
 * Keep at this level (not deeper) — the whole bar is ~140 lines and owns
 * one concern. Breaking further would scatter the composer language.
 */
@Composable
internal fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    hint: String,
    showSend: Boolean,
    showPlus: Boolean,
    showAttach: Boolean,
    attachments: List<AttachmentItem>,
    onSend: () -> Unit,
    onPlusClick: () -> Unit,
    onAttachClick: () -> Unit,
    onRemoveAttachment: (AttachmentItem) -> Unit,
    /** «⏰ Отложить» — only admin + NEWS. Null = hidden. */
    onScheduleClick: (() -> Unit)? = null,
) {
    Column {
        AttachmentThumbnailRow(attachments = attachments, onRemove = onRemoveAttachment)

        ThinDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgCard)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            if (showPlus) {
                val (plusSrc, plusMod) = rememberAppPressFeel()
                IconButton(
                    onClick = onPlusClick,
                    interactionSource = plusSrc,
                    modifier = Modifier.size(40.dp).then(plusMod)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Действия",
                        tint = TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = {
                    Text(
                        hint,
                        color = TextTertiary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingIcon = if (!showAttach && onScheduleClick == null) null else {
                    {
                        // §TZ-2.3.7 — тактика на скрепку и часы-schedule.
                        val trayFeedback = com.example.otlhelper.core.feedback.LocalFeedback.current
                        val trayHost = androidx.compose.ui.platform.LocalView.current
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (onScheduleClick != null) {
                                IconButton(
                                    onClick = {
                                        trayFeedback?.tap(trayHost)
                                        onScheduleClick()
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Schedule,
                                        contentDescription = "Отложить",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            if (showAttach) {
                                IconButton(
                                    onClick = {
                                        trayFeedback?.tap(trayHost)
                                        onAttachClick()
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.AttachFile,
                                        contentDescription = "Прикрепить",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                singleLine = false,
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentMuted.copy(alpha = 0.4f),
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = Accent,
                    focusedContainerColor = BgInput,
                    unfocusedContainerColor = BgInput
                )
            )

            if (showSend) {
                Spacer(Modifier.width(6.dp))
                val (sendSrc, sendMod) = rememberAppPressFeel()
                val active = text.isNotBlank() || attachments.isNotEmpty()
                // §TZ-2.3.7 — confirm-хаптика на Send. Send = коммит действия,
                // должен чувствоваться чётче чем обычный tab-tick.
                val sendFeedback = com.example.otlhelper.core.feedback.LocalFeedback.current
                val sendHostView = androidx.compose.ui.platform.LocalView.current
                IconButton(
                    onClick = {
                        // §TZ-2.3.19 — отправка chat / news: единственное
                        // место в UI где звук играется вместе с haptic.
                        if (active) sendFeedback?.messageSent(sendHostView)
                        onSend()
                    },
                    interactionSource = sendSrc,
                    modifier = Modifier
                        .size(40.dp)
                        .then(sendMod)
                        .clip(CircleShape)
                        .background(if (active) Accent else BgCard)
                ) {
                    Icon(
                        Icons.Rounded.ArrowUpward,
                        contentDescription = "Отправить",
                        tint = if (active) BgApp else TextTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
