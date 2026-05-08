package com.example.otlhelper.shared.api

/**
 * Shared API field-name constants used in JSON request/response payloads.
 *
 * Keep this list in sync with `server-modular/handlers-*.js` and the
 * `app/`/`desktop/` API clients. Client code should reference these
 * constants instead of hardcoded string literals so a typo in any single
 * field name fails at compile time, not silently at runtime.
 *
 * Convention: group by domain, alphabetize inside each group.
 */
object ApiFields {

    // ── Generic envelope ────────────────────────────────────────────────
    const val ACTION = "action"
    const val DATA = "data"
    const val ERROR = "error"
    const val OK = "ok"
    const val PAYLOAD = "payload"
    const val RAW = "raw"

    // ── Auth / session ──────────────────────────────────────────────────
    const val APP_VERSION = "app_version"
    const val DEVICE_ID = "device_id"
    const val INTEGRITY_NONCE = "integrity_nonce"
    const val INTEGRITY_TOKEN = "integrity_token"
    const val LOGIN = "login"
    const val MUST_CHANGE_PASSWORD = "must_change_password"
    const val NEW_PASSWORD = "new_password"
    const val OLD_PASSWORD = "old_password"
    const val OS_VERSION = "os_version"
    const val PASSWORD = "password"
    const val PLATFORM = "platform"
    const val TOKEN = "token"

    // ── Identity / user / role ──────────────────────────────────────────
    const val ACTOR_LOGIN = "actor_login"
    const val APP_SCOPE = "app_scope"
    const val FULL_NAME = "full_name"
    const val NEW_LOGIN = "new_login"
    const val NEW_ROLE = "new_role"
    const val PEER_LOGIN = "peer_login"
    const val RECEIVER_LOGIN = "receiver_login"
    const val ROLE = "role"
    const val TARGET_LOGIN = "target_login"
    const val USER_LOGIN = "user_login"

    // ── Items: news / messages / polls ──────────────────────────────────
    const val ALLOW_REVOTING = "allow_revoting"
    const val ATTACHMENTS = "attachments"
    const val DESCRIPTION = "description"
    const val ID = "id"
    const val KIND = "kind"
    const val LOCAL_ITEM_ID = "local_item_id"
    const val MESSAGE_ID = "message_id"
    const val OPTION_IDS = "option_ids"
    const val OPTIONS = "options"
    const val POLL_ID = "poll_id"
    const val REPLY_TO_ID = "reply_to_id"
    const val SELECTION_MODE = "selection_mode"
    const val TEXT = "text"
    const val TITLE = "title"

    // ── Reactions ───────────────────────────────────────────────────────
    const val EMOJI = "emoji"
    const val TARGET_TYPE = "target_type"

    // ── Attachments / files / blobs ─────────────────────────────────────
    const val DATA_URL = "data_url"
    const val FILE_NAME = "file_name"
    const val MIME_TYPE = "mime_type"

    // ── Push / device ───────────────────────────────────────────────────
    const val PUSH_TOKEN = "push_token"

    // ── Pagination / scheduling / time ─────────────────────────────────
    const val FROM_DATE = "from_date"
    const val LIMIT = "limit"
    const val OFFSET = "offset"
    const val SEND_AT = "send_at"
    const val SINCE_DAYS = "since_days"
    const val TO_DATE = "to_date"

    // ── App state / metrics ─────────────────────────────────────────────
    const val APP_STATE = "app_state"
    const val ERRORS = "errors"
    const val EVENTS = "events"
    const val METRICS = "metrics"
    const val SCOPE = "scope"
    const val STATE = "state"

    // ── Filters / settings ──────────────────────────────────────────────
    const val ACTION_FILTER = "action_filter"
    const val AUTO_WIPE_AFTER_HOURS = "auto_wipe_after_hours"
    const val DND_END = "dnd_end"
    const val DND_START = "dnd_start"
    const val INCLUDE_ADMINS = "include_admins"
    const val MESSAGE = "message"
    const val REQUIRE_CONFIRMATION = "require_confirmation"

    // ── Standard values (used as constants in code) ─────────────────────

    /** Value for [PLATFORM] — Android client. */
    const val PLATFORM_ANDROID = "android"
    /** Value for [PLATFORM] — desktop client (macOS/Windows). */
    const val PLATFORM_DESKTOP = "desktop"

    /** Values for [APP_STATE]. */
    const val APP_STATE_FOREGROUND = "foreground"
    const val APP_STATE_BACKGROUND = "background"
    const val APP_STATE_PAUSED = "paused"

    /** Values for [KIND] of news items. */
    const val KIND_NEWS = "news"
    const val KIND_POLL = "poll"

    /** Values for [SELECTION_MODE] in polls. */
    const val SELECTION_MODE_SINGLE = "single"
    const val SELECTION_MODE_MULTI = "multi"

    /** Values for [SCOPE]. */
    const val SCOPE_MAIN = "main"
}
