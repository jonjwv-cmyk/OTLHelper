package com.example.otlhelper.desktop.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.BgInput
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.StatusErrorBorder
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary
import com.example.otlhelper.desktop.ui.AppOverlay

/**
 * §TZ-DESKTOP 0.4.x round 11 — password prompt для actions с
 * `requiresPassword`. Простой modal centered в main window:
 *   • Title: «{actionLabel}»
 *   • Description: «Введите пароль для запуска макроса»
 *   • Password field (masked)
 *   • Cancel / Запустить кнопки
 *   • Enter → submit, Esc → cancel
 *
 * Через AppOverlay (heavyweight ComposePanel в layered pane) чтобы быть
 * **внутри** main window — двигается с приложением, не отдельный OS
 * window.
 *
 * §TZ-DESKTOP-0.10.13 — Validation **серверная**. Раньше клиент сравнивал
 * input vs hardcoded expectedPassword. Теперь password введённый юзером
 * передаётся в onSubmit(password) callback → run_script action отправляет
 * на сервер → сервер сравнивает с stored requiresPassword из registry.
 * Сервер вернёт `wrong_password` если не совпало; родитель показывает
 * showError=true через `errorMessage` параметр и переоткрывает prompt.
 */
@Composable
fun SheetsPasswordPrompt(
    actionLabel: String,
    rightInset: Dp = 0.dp,
    errorMessage: String? = null,
    onSubmit: (password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AppOverlay {
        var input by remember { mutableStateOf("") }
        var showError by remember { mutableStateOf(errorMessage != null) }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
        }

        fun trySubmit() {
            if (input.isEmpty()) {
                showError = true
                return
            }
            // Серверная валидация — отправляем введённое значение наверх,
            // там вызывается run_script с password в body. Если сервер
            // вернёт wrong_password — родитель сделает showError=true.
            onSubmit(input)
        }

        DisposableEffect(Unit) {
            SheetsViewBridge.setBlur(true)
            onDispose { SheetsViewBridge.setBlur(false) }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                        onDismiss(); true
                    } else false
                },
        ) {
            // Scrim background — clickable for dismiss.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            // Card — sibling, click handled by children.
            Box(
                modifier = Modifier.fillMaxSize().padding(end = rightInset),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .width(420.dp)
                        .shadow(elevation = 24.dp, shape = RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(BgElevated)
                        .border(0.5.dp, BorderDivider, RoundedCornerShape(16.dp))
                        .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                        .padding(24.dp),
                ) {
                    Text(
                        actionLabel,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Введите пароль для запуска макроса",
                        color = TextTertiary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(20.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BgInput)
                            .border(
                                0.5.dp,
                                if (showError) StatusErrorBorder else BorderDivider,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (input.isEmpty()) {
                            Text(
                                "Пароль",
                                color = TextTertiary,
                                fontSize = 14.sp,
                            )
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = {
                                input = it
                                showError = false
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions.Default,
                            textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                            cursorBrush = SolidColor(Accent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onPreviewKeyEvent { ev ->
                                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter) {
                                        trySubmit(); true
                                    } else false
                                },
                        )
                    }
                    if (showError) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Неверный пароль",
                            color = StatusErrorBorder,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        DialogButton(
                            label = "Отмена",
                            primary = false,
                            onClick = onDismiss,
                        )
                        Spacer(Modifier.width(10.dp))
                        DialogButton(
                            label = "Запустить",
                            primary = true,
                            onClick = ::trySubmit,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (primary) Accent.copy(alpha = 0.18f) else Color.Transparent,
            )
            .border(
                0.5.dp,
                if (primary) Accent.copy(alpha = 0.55f) else BorderDivider,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (primary) Accent else TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
