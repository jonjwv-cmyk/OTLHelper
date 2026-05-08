package com.example.otlhelper.presentation.home.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.otlhelper.presentation.home.HomeDialogsState
import com.example.otlhelper.presentation.home.HomeTab
import com.example.otlhelper.presentation.home.HomeUiState
import com.example.otlhelper.presentation.home.HomeViewModel
import com.example.otlhelper.presentation.home.components.InputBar
import com.example.otlhelper.presentation.home.components.ReplyPreviewBar
import org.json.JSONObject

/**
 * §TZ-CLEANUP-2026-04-26 — extracted from HomeScreen.kt.
 *
 * Composer-bar (InputBar) + reply-preview, со всеми связанными
 * derivedStateOf и onSend wiring (newsroom / messenger / search).
 *
 * Конфигурация мемоизируется в [InputBarConfig] — флаги пересчитываются
 * только при смене activeTab/role/overlay-state, а не на каждый keystroke
 * (иначе FAB-кнопка мерцала бы при печати).
 */
@Immutable
private data class InputBarConfig(
    val visible: Boolean,
    val showSend: Boolean,
    val showAttach: Boolean,
    val showSchedule: Boolean,
    val hint: String,
)

@Composable
internal fun HomeInputBarWiring(
    state: HomeUiState,
    viewModel: HomeViewModel,
    dialogs: HomeDialogsState,
    inputTextState: MutableState<String>,
    replyTargetState: MutableState<JSONObject?>,
) {
    val context = LocalContext.current
    var inputText by inputTextState
    var replyTarget by replyTargetState

    // InputBar visibility + все connected derived-флаги — через ОДИН
    // derivedStateOf. Иначе при каждом keystroke в composer'е (что триггерит
    // рекомпозицию HomeScreen через inputText state) пересчитывалось бы
    // 4-5 условий, FAB (жёлтая кнопка) мерцала бы на каждый символ.
    // Теперь recomposition реагирует только на тех изменениях которые
    // реально меняют видимость компонентов.
    val isAdmin = viewModel.hasAdminAccess()
    val inputBarConfig by remember(
        state.activeTab,
        isAdmin,
        state.accountScreenOpen,
        state.adminConversationOpen,
    ) {
        derivedStateOf {
            val tab = state.activeTab
            val show = !state.accountScreenOpen &&
                tab != HomeTab.SEARCH &&
                !(tab == HomeTab.MONITORING && isAdmin && !state.adminConversationOpen) &&
                !(tab == HomeTab.NEWS && !isAdmin)
            InputBarConfig(
                visible = show,
                showSend = when {
                    tab == HomeTab.SEARCH -> false
                    tab == HomeTab.NEWS -> isAdmin
                    else -> true
                },
                showAttach = when {
                    tab == HomeTab.SEARCH -> false
                    tab == HomeTab.NEWS -> isAdmin
                    else -> true
                },
                showSchedule = tab == HomeTab.NEWS && isAdmin,
                hint = when {
                    tab == HomeTab.SEARCH -> "поиск..."
                    tab == HomeTab.NEWS && isAdmin -> "Написать новость..."
                    tab == HomeTab.NEWS -> "Только администраторы могут публиковать"
                    state.adminConversationOpen -> "Ответить..."
                    else -> "Написать..."
                },
            )
        }
    }
    if (!inputBarConfig.visible) return

    // Reply preview — Telegram-style bar above the composer
    replyTarget?.let { rt ->
        ReplyPreviewBar(
            senderName = rt.optString("sender_name", rt.optString("sender_login", "")),
            text = rt.optString("text", ""),
            onCancel = { replyTarget = null }
        )
    }
    InputBar(
        text = inputText,
        onTextChange = { text ->
            inputText = text
            when (state.activeTab) {
                HomeTab.SEARCH -> viewModel.onSearchQueryChanged(text)
                else -> {
                    viewModel.saveDraft(state.activeTab, text)
                    // Phase 8: typing indicator. Для user — всем админам
                    // (peer='admins'), для admin в чате — конкретному юзеру.
                    if (state.activeTab == HomeTab.MONITORING && text.isNotBlank()) {
                        val peer = if (viewModel.hasAdminAccess())
                            state.selectedAdminUserLogin.orEmpty()
                        else
                            "admins"
                        if (peer.isNotBlank()) viewModel.sendTypingStart(peer)
                    }
                }
            }
        },
        hint = inputBarConfig.hint,
        showSend = inputBarConfig.showSend,
        // Plus menu перенесён в bottom tab bar — всегда false.
        showPlus = false,
        showAttach = inputBarConfig.showAttach,
        attachments = state.pendingAttachments,
        onSend = {
            val text = inputText.trim()
            val attachments = state.pendingAttachments
            if (text.isBlank() && attachments.isEmpty()) return@InputBar
            inputText = ""
            viewModel.clearAttachments()
            viewModel.onSearchQueryChanged("")
            viewModel.saveDraft(state.activeTab, "")
            val attachmentsJson = com.example.otlhelper.core.ui.buildAttachmentsJson(context, attachments)
            when (state.activeTab) {
                HomeTab.NEWS -> viewModel.sendNews(text, attachmentsJson) { ok, msg ->
                    if (!ok) viewModel.setStatus(msg)
                }
                HomeTab.MONITORING -> {
                    val rId = replyTarget?.optLong("id", 0L) ?: 0L
                    replyTarget = null
                    viewModel.sendMessage(
                        text,
                        if (state.adminConversationOpen) state.selectedAdminUserLogin else null,
                        attachmentsJson,
                        rId
                    ) { ok, msg -> if (!ok) viewModel.setStatus(msg) }
                }
                HomeTab.SEARCH -> {}
            }
        },
        onPlusClick = { dialogs.showActionMenu = true },
        onAttachClick = { dialogs.showAttachPicker = true },
        onRemoveAttachment = { viewModel.removeAttachment(it) },
        onScheduleClick = if (inputBarConfig.showSchedule) {
            { dialogs.showSchedulePick = true }
        } else null,
    )
}
