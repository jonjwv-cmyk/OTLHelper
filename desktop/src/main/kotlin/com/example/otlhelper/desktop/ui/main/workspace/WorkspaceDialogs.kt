package com.example.otlhelper.desktop.ui.main.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.otlhelper.desktop.data.feed.NewsRepository
import com.example.otlhelper.desktop.model.Role
import com.example.otlhelper.desktop.ui.SessionRepos
import com.example.otlhelper.desktop.ui.dialogs.AccountSheet
import com.example.otlhelper.desktop.ui.dialogs.AttachmentPickerSheet
import com.example.otlhelper.desktop.ui.dialogs.AvatarPickerSheet
import com.example.otlhelper.desktop.ui.dialogs.NewsReadersDialog
import com.example.otlhelper.desktop.ui.dialogs.PollBuilderSheet
import com.example.otlhelper.desktop.ui.dialogs.PollStatsDialog
import com.example.otlhelper.desktop.ui.dialogs.ReactionVotersDialog
import com.example.otlhelper.desktop.ui.dialogs.ScheduledListSheet
import com.example.otlhelper.desktop.ui.dialogs.ScheduledPickDialog
import com.example.otlhelper.desktop.ui.dialogs.SettingsSheet
import com.example.otlhelper.desktop.ui.dialogs.UserManagementSheet
import com.example.otlhelper.desktop.core.update.VersionInfo
import com.example.otlhelper.desktop.ui.main.DesktopAttachment
import com.example.otlhelper.desktop.ui.main.MenuSheet
import kotlinx.coroutines.launch

/**
 * §TZ-CLEANUP-2026-04-26 — все модальные диалоги правой панели в одном
 * composable. Принимает [WorkspaceDialogState] (видимость + lazy-loaded
 * данные stats-диалогов) и callback'и для общения с WorkspacePanel
 * (notice в InputBar, добавление вложений, schedule submit).
 *
 * Внутри сам собирает scheduledState/usersState из repos — эти стейты
 * нужны только в этой ветке UI, их collect в WorkspacePanel был лишним.
 */
@Composable
internal fun WorkspaceDialogs(
    state: WorkspaceDialogState,
    role: Role,
    login: String,
    fullName: String,
    avatarUrl: String,
    repos: SessionRepos,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onLogout: () -> Unit,
    newsState: NewsRepository.State,
    versionInfo: VersionInfo = VersionInfo(),
    onUpdateRequest: () -> Unit = {},
    onScheduleNotice: (String) -> Unit,
    onAttachmentPicked: (DesktopAttachment) -> Unit,
    onSchedulePicked: (iso: String) -> Unit,
) {
    val repoScope = repos.scope
    val newsRepo = repos.newsRepo
    val scheduledRepo = repos.scheduledRepo
    val usersRepo = repos.usersRepo
    val scheduledState by scheduledRepo.state.collectAsState()
    val usersState by usersRepo.state.collectAsState()

    // ── Menu ──
    if (menuOpen) {
        MenuSheet(
            login = login,
            fullName = fullName,
            avatarUrl = avatarUrl,
            role = role,
            onDismiss = { onMenuOpenChange(false) },
            onLogout = { onMenuOpenChange(false); onLogout() },
            onCreatePoll = {
                state.openFromMenu({ state.pollBuilderOpen = it }, onMenuOpenChange)
            },
            onShowScheduled = {
                state.openFromMenu({ state.scheduledListOpen = it }, onMenuOpenChange)
            },
            onShowAccount = {
                state.openFromMenu({ state.accountOpen = it }, onMenuOpenChange)
            },
            onShowSettings = {
                state.openFromMenu({ state.settingsOpen = it }, onMenuOpenChange)
            },
            onShowUsers = {
                state.openFromMenu({ state.userManagementOpen = it }, onMenuOpenChange)
            },
        )
    }

    // ── Menu sub-sheets ──
    // onDismiss = полный close (publish / outside-click / X) — меню НЕ возвращается.
    // onBack = back-стрелка — если пришли из меню, возвращаемся в меню.
    if (state.accountOpen) {
        AccountSheet(
            login = login,
            fullName = fullName,
            avatarUrl = avatarUrl,
            role = role,
            onDismiss = { state.fullClose { state.accountOpen = it } },
            onBack = {
                state.closeAndMaybeReturnToMenu({ state.accountOpen = it }, onMenuOpenChange)
            },
            onAvatarPickerOpen = { state.avatarPickerOpen = true },
        )
    }
    if (state.avatarPickerOpen) {
        AvatarPickerSheet(
            onDismiss = { state.avatarPickerOpen = false },
            onPicked = { file ->
                repoScope.launch {
                    uploadAvatar(file)
                    usersRepo.refresh()
                }
            },
        )
    }
    if (state.pollBuilderOpen) {
        PollBuilderSheet(
            onDismiss = { state.fullClose { state.pollBuilderOpen = it } },
            onBack = {
                state.closeAndMaybeReturnToMenu({ state.pollBuilderOpen = it }, onMenuOpenChange)
            },
            onCreate = { title, description, options ->
                repoScope.launch { newsRepo.createPoll(title, description, options) }
            },
        )
    }
    if (state.scheduledListOpen) {
        LaunchedEffect(Unit) { scheduledRepo.refresh() }
        ScheduledListSheet(
            state = scheduledState,
            onCancel = { id -> repoScope.launch { scheduledRepo.cancel(id) } },
            onDismiss = { state.fullClose { state.scheduledListOpen = it } },
            onBack = {
                state.closeAndMaybeReturnToMenu({ state.scheduledListOpen = it }, onMenuOpenChange)
            },
        )
    }
    if (state.settingsOpen) {
        SettingsSheet(
            versionInfo = versionInfo,
            onUpdateRequest = onUpdateRequest,
            onDismiss = { state.fullClose { state.settingsOpen = it } },
            onBack = {
                state.closeAndMaybeReturnToMenu({ state.settingsOpen = it }, onMenuOpenChange)
            },
            sessionLifecycle = repos.sessionLifecycle,
        )
    }
    if (state.userManagementOpen) {
        // §0.10.26 — auto-poll каждые 3 сек когда screen open. Юзер хочет
        // мгновенный refresh статусов (presence dot online/paused/offline).
        // True realtime (<1s) требует WS presence event broadcast — отдельный
        // спринт 0.11.0. Пока 3-сек polling даёт subjective realtime.
        LaunchedEffect(Unit) {
            while (true) {
                usersRepo.refresh()
                kotlinx.coroutines.delay(3_000)
            }
        }
        UserManagementSheet(
            state = usersState,
            currentRole = role,
            onCreate = { newLogin, name, pwd, r, mustChange ->
                repoScope.launch { usersRepo.createUser(newLogin, name, pwd, r, mustChange) }
            },
            onChangeRole = { target, newRole ->
                repoScope.launch { usersRepo.changeRole(target, newRole) }
            },
            onResetPassword = { target, newPass ->
                repoScope.launch { usersRepo.resetPassword(target, newPass) }
            },
            onChangeLogin = { target, newLogin ->
                repoScope.launch { usersRepo.changeLogin(target, newLogin) }
            },
            // §TZ-DESKTOP-0.10.2 — раньше callback отсутствовал → клик
            // «Переименовать» был no-op, full_name не менялся.
            onChangeFullName = { target, newName ->
                repoScope.launch { usersRepo.renameUser(target, newName) }
            },
            onDelete = { target ->
                repoScope.launch { usersRepo.deleteUser(target) }
            },
            onResetPasswordCounter = { target ->
                repoScope.launch { usersRepo.resetPasswordLoginCounter(target) }
            },
            onDismiss = { state.fullClose { state.userManagementOpen = it } },
            onBack = {
                state.closeAndMaybeReturnToMenu({ state.userManagementOpen = it }, onMenuOpenChange)
            },
        )
    }

    // ── §ТЗ-5 — stats dialogs (реальные данные с lazy load) ──
    state.readersTarget?.let { messageId ->
        val data = state.readersData
        if (data == null) {
            // Первый render — запускаем загрузку. state обновится → recompose → покажем диалог.
            LaunchedEffect(messageId) {
                val loaded = loadNewsReaders(messageId)
                if (loaded != null) state.readersData = loaded
                else state.readersTarget = null // ошибка — просто закроем
            }
        } else {
            NewsReadersDialog(
                data = data,
                isAdmin = role.isAdmin,
                isPinnedInitial = state.readersIsPinned,
                onPinToggle = { pin ->
                    val already = newsState.pinnedItems.any { it.id == messageId }
                    if (pin && !already && newsState.pinnedItems.size >= 3) {
                        onScheduleNotice("Максимум 3 закрепа")
                    } else {
                        repoScope.launch { newsRepo.togglePin(messageId, pin) }
                        state.readersIsPinned = pin
                    }
                },
                onDismiss = {
                    state.readersTarget = null
                    state.readersData = null
                },
            )
        }
    }

    state.pollStatsTarget?.let { (pollId, messageId) ->
        val data = state.pollStatsData
        if (data == null) {
            LaunchedEffect(pollId) {
                val loaded = loadPollStats(pollId)
                if (loaded != null) state.pollStatsData = loaded
                else state.pollStatsTarget = null
            }
        } else {
            PollStatsDialog(
                data = data,
                isAdmin = role.isAdmin,
                isPinnedInitial = state.pollStatsIsPinned,
                onPinToggle = { pin ->
                    val already = newsState.pinnedItems.any { it.id == messageId }
                    if (pin && !already && newsState.pinnedItems.size >= 3) {
                        onScheduleNotice("Максимум 3 закрепа")
                    } else {
                        repoScope.launch { newsRepo.togglePin(messageId, pin) }
                        state.pollStatsIsPinned = pin
                    }
                },
                onDismiss = {
                    state.pollStatsTarget = null
                    state.pollStatsData = null
                },
            )
        }
    }

    state.reactionVotersMessageId?.let { msgId ->
        val list = state.reactionVotersList
        if (list == null) {
            LaunchedEffect(msgId) {
                val loaded = loadReactionVoters(msgId)
                if (loaded != null) state.reactionVotersList = loaded
                else state.reactionVotersMessageId = null
            }
        } else {
            ReactionVotersDialog(
                voters = list,
                onDismiss = {
                    state.reactionVotersMessageId = null
                    state.reactionVotersList = null
                },
            )
        }
    }

    // ── Pickers из InputBar ──
    if (state.attachPickerOpen) {
        AttachmentPickerSheet(
            onDismiss = { state.attachPickerOpen = false },
            onPicked = { att -> onAttachmentPicked(att) },
        )
    }
    if (state.newsScheduleOpen) {
        ScheduledPickDialog(
            onDismiss = { state.newsScheduleOpen = false },
            onPick = { iso ->
                state.newsScheduleOpen = false
                onSchedulePicked(iso)
            },
        )
    }
}
