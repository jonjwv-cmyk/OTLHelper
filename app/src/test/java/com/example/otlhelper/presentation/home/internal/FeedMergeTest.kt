package com.example.otlhelper.presentation.home.internal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * Pure unit tests for [FeedMerge.kt] — no coroutines, no mocks, just JSON in/out.
 * These helpers drive anti-duplicate semantics for optimistic message send:
 * if someone breaks the matching rules, a chat-bubble regression (vanishing
 * just-sent message OR duplicated message) ships silently. Tests guard that.
 */
class FeedMergeTest {

    // ── mergeWithPending ───────────────────────────────────────────────────

    @Test
    fun `empty previous feed returns server list unchanged`() {
        val server = listOf(msg(id = 1, sender = "a", text = "hello"))
        val result = mergeWithPending(server, emptyList())
        assertEquals(1, result.size)
        assertEquals(1L, result[0].optLong("id"))
    }

    @Test
    fun `empty server list with no pendings returns empty`() {
        val result = mergeWithPending(emptyList(), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `pending that matches a server row is dropped (dedup)`() {
        // Optimistic row the UI appended right after send.
        val pending = pendingMsg(localId = "local-1", sender = "alice", text = "привет")
        // Server eventually committed the same message with its real id.
        val server = listOf(msg(id = 42, sender = "alice", text = "привет"))
        val result = mergeWithPending(server, listOf(pending))
        assertEquals("pending should have been deduped — server won", 1, result.size)
        assertEquals(42L, result[0].optLong("id"))
        assertFalse("server row is not marked pending", result[0].optBoolean("is_pending"))
    }

    @Test
    fun `pending with no server match survives at the end`() {
        val pending = pendingMsg(localId = "local-2", sender = "bob", text = "ещё не дошло")
        val server = listOf(msg(id = 7, sender = "alice", text = "старое"))
        val result = mergeWithPending(server, listOf(pending))
        assertEquals(2, result.size)
        // Pendings are appended AFTER server rows — they're the newest.
        assertEquals("старое", result[0].optString("text"))
        assertEquals("ещё не дошло", result[1].optString("text"))
        assertTrue(result[1].optBoolean("is_pending"))
    }

    @Test
    fun `pending without local_item_id is ignored (treated as non-pending)`() {
        // Without local_item_id we can't anchor it — safer to drop than risk a
        // stale optimistic row sticking around forever.
        val pending = JSONObject().apply {
            put("is_pending", true)
            put("sender_login", "alice")
            put("text", "no-id")
        }
        val server = listOf(msg(id = 99, sender = "alice", text = "real"))
        val result = mergeWithPending(server, listOf(pending))
        assertEquals(1, result.size)
        assertEquals("real", result[0].optString("text"))
    }

    @Test
    fun `server rows without is_pending flag are filtered out of pending list`() {
        // Previous feed contains a confirmed server row AND an optimistic pending.
        // Only the genuinely pending one should be considered for carry-over.
        val confirmed = msg(id = 1, sender = "a", text = "old")
        val pending = pendingMsg(localId = "local-3", sender = "a", text = "new")
        val newServer = listOf(msg(id = 1, sender = "a", text = "old"))
        val result = mergeWithPending(newServer, listOf(confirmed, pending))
        // confirmed is already in newServer (same id wouldn't cause dup, but its
        // logic doesn't run because it's not flagged pending). pending survives.
        assertEquals(2, result.size)
        assertTrue(result[1].optBoolean("is_pending"))
    }

    @Test
    fun `multiple pendings - some match, some do not`() {
        val matched = pendingMsg(localId = "l1", sender = "a", text = "one")
        val unmatched = pendingMsg(localId = "l2", sender = "a", text = "two")
        val server = listOf(
            msg(id = 10, sender = "a", text = "zero"),
            msg(id = 11, sender = "a", text = "one"),  // matches `matched`
        )
        val result = mergeWithPending(server, listOf(matched, unmatched))
        assertEquals(3, result.size)
        assertEquals("zero", result[0].optString("text"))
        assertEquals("one", result[1].optString("text"))
        assertEquals("two", result[2].optString("text"))
        assertTrue("unmatched pending should still be flagged", result[2].optBoolean("is_pending"))
    }

    // ── sortFeedItems ──────────────────────────────────────────────────────

    @Test
    fun `sortFeedItems orders by created_at ascending`() {
        val items = listOf(
            msg(id = 1, sender = "a", text = "c", createdAt = "2026-04-19T10:00:00Z"),
            msg(id = 2, sender = "a", text = "a", createdAt = "2026-04-19T08:00:00Z"),
            msg(id = 3, sender = "a", text = "b", createdAt = "2026-04-19T09:00:00Z"),
        )
        val sorted = sortFeedItems(items)
        assertEquals("a", sorted[0].optString("text"))
        assertEquals("b", sorted[1].optString("text"))
        assertEquals("c", sorted[2].optString("text"))
    }

    @Test
    fun `sortFeedItems handles missing created_at as empty string (stable)`() {
        val items = listOf(
            msg(id = 1, sender = "a", text = "has-ts", createdAt = "2026-04-19T10:00:00Z"),
            JSONObject().apply { put("id", 2); put("text", "no-ts") },
        )
        val sorted = sortFeedItems(items)
        // Empty string sorts before any real timestamp.
        assertEquals("no-ts", sorted[0].optString("text"))
        assertEquals("has-ts", sorted[1].optString("text"))
    }

    @Test
    fun `sortFeedItems preserves already-sorted input`() {
        val items = listOf(
            msg(id = 1, sender = "a", text = "first", createdAt = "2026-04-19T08:00:00Z"),
            msg(id = 2, sender = "a", text = "second", createdAt = "2026-04-19T09:00:00Z"),
        )
        val sorted = sortFeedItems(items)
        assertEquals("first", sorted[0].optString("text"))
        assertEquals("second", sorted[1].optString("text"))
    }

    // ── nowUtcIso ──────────────────────────────────────────────────────────

    @Test
    fun `nowUtcIso output matches ISO-8601 UTC 'Z' shape`() {
        val iso = nowUtcIso()
        // "yyyy-MM-ddTHH:mm:ssZ" — 20 chars, ends with Z
        val pattern = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$")
        assertTrue(
            "Expected ISO-8601 UTC format, got: $iso",
            pattern.matcher(iso).matches(),
        )
    }

    @Test
    fun `nowUtcIso uses UTC not local timezone`() {
        // If the formatter accidentally ran in local TZ, two calls ~100ms apart
        // would differ by a second at most — but the HOUR field must match UTC
        // hour from System.currentTimeMillis.
        val iso = nowUtcIso()
        val utcHour = java.time.Instant.now()
            .atOffset(java.time.ZoneOffset.UTC)
            .hour
        val isoHour = iso.substring(11, 13).toInt()
        // Account for crossing an hour boundary between the two calls.
        val diff = (isoHour - utcHour + 24) % 24
        assertTrue(
            "ISO hour $isoHour should match UTC hour $utcHour (or off by 1 across boundary)",
            diff == 0 || diff == 1 || diff == 23,
        )
    }

    // ── JSONArray.toJsonList extension ─────────────────────────────────────

    @Test
    fun `toJsonList converts JSONArray to List of JSONObject preserving order`() {
        val arr = JSONArray().apply {
            put(JSONObject().put("id", 1))
            put(JSONObject().put("id", 2))
            put(JSONObject().put("id", 3))
        }
        val list = arr.toJsonList()
        assertEquals(3, list.size)
        assertEquals(1L, list[0].optLong("id"))
        assertEquals(2L, list[1].optLong("id"))
        assertEquals(3L, list[2].optLong("id"))
    }

    @Test
    fun `toJsonList skips non-object entries silently`() {
        // JSONArray can contain non-object values (strings, numbers) — toJsonList
        // must not throw. optJSONObject returns null for those; we skip.
        val arr = JSONArray().apply {
            put(JSONObject().put("id", 1))
            put("not-an-object")
            put(42)
            put(JSONObject().put("id", 2))
        }
        val list = arr.toJsonList()
        assertEquals(2, list.size)
        assertEquals(1L, list[0].optLong("id"))
        assertEquals(2L, list[1].optLong("id"))
    }

    @Test
    fun `toJsonList on empty JSONArray returns empty list`() {
        val list = JSONArray().toJsonList()
        assertTrue(list.isEmpty())
    }

    // ── Test helpers ───────────────────────────────────────────────────────

    private fun msg(
        id: Long,
        sender: String,
        text: String,
        createdAt: String = "2026-04-19T10:00:00Z",
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("sender_login", sender)
        put("text", text)
        put("created_at", createdAt)
    }

    private fun pendingMsg(
        localId: String,
        sender: String,
        text: String,
    ): JSONObject = JSONObject().apply {
        put("local_item_id", localId)
        put("is_pending", true)
        put("sender_login", sender)
        put("text", text)
        put("created_at", "2026-04-19T10:00:00Z")
    }
}
