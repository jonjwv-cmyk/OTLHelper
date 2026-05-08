package com.example.otlhelper.presentation.home

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.StatusErrorBorder
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.ui.AttachmentPickerSheet
import com.example.otlhelper.data.sync.BaseSyncStatus
import com.example.otlhelper.presentation.home.components.AccountScreen
import com.example.otlhelper.presentation.home.components.ChangePasswordSheet
import com.example.otlhelper.presentation.home.dialogs.ActionMenuDialog
import com.example.otlhelper.presentation.home.dialogs.AppStatsDialog
import com.example.otlhelper.presentation.home.dialogs.ChatMessageActionPopup
import com.example.otlhelper.presentation.home.dialogs.ConfirmVoteDialog
import com.example.otlhelper.presentation.home.dialogs.EditDeleteSheet
import com.example.otlhelper.presentation.home.dialogs.EditMessageDialog
import com.example.otlhelper.presentation.home.dialogs.NewsReadersDialog
import com.example.otlhelper.presentation.home.dialogs.PollBuilderDialog
import com.example.otlhelper.presentation.home.dialogs.PollStatsDialog
import com.example.otlhelper.presentation.home.dialogs.ReactionVotersDialog
import com.example.otlhelper.presentation.home.dialogs.ScheduledListDialog
import com.example.otlhelper.presentation.home.dialogs.ScheduledPickDialog
import com.example.otlhelper.presentation.home.dialogs.SettingsDialog
import com.example.otlhelper.presentation.home.dialogs.SoftUpdateDialog
import com.example.otlhelper.presentation.home.dialogs.SystemControlDialog
import com.example.otlhelper.presentation.home.dialogs.audit_log.AuditLogDialog
import com.example.otlhelper.presentation.home.dialogs.user_management.UserManagementDialog
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Hoisted state object for all HomeScreen dialogs.
 *
 * Hoisting the 17+ show-flags and target payloads into a single @Stable
 * class keeps HomeScreen itself focused on layout composition and allows
 * the dialog rendering to live in a separate composable
 * ([HomeDialogsHost]) — otherwise HomeScreen ballooned to ~1000 lines
 * just wiring dialogs.
 *
 * Every field is an observable Compose state — flipping one triggers only
 * the owning dialog's recomposition, not the whole host tree.
 */
@Stable
class HomeDialogsState {
    var showActionMenu by mutableStateOf(false)
    var showPollBuilder by mutableStateOf(false)
    var showSystemControl by mutableStateOf(false)
    var showLogoutConfirm by mutableStateOf(false)
    var showUserManagement by mutableStateOf(false)
    var showAuditLog by mutableStateOf(false)
    var showAppStats by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var showAttachPicker by mutableStateOf(false)
    var showChangePasswordSheet by mutableStateOf(false)
    var showSoftUpdateDialog by mutableStateOf(false)
    var showSchedulePick by mutableStateOf(false)
    var showScheduledList by mutableStateOf(false)
    // §TZ-0.10.5/2.5.2 — QR-вход на ПК + список активных PC-сессий.
    var showPcLogin by mutableStateOf(false)
    var showActivePcSessions by mutableStateOf(false)

    // Target-bound dialogs — non-null means "show with this payload"
    var pollStatsTarget by mutableStateOf<JSONObject?>(null)
    var newsReadersTarget by mutableStateOf<JSONObject?>(null)
    var confirmVoteTarget by mutableStateOf<Pair<JSONObject, List<Long>>?>(null)
    var editDeleteTarget by mutableStateOf<JSONObject?>(null)
    var editingItem by mutableStateOf<JSONObject?>(null)
    var reactionVotersTarget by mutableStateOf<Pair<Long, String>?>(null)
    var chatActionTarget by mutableStateOf<Pair<JSONObject, androidx.compose.ui.geometry.Rect>?>(null)
}

@Composable
fun rememberHomeDialogsState(): HomeDialogsState = remember { HomeDialogsState() }

/**
 * Renders every HomeScreen dialog/sheet based on [dialogs] state. Isolated
 * from HomeScreen to keep the main screen composable focused on layout and
 * content rendering.
 *
 * @param inputTextState host's input text — required to clear it when a
 *        scheduled post succeeds.
 * @param replyTarget host's reply draft — written to when user taps "reply"
 *        on a chat bubble action popup.
 */
@Composable
internal fun HomeDialogsHost(
    dialogs: HomeDialogsState,
    viewModel: HomeViewModel,
    state: HomeUiState,
    snackbarHostState: SnackbarHostState,
    inputTextState: MutableState<String>,
    replyTarget: MutableState<JSONObject?>,
    onNavigateToLogin: () -> Unit,
    onThemeChange: (com.example.otlhelper.core.theme.ThemeMode) -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()

    if (dialogs.showActionMenu) {
        ActionMenuDialog(
            isAdmin = viewModel.hasAdminAccess(),
            isSuperAdmin = viewModel.isDeveloper(),
            pollsFeatureEnabled = viewModel.getFeatures().pollsEnabled,
            userName = viewModel.getFullName(),
            userRole = viewModel.getRoleLabel(),
            avatarUrl = state.avatarUrl,
            presenceStatus = "online",
            onDismiss = { dialogs.showActionMenu = false },
            onCreatePoll = { dialogs.showActionMenu = false; dialogs.showPollBuilder = true },
            onShowAccount = { dialogs.showActionMenu = false; viewModel.openAccountScreen() },
            onShowSettings = { dialogs.showActionMenu = false; dialogs.showSettings = true },
            onSystemControl = { dialogs.showActionMenu = false; dialogs.showSystemControl = true },
            onManageUsers = { dialogs.showActionMenu = false; dialogs.showUserManagement = true },
            onShowAuditLog = { dialogs.showActionMenu = false; dialogs.showAuditLog = true },
            onShowAppStats = { dialogs.showActionMenu = false; dialogs.showAppStats = true },
            onShowScheduled = { dialogs.showActionMenu = false; dialogs.showScheduledList = true },
            onPcLogin = { dialogs.showActionMenu = false; dialogs.showPcLogin = true },
            onLogout = { dialogs.showActionMenu = false; dialogs.showLogoutConfirm = true },
        )
    }

    // §TZ-0.10.5/2.5.2 — PC login scanner.
    if (dialogs.showPcLogin) {
        com.example.otlhelper.presentation.pc_login.PcLoginScannerScreen(
            onClose = { dialogs.showPcLogin = false },
        )
    }

    // Base sync status — shared between Settings row and the dedicated progress sheet.
    val baseSyncStatus by viewModel.baseSyncManager.status()
        .collectAsState(initial = BaseSyncStatus.Idle)

    if (dialogs.showSettings) {
        SettingsDialog(
            settings = viewModel.appSettings,
            biometricLockManager = viewModel.biometricLockManager,
            baseVersion = state.baseVersion,
            baseUpdatedAt = state.baseUpdatedAt,
            baseSyncStatus = baseSyncStatus,
            // §TZ-2.3.4: убрали модальный BaseSyncDialog. Теперь клик «Обновить»
            // — тихий триггер; прогресс и ошибки отображаются инлайн в самой
            // Settings-строке справочника (BaseDatabaseRow). Без модала юзер
            // может продолжить работу пока база качается, а «Подождите» на
            // backoff не блокирует UI.
            onBaseSyncNow = { viewModel.triggerBaseSyncManual() },
            isDeveloper = viewModel.isDeveloper(),
            onThemeChange = onThemeChange,
            onDismiss = { dialogs.showSettings = false },
        )
    }

    if (dialogs.showPollBuilder) {
        PollBuilderDialog(
            onDismiss = { dialogs.showPollBuilder = false },
            onSubmit = { desc, opts, attachments ->
                dialogs.showPollBuilder = false
                viewModel.createPoll(desc, opts, attachments) { ok, msg ->
                    if (!ok) viewModel.setStatus(msg)
                }
            },
        )
    }

    dialogs.pollStatsTarget?.let { item ->
        PollStatsDialog(
            item = item,
            isAdmin = viewModel.hasAdminAccess(),
            onDismiss = { dialogs.pollStatsTarget = null },
            onLoadStats = { pollId, cb -> viewModel.getPollStats(pollId, cb) },
            onPinToggle = { msgId, pin, cb ->
                viewModel.togglePin(msgId, pin) { ok, msg -> cb(ok, msg) }
            },
        )
    }

    dialogs.newsReadersTarget?.let { item ->
        NewsReadersDialog(
            item = item,
            isAdmin = viewModel.hasAdminAccess(),
            onDismiss = { dialogs.newsReadersTarget = null },
            onLoadReaders = { msgId, cb -> viewModel.getNewsReaders(msgId, cb) },
            onPinToggle = { msgId, pin, cb ->
                viewModel.togglePin(msgId, pin) { ok, msg -> cb(ok, msg) }
            },
        )
    }

    dialogs.confirmVoteTarget?.let { (item, ids) ->
        ConfirmVoteDialog(
            onDismiss = { dialogs.confirmVoteTarget = null },
            onConfirm = {
                dialogs.confirmVoteTarget = null
                val pollId = (item.optJSONObject("poll") ?: item).optLong("poll_id", item.optLong("id", 0L))
                viewModel.votePoll(pollId, ids) { _, msg -> viewModel.setStatus(msg) }
            },
        )
    }

    if (dialogs.showSystemControl) {
        SystemControlDialog(
            isSuperAdmin = viewModel.isDeveloper(),
            onDismiss = { dialogs.showSystemControl = false },
            viewModel = viewModel,
        )
    }

    if (dialogs.showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { dialogs.showLogoutConfirm = false },
            title = { Text("Выйти из аккаунта?", color = TextPrimary) },
            text = { Text("Вы уверены, что хотите выйти?", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    dialogs.showLogoutConfirm = false
                    viewModel.logout { onNavigateToLogin() }
                }) { Text("Выйти", color = StatusErrorBorder) }
            },
            dismissButton = {
                TextButton(onClick = { dialogs.showLogoutConfirm = false }) {
                    Text("Отмена", color = TextSecondary)
                }
            },
            containerColor = BgCard,
        )
    }

    if (dialogs.showAuditLog) {
        AuditLogDialog(onDismiss = { dialogs.showAuditLog = false })
    }

    if (dialogs.showAppStats) {
        AppStatsDialog(onDismiss = { dialogs.showAppStats = false })
    }

    dialogs.reactionVotersTarget?.let { (msgId, emoji) ->
        ReactionVotersDialog(
            messageId = msgId,
            highlightEmoji = emoji,
            // Cache-first: return last known voters blob from AppSettings so
            // the dialog paints real data on the first frame (even offline /
            // slow first-open). Fresh network fetch overwrites silently on arrival.
            cachedJson = viewModel.appSettings.cachedReactionVotersJson(msgId),
            persistJson = { json -> viewModel.appSettings.saveCachedReactionVotersJson(msgId, json) },
            fetch = { id -> viewModel.loadReactions(id) },
            onDismiss = { dialogs.reactionVotersTarget = null },
        )
    }

    // Chat message action popup (long-press on chat bubble) — reactions + reply + copy.
    // Floats above/below the long-pressed bubble, Telegram-style.
    dialogs.chatActionTarget?.let { (target, bounds) ->
        val msgId = target.optLong("id", 0L)
        val myReactionsArr = target.optJSONArray("my_reactions")
        val myReactions = buildSet {
            if (myReactionsArr != null) for (i in 0 until myReactionsArr.length()) {
                val v = myReactionsArr.optString(i, "")
                if (v.isNotBlank()) add(v)
            }
        }
        // LocalClipboard (Compose 1.7+) — старый LocalClipboardManager deprecated.
        val clipboard = androidx.compose.ui.platform.LocalClipboard.current
        val clipboardScope = rememberCoroutineScope()
        ChatMessageActionPopup(
            bubbleBounds = bounds,
            myReactions = myReactions,
            onReact = { emoji, already ->
                if (msgId > 0L) viewModel.toggleReaction(msgId, emoji, already)
            },
            onReply = { replyTarget.value = target },
            onCopy = {
                val text = target.optString("text", "")
                if (text.isNotBlank()) {
                    clipboardScope.launch {
                        val clip = android.content.ClipData.newPlainText("otl-message", text)
                        clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(clip))
                    }
                }
            },
            onDismiss = { dialogs.chatActionTarget = null },
        )
    }

    if (dialogs.showSoftUpdateDialog && state.softUpdateUrl.isNotBlank()) {
        SoftUpdateDialog(
            version = state.softUpdateVersion,
            url = state.softUpdateUrl,
            onDismiss = { dialogs.showSoftUpdateDialog = false },
        )
    }

    // Phase 12a: Edit/Delete sheet on long-press.
    dialogs.editDeleteTarget?.let { target ->
        val senderLogin = target.optString("sender_login", "")
        val isAuthor = senderLogin == viewModel.getLogin()
        val isAdmin = viewModel.hasAdminAccess()
        EditDeleteSheet(
            canEdit = isAuthor || isAdmin,
            canDelete = isAuthor || isAdmin,
            onDismiss = { dialogs.editDeleteTarget = null },
            onEdit = {
                dialogs.editingItem = target
                dialogs.editDeleteTarget = null
            },
            onDelete = {
                val id = target.optLong("id", 0L)
                dialogs.editDeleteTarget = null
                if (id > 0L) viewModel.softDeleteMessage(id) { ok, _ ->
                    if (ok) {
                        coroutineScope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "Удалено",
                                actionLabel = "Отменить",
                                duration = SnackbarDuration.Short,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                viewModel.undeleteMessage(id) { _, _ -> }
                            }
                        }
                    }
                }
            },
        )
    }

    dialogs.editingItem?.let { target ->
        val initialText = target.optString("text", "")
        val id = target.optLong("id", 0L)
        EditMessageDialog(
            initialText = initialText,
            onDismiss = { dialogs.editingItem = null },
            onConfirm = { newText ->
                dialogs.editingItem = null
                if (id > 0L) viewModel.editMessage(id, newText) { ok, msg ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            if (ok) "Изменено" else msg.ifBlank { "Не удалось" },
                            duration = SnackbarDuration.Short,
                        )
                    }
                }
            },
        )
    }

    // Phase 12c: scheduled pick dialog + list.
    if (dialogs.showSchedulePick) {
        ScheduledPickDialog(
            onDismiss = { dialogs.showSchedulePick = false },
            onPick = { sendAtUtc ->
                dialogs.showSchedulePick = false
                val text = inputTextState.value.trim()
                if (text.isNotBlank()) {
                    viewModel.schedulePost("news", text, sendAtUtc) { ok, msg ->
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                if (ok) "Запланировано на $sendAtUtc UTC" else msg.ifBlank { "Не удалось" },
                                duration = SnackbarDuration.Short,
                            )
                        }
                        if (ok) {
                            inputTextState.value = ""
                            viewModel.saveDraft(HomeTab.NEWS, "")
                        }
                    }
                }
            },
        )
    }

    if (dialogs.showScheduledList) {
        ScheduledListDialog(onDismiss = { dialogs.showScheduledList = false })
    }

    if (dialogs.showUserManagement) {
        UserManagementDialog(
            viewModel = viewModel,
            onDismiss = { dialogs.showUserManagement = false },
        )
    }

    if (state.accountScreenOpen) {
        AccountScreen(
            viewModel = viewModel,
            onDismiss = { viewModel.closeAccountScreen() },
            onChangePassword = {
                // Close the account sheet and surface change-password as its
                // own sheet — no NavController hop, no splash re-init.
                viewModel.closeAccountScreen()
                dialogs.showChangePasswordSheet = true
            },
        )
    }

    if (dialogs.showChangePasswordSheet) {
        ChangePasswordSheet(onDismiss = { dialogs.showChangePasswordSheet = false })
    }

    if (dialogs.showAttachPicker) {
        AttachmentPickerSheet(
            onDismiss = { dialogs.showAttachPicker = false },
            onAttachmentPicked = { item ->
                viewModel.addAttachment(item)
                dialogs.showAttachPicker = false
            },
        )
    }
}
