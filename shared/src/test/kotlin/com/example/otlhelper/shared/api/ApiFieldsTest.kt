package com.example.otlhelper.shared.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiFieldsTest {

    /** Field NAMES (used as JSON keys) must be unique — typo in any single
     *  one would silently fail at runtime, so we lock the list here. */
    @Test
    fun fieldNamesAreUnique() {
        val names = listOf(
            ApiFields.ACTION,
            ApiFields.ACTION_FILTER,
            ApiFields.ACTOR_LOGIN,
            ApiFields.ALLOW_REVOTING,
            ApiFields.APP_SCOPE,
            ApiFields.APP_STATE,
            ApiFields.APP_VERSION,
            ApiFields.ATTACHMENTS,
            ApiFields.AUTO_WIPE_AFTER_HOURS,
            ApiFields.DATA,
            ApiFields.DATA_URL,
            ApiFields.DESCRIPTION,
            ApiFields.DEVICE_ID,
            ApiFields.DND_END,
            ApiFields.DND_START,
            ApiFields.EMOJI,
            ApiFields.ERROR,
            ApiFields.ERRORS,
            ApiFields.EVENTS,
            ApiFields.FILE_NAME,
            ApiFields.FROM_DATE,
            ApiFields.FULL_NAME,
            ApiFields.ID,
            ApiFields.INCLUDE_ADMINS,
            ApiFields.INTEGRITY_NONCE,
            ApiFields.INTEGRITY_TOKEN,
            ApiFields.KIND,
            ApiFields.LIMIT,
            ApiFields.LOCAL_ITEM_ID,
            ApiFields.LOGIN,
            ApiFields.MESSAGE,
            ApiFields.MESSAGE_ID,
            ApiFields.METRICS,
            ApiFields.MIME_TYPE,
            ApiFields.MUST_CHANGE_PASSWORD,
            ApiFields.NEW_LOGIN,
            ApiFields.NEW_PASSWORD,
            ApiFields.NEW_ROLE,
            ApiFields.OFFSET,
            ApiFields.OK,
            ApiFields.OLD_PASSWORD,
            ApiFields.OPTION_IDS,
            ApiFields.OPTIONS,
            ApiFields.OS_VERSION,
            ApiFields.PASSWORD,
            ApiFields.PAYLOAD,
            ApiFields.PEER_LOGIN,
            ApiFields.PLATFORM,
            ApiFields.POLL_ID,
            ApiFields.PUSH_TOKEN,
            ApiFields.RAW,
            ApiFields.RECEIVER_LOGIN,
            ApiFields.REPLY_TO_ID,
            ApiFields.REQUIRE_CONFIRMATION,
            ApiFields.ROLE,
            ApiFields.SCOPE,
            ApiFields.SELECTION_MODE,
            ApiFields.SEND_AT,
            ApiFields.SINCE_DAYS,
            ApiFields.STATE,
            ApiFields.TARGET_LOGIN,
            ApiFields.TARGET_TYPE,
            ApiFields.TEXT,
            ApiFields.TITLE,
            ApiFields.TO_DATE,
            ApiFields.TOKEN,
            ApiFields.USER_LOGIN,
        )
        assertEquals(names.sorted(), names.distinct().sorted())
    }

    /** Field names must be non-blank lowercase snake_case (defensive shape check). */
    @Test
    fun fieldNamesShape() {
        val pattern = Regex("^[a-z][a-z0-9_]*$")
        val names = listOf(
            ApiFields.ACTION, ApiFields.OK, ApiFields.ERROR, ApiFields.LOGIN,
            ApiFields.TOKEN, ApiFields.MESSAGE_ID, ApiFields.POLL_ID,
            ApiFields.RECEIVER_LOGIN, ApiFields.TARGET_LOGIN,
        )
        for (n in names) {
            assertTrue("field '$n' must match snake_case pattern", pattern.matches(n))
        }
    }

    /** Standard values used for [ApiFields.PLATFORM] / [ApiFields.APP_STATE] /
     *  [ApiFields.KIND] / [ApiFields.SELECTION_MODE] must be distinct from
     *  field names (otherwise we'd risk matching a value where a key was expected). */
    @Test
    fun standardValuesAreNotFieldNames() {
        val values = listOf(
            ApiFields.PLATFORM_ANDROID,
            ApiFields.PLATFORM_DESKTOP,
            ApiFields.APP_STATE_FOREGROUND,
            ApiFields.APP_STATE_BACKGROUND,
            ApiFields.APP_STATE_PAUSED,
            ApiFields.KIND_NEWS,
            ApiFields.KIND_POLL,
            ApiFields.SELECTION_MODE_SINGLE,
            ApiFields.SELECTION_MODE_MULTI,
            ApiFields.SCOPE_MAIN,
        )
        assertEquals(values.sorted(), values.distinct().sorted())
    }
}
