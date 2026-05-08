package com.example.otlhelper.presentation.home.internal

import com.example.otlhelper.domain.model.SearchMode
import com.example.otlhelper.presentation.home.internal.SearchController.Companion.detectSearchMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for search-mode auto-detection. This regex trio drives which
 * MolRepository call the app makes — misclassification surfaces as
 * "phone search returned no results" / "warehouse search matched everything".
 *
 * Precedence contract (enforced by ordering in [detectSearchMode]):
 *   EMAIL > PHONE > WAREHOUSE > NAME
 *
 * Boundary cases are baked in because each was a real-world mishap at some
 * point (e.g. short alphanumeric like "А1" got phoned for a while until the
 * 7-digit minimum was added).
 */
class SearchModeTest {

    // ── EMAIL ──────────────────────────────────────────────────────────────

    @Test fun `simple email detected as EMAIL`() =
        assertEquals(SearchMode.EMAIL, detectSearchMode("user@example.com"))

    @Test fun `email embedded in a longer query still detected`() =
        // containsMatchIn, not matches — "кто это: a@b.ru?" still surfaces as email
        assertEquals(SearchMode.EMAIL, detectSearchMode("кто это a@b.ru"))

    @Test fun `cyrillic-domain email detected`() =
        assertEquals(SearchMode.EMAIL, detectSearchMode("ivanov@почта.рф"))

    // ── PHONE ──────────────────────────────────────────────────────────────

    @Test fun `11 digits with +7 prefix is PHONE`() =
        assertEquals(SearchMode.PHONE, detectSearchMode("+79501234567"))

    @Test fun `10 digits with 8 prefix is PHONE`() =
        assertEquals(SearchMode.PHONE, detectSearchMode("89501234567"))

    @Test fun `formatted phone with spaces and dashes is PHONE`() =
        assertEquals(SearchMode.PHONE, detectSearchMode("+7 950 123-45-67"))

    @Test fun `6-digit short local phone still qualifies if formatted`() =
        // 3435-12-34 → 8 digits → phone
        assertEquals(SearchMode.PHONE, detectSearchMode("3435-12-34"))

    @Test fun `6 digits without formatting is PHONE`() =
        // §TZ-2.3.7: warehouse max 4 digits. 6 digits → digit_run=6 ≥ 5 → PHONE.
        assertEquals(SearchMode.PHONE, detectSearchMode("343512"))

    // ── WAREHOUSE ──────────────────────────────────────────────────────────

    @Test fun `pure digits 3-4 chars is WAREHOUSE`() =
        assertEquals(SearchMode.WAREHOUSE, detectSearchMode("1234"))

    @Test fun `short digits 1-3 chars is WAREHOUSE (early)`() =
        // §TZ-2.3.7: partial warehouse query — mode detected early, empty result
        // yields "Склад не найден" empty-state вместо пустого `Ничего не найдено`.
        assertEquals(SearchMode.WAREHOUSE, detectSearchMode("12"))

    @Test fun `1-3 digits followed by single cyrillic letter is WAREHOUSE`() =
        assertEquals(SearchMode.WAREHOUSE, detectSearchMode("123К"))

    @Test fun `1-3 digits followed by single latin letter is WAREHOUSE`() =
        assertEquals(SearchMode.WAREHOUSE, detectSearchMode("123A"))

    // ── NAME (fallthrough) ─────────────────────────────────────────────────

    @Test fun `russian full name is NAME`() =
        assertEquals(SearchMode.NAME, detectSearchMode("Иванов Иван"))

    @Test fun `single russian word is NAME`() =
        assertEquals(SearchMode.NAME, detectSearchMode("Иванов"))

    @Test fun `latin-only falls through to EMAIL by design`() =
        // Русскоязычная организация — латиница без @ = вероятно начало email.
        // «John Smith» → EMAIL. Для ФИО используется кириллица.
        assertEquals(SearchMode.EMAIL, detectSearchMode("John Smith"))

    @Test fun `empty string falls through to NAME`() =
        // performSearch guards against this in practice (onSearchQueryChanged
        // returns early on blank) but the pure detector still has to give
        // something sensible.
        assertEquals(SearchMode.NAME, detectSearchMode(""))

    @Test fun `single letter is NAME`() =
        // Not warehouse (warehouse requires letter followed by digits).
        assertEquals(SearchMode.NAME, detectSearchMode("А"))
}
