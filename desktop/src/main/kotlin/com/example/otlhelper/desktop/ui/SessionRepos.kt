package com.example.otlhelper.desktop.ui

import com.example.otlhelper.desktop.core.session.SessionLifecycleManager
import com.example.otlhelper.desktop.data.chat.ConversationRepository
import com.example.otlhelper.desktop.data.chat.InboxRepository
import com.example.otlhelper.desktop.data.feed.NewsRepository
import com.example.otlhelper.desktop.data.scheduled.ScheduledRepository
import com.example.otlhelper.desktop.data.users.UsersRepository
import kotlinx.coroutines.CoroutineScope

/**
 * §TZ-DESKTOP-0.1.0 — контейнер репозиториев для активной сессии.
 * Живёт на уровне App (пока login не сменился), чтобы сворачивание правой
 * панели не теряло состояние. Polling стартует/останавливается из App.
 */
class SessionRepos(
    val scope: CoroutineScope,
    val inboxRepo: InboxRepository,
    val newsRepo: NewsRepository,
    val convRepo: ConversationRepository,
    val scheduledRepo: ScheduledRepository,
    val usersRepo: UsersRepository,
    val sessionLifecycle: SessionLifecycleManager,
)
