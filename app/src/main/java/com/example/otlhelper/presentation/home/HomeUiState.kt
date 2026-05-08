package com.example.otlhelper.presentation.home

import android.net.Uri
import com.example.otlhelper.domain.model.MolRecord
import com.example.otlhelper.domain.model.SearchMode
import org.json.JSONObject

data class AttachmentItem(val uri: Uri, val fileName: String, val mimeType: String, val fileSize: Long)

enum class HomeTab { NEWS, SEARCH, MONITORING }

data class HomeUiState(
    // Navigation / tabs
    val activeTab: HomeTab = HomeTab.NEWS,

    // Search
    val searchQuery: String = "",
    val searchResults: List<MolRecord> = emptyList(),
    val searchLoading: Boolean = false,
    val searchMode: SearchMode = SearchMode.NONE,
    val pinnedWarehouse: MolRecord? = null,
    val pinnedWarehouseExpanded: Boolean = false,

    // Feed (news / chat)
    val feedItems: List<JSONObject> = emptyList(),
    val feedLoading: Boolean = false,
    val feedError: String = "",
    val feedFromCache: Boolean = false,

    // In-memory per-scope feed cache — survives tab switches so going back to a
    // tab shows its previous content instantly (no reload flicker).
    //
    // Keyed by scope string:
    //   "news"             — News tab
    //   "user_chat"        — Monitoring as a regular user
    //   "admin_inbox"      — Monitoring list of contacts (admin)
    //   "admin_conv:login" — Monitoring conversation with a specific user
    val feedItemsCache: Map<String, List<JSONObject>> = emptyMap(),

    // Admin monitoring
    val adminConversationOpen: Boolean = false,
    val selectedAdminUserLogin: String? = null,
    val selectedAdminUserName: String? = null,
    val selectedAdminUserAvatarUrl: String? = null,

    // App update banner — soft update (non-mandatory). When true, a chip/banner
    // offers "Доступно обновление vX.Y" with a tap-to-download action.
    val softUpdateAvailable: Boolean = false,
    val softUpdateVersion: String = "",
    val softUpdateUrl: String = "",
    val softUpdateDismissed: Boolean = false,


    // Badges
    val newsUnreadCount: Int = 0,
    val monitoringUnreadCount: Int = 0,

    // Input
    val inputDrafts: Map<HomeTab, String> = emptyMap(),

    // Splash / startup
    val splashVisible: Boolean = true,
    val splashStatus: String = "Запускаем...",

    // Block overlay
    val blockOverlayVisible: Boolean = false,
    val blockTitle: String = "",
    val blockMessage: String = "",
    // Version tag for the APK that `updateUrl` points to — used to version the
    // cached APK file so we don't re-download what the user already has.
    val blockUpdateVersion: String = "",

    // Account screen
    val accountScreenOpen: Boolean = false,

    // Subtitle (transient status messages)
    val statusMessage: String = "",

    // Pending scroll restoration
    val pendingScrollKey: String? = null,

    // Закреплённые новости/опросы — §3.5. До [com.example.otlhelper.domain.limits.Limits.MAX_PINNED]
    // элементов одновременно. Серверный порядок сохраняется.
    val pinnedFeedItems: List<JSONObject> = emptyList(),

    // Block overlay update URL
    val updateUrl: String = "",

    // Users management (developer)
    val usersList: List<JSONObject> = emptyList(),
    val usersLoading: Boolean = false,

    // Pending attachments
    val pendingAttachments: List<AttachmentItem> = emptyList(),

    // Avatar
    val avatarUrl: String = "",
    val avatarUploading: Boolean = false,
    val avatarUploadError: String = "",

    // Local base (search DB) metadata — shown in Settings
    val baseVersion: String = "",
    val baseUpdatedAt: String = "",

    // Phase 8: typing indicator — login'ы текущих собеседников, которые сейчас
    // набирают текст. TTL 3s (см. Limits.TYPING_INDICATOR_TTL_MS) — сбрасывается
    // VM-корутиной если stop не приходит.
    val typingFromLogins: Set<String> = emptySet(),
)
