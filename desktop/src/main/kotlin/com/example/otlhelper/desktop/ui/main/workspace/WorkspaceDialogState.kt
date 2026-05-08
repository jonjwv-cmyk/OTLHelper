package com.example.otlhelper.desktop.ui.main.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.otlhelper.desktop.ui.dialogs.PollStatsData
import com.example.otlhelper.desktop.ui.dialogs.ReactionVoter
import com.example.otlhelper.desktop.ui.dialogs.ReadersData

/**
 * §TZ-CLEANUP-2026-04-26 — state holder для всех модальных диалогов
 * правой панели. Отделяет управление видимостью от логики WorkspacePanel.
 *
 * Не включает: `openConversation`, `newsText`, `newsAttachments`,
 * `scheduleNotice` — это InputBar/Conversation state, остаётся в
 * WorkspacePanel и пробрасывается сюда callback'ами.
 */
class WorkspaceDialogState {
    // Menu sub-sheets
    var accountOpen by mutableStateOf(false)
    var avatarPickerOpen by mutableStateOf(false)
    var pollBuilderOpen by mutableStateOf(false)
    var scheduledListOpen by mutableStateOf(false)
    var userManagementOpen by mutableStateOf(false)
    var settingsOpen by mutableStateOf(false)

    // InputBar pickers
    var attachPickerOpen by mutableStateOf(false)
    var newsScheduleOpen by mutableStateOf(false)

    // Stats dialogs (target + lazy-loaded data)
    var readersTarget by mutableStateOf<Long?>(null)
    var readersData by mutableStateOf<ReadersData?>(null)
    var readersIsPinned by mutableStateOf(false)
    var pollStatsTarget by mutableStateOf<Pair<Long, Long>?>(null) // (pollId, messageId)
    var pollStatsData by mutableStateOf<PollStatsData?>(null)
    var pollStatsIsPinned by mutableStateOf(false)
    var reactionVotersMessageId by mutableStateOf<Long?>(null)
    var reactionVotersList by mutableStateOf<List<ReactionVoter>?>(null)

    // §back-flow: помнит был ли sub-sheet открыт из меню — back возвращает в меню,
    // но publish/outside-click — нет.
    private var cameFromMenu = false

    /**
     * Любой модальный sheet/dialog открыт (включая внешний menuOpen).
     * Используется для pause polling — иначе recompose теряет state форм ввода.
     */
    fun anyModalOpen(menuOpen: Boolean): Boolean =
        menuOpen || accountOpen || pollBuilderOpen || scheduledListOpen ||
            userManagementOpen || settingsOpen || newsScheduleOpen ||
            attachPickerOpen || avatarPickerOpen

    /** Открыть sub-sheet из меню: ставим флаг и закрываем меню (чтобы не было двойной модалки). */
    fun openFromMenu(setter: (Boolean) -> Unit, onMenuOpenChange: (Boolean) -> Unit) {
        setter(true)
        cameFromMenu = true
        onMenuOpenChange(false)
    }

    /** Back-стрелка: закрываем sub-sheet и, если он был open из меню — возвращаемся в меню. */
    fun closeAndMaybeReturnToMenu(setter: (Boolean) -> Unit, onMenuOpenChange: (Boolean) -> Unit) {
        setter(false)
        if (cameFromMenu) {
            onMenuOpenChange(true)
            cameFromMenu = false
        }
    }

    /** Полный close (publish/outside-click/X) — меню НЕ возвращается. */
    fun fullClose(setter: (Boolean) -> Unit) {
        setter(false)
        cameFromMenu = false
    }
}

@Composable
fun rememberWorkspaceDialogState(): WorkspaceDialogState =
    remember { WorkspaceDialogState() }
