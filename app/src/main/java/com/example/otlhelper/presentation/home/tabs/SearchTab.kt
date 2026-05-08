package com.example.otlhelper.presentation.home.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Brush
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.AccentMuted
import com.example.otlhelper.core.theme.BrandAmber
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.AccentMuted
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.BgInput
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.ui.components.ThinDivider
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
import com.example.otlhelper.core.ui.MolRecordCard
import com.example.otlhelper.core.ui.WarehouseCard
import com.example.otlhelper.core.ui.WarehousePillBar
import com.example.otlhelper.core.ui.animations.AppMotion
import com.example.otlhelper.domain.model.MolRecord
import com.example.otlhelper.domain.model.hasRealWarehouse

@Composable
fun SearchTab(
    query: String,
    onQueryChanged: (String) -> Unit,
    results: List<MolRecord>,
    isLoading: Boolean,
    pinnedWarehouse: MolRecord?,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    pillExpanded: Boolean = false,
    onPillToggle: () -> Unit = {},
    searchMode: com.example.otlhelper.domain.model.SearchMode = com.example.otlhelper.domain.model.SearchMode.NAME,
    isAdminOrDev: Boolean = false,
    modifier: Modifier = Modifier
) {
    val lazyListState = listState

    // Pill складa видим когда первый warehouse-результат ушёл из viewport.
    // Цвет pill нейтрально-тёмный (WarehousePillBg = #181C1A), не BrandAmber —
    // никакой путаницы с «кнопкой отправки».
    val pillVisible by remember { derivedStateOf { lazyListState.firstVisibleItemIndex > 0 } }

    Column(modifier = modifier.fillMaxSize()) {
        // ── Search field ─────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgCard)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                placeholder = {
                    // §TZ-2.3.41 — увеличенная контрастность под солнцем:
                    // placeholder переведён с TextTertiary (#707078) на
                    // TextSecondary (#9A9AA0) — читается на ярком экране
                    // улицей без ухода в серый фон BgInput.
                    Text(
                        "Склад · ФИО · телефон · почта",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    // §TZ-2.3.41 — иконка поиска теперь TextSecondary в idle,
                    // Accent при активном вводе. Раньше идентичный серый цвет
                    // делал её незаметной на BgInput под солнцем.
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = if (query.isNotBlank()) Accent else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { onQueryChanged("") }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Очистить",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    // §TZ-2.3.41 — заметная рамка даже под солнцем:
                    //  • focused: Accent (warm bronze, не polу-muted)
                    //  • unfocused: BorderDivider (видимый контур),
                    //    раньше Transparent → под ярким светом поле
                    //    сливалось с кардом.
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = BorderDivider,
                    cursorColor = Accent,
                    focusedContainerColor = BgInput,
                    unfocusedContainerColor = BgInput
                )
            )
        }

        ThinDivider()

        // ── Content ──────────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f)) {
            when {
                query.isBlank() -> SearchIntroState()

                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp)
                }

                results.isEmpty() -> SearchEmptyState(mode = searchMode)

                else -> Box(Modifier.fillMaxSize()) {
                    // Pre-compute groups outside LazyListScope (not a Composable context)
                    val groups = remember(results, searchMode) {
                        if (searchMode != com.example.otlhelper.domain.model.SearchMode.WAREHOUSE)
                            groupByPerson(results)
                        else emptyList()
                    }
                    // §TZ-2.3.8 — scroll haptic в SEARCH results (как в NEWS/MONITORING).
                    val searchFeedback = com.example.otlhelper.core.feedback.LocalFeedback.current
                    val searchHost = androidx.compose.ui.platform.LocalView.current
                    androidx.compose.runtime.LaunchedEffect(lazyListState) {
                        androidx.compose.runtime.snapshotFlow { lazyListState.firstVisibleItemIndex }
                            .collect { _ ->
                                if (lazyListState.isScrollInProgress) searchFeedback?.tick(searchHost)
                            }
                    }
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (searchMode == com.example.otlhelper.domain.model.SearchMode.WAREHOUSE) {
                            // Warehouse search: warehouse card on top, contacts below
                            if (pinnedWarehouse != null) {
                                item(key = "pinned_warehouse_card") {
                                    WarehouseCard(
                                        record = pinnedWarehouse,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            // §TZ-2.3.7 — ключ включает index: сервер может вернуть
                            // две МОЛ-записи с одинаковыми (remoteId, fio) (разные
                            // склады для одного сотрудника) → дубликат ключа →
                            // LazyColumn крашит IllegalArgumentException. Index
                            // всегда уникален в пределах одного списка.
                            itemsIndexed(
                                results,
                                key = { idx, r -> "${r.remoteId}_${r.warehouseId}_$idx" },
                            ) { _, record ->
                                MolRecordCard(
                                    record = record,
                                    modifier = Modifier.fillMaxWidth(),
                                    searchMode = searchMode,
                                    isAdminOrDev = isAdminOrDev
                                )
                            }
                        } else {
                            // Name/phone/email: group by person, render one card per person
                            items(groups, key = { it.key }) { group ->
                                ContactGroupBlock(
                                    group = group,
                                    searchMode = searchMode,
                                    isAdminOrDev = isAdminOrDev,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    // §TZ-2.3.5+ — pill возвращена (нейтральный тёмный цвет
                    // WarehousePillBg, не Accent/BrandAmber). Раньше юзер путал
                    // её с «кнопкой отправки». Теперь визуально это явно
                    // sticky-header складa, не FAB.
                    if (pinnedWarehouse != null) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = pillVisible,
                            modifier = Modifier.align(Alignment.TopCenter),
                            enter = AppMotion.SlideInFromTop,
                            exit = AppMotion.SlideOutToTop
                        ) {
                            WarehousePillBar(
                                record = pinnedWarehouse,
                                expanded = pillExpanded,
                                onToggle = onPillToggle,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * §TZ-2.3.6 — empty-state по типу поиска:
 *   • Склад: «Склад не найден»
 *   • Телефон: «Телефон не найден»
 *   • E-mail: «E-mail не найден»
 *   • ФИО: «Сотрудник не найден»
 *
 * Единый визуальный язык — Lottie `otter_sad` + title + subtitle. Раньше был
 * простой иконка Search + generic «Ничего не найдено» — юзер не понимал какой
 * именно тип не найден.
 */
@Composable
private fun SearchEmptyState(mode: com.example.otlhelper.domain.model.SearchMode) {
    val (title, subtitle) = when (mode) {
        com.example.otlhelper.domain.model.SearchMode.WAREHOUSE ->
            "Склад не найден" to "Проверьте номер склада"
        com.example.otlhelper.domain.model.SearchMode.PHONE ->
            "Телефон не найден" to "Попробуйте другой номер"
        com.example.otlhelper.domain.model.SearchMode.EMAIL ->
            "E-mail не найден" to "Проверьте написание адреса"
        com.example.otlhelper.domain.model.SearchMode.NAME ->
            "Сотрудник не найден" to "Попробуйте другой запрос"
        com.example.otlhelper.domain.model.SearchMode.NONE ->
            "Ничего не найдено" to "Попробуйте другой запрос"
    }
    val composition by rememberLottieComposition(LottieCompositionSpec.Asset("otter_sad.json"))
    val progress by animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LottieAnimation(
                composition = composition,
                progress = { progress },
                modifier = Modifier.size(140.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(title, color = TextSecondary, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = TextTertiary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SearchIntroState() {
    val composition by rememberLottieComposition(LottieCompositionSpec.Asset("cat.json"))
    val progress by animateLottieCompositionAsState(
        composition,
        iterations = LottieConstants.IterateForever
    )

    // Дышащее тёплое сияние за котом — те же оттенки что и у splash-а:
    // outer (BrandAmber + AccentMuted) + inner (BrandAmber pale).
    val transition = rememberInfiniteTransition(label = "cat_halo")
    val haloScale by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "halo_scale"
    )
    val haloAlpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "halo_alpha"
    )

    // Stay vertically CENTERED in the available area. HomeScreen's root
    // Column already applies imePadding(), so when the keyboard slides up
    // this Box automatically shrinks and Arrangement.Center re-settles the
    // cat + caption to the midpoint between the search field and the
    // keyboard. No verticalScroll — the previous scrollable version felt
    // like "the content is floating up" because the scroll container
    // resized while the content stayed top-pinned. Centering is the
    // correct iOS/Linear-style behaviour.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Capture theme colors in composable scope before entering DrawScope lambdas
        val haloBrandAmber = BrandAmber
        val haloAccent = Accent
        val haloAccentMuted = AccentMuted
        Box(
            modifier = Modifier.size(220.dp),
            contentAlignment = Alignment.Center
        ) {
            // Outer glow — широкий warm-bronze halo
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = (size.minDimension / 2f) * haloScale
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to haloBrandAmber.copy(alpha = haloAlpha * 0.55f),
                            0.45f to haloAccent.copy(alpha = haloAlpha * 0.30f),
                            0.80f to haloAccentMuted.copy(alpha = haloAlpha * 0.10f),
                            1.00f to Color.Transparent
                        ),
                        radius = r
                    ),
                    radius = r
                )
            }
            // Inner core — компактное pale-amber свечение прямо под котом
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = (size.minDimension / 3.2f) * haloScale
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to haloBrandAmber.copy(alpha = haloAlpha * 0.45f),
                            0.55f to haloBrandAmber.copy(alpha = haloAlpha * 0.18f),
                            1.00f to Color.Transparent
                        ),
                        radius = r
                    ),
                    radius = r
                )
            }
            LottieAnimation(
                composition = composition,
                progress = { progress },
                modifier = Modifier.size(180.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Что ищем сегодня?",
            color = TextPrimary,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
    }
}

// ── Contact grouping for NAME/PHONE/EMAIL search ─────────────────────────────

/**
 * One contact = one person. For a given person we may have multiple records
 * (one per warehouse assignment). Group them so the UI shows ONE contact card
 * plus a collapsible list of warehouse pills — not duplicate cards.
 */
private data class ContactGroup(
    val key: String,
    val person: MolRecord,
    val warehouses: List<MolRecord>
)

private fun groupByPerson(results: List<MolRecord>): List<ContactGroup> {
    val groups = linkedMapOf<String, MutableList<MolRecord>>()
    for (r in results) {
        // Group by FIO + mobile — stable identifier for same person
        val key = "${r.fio.trim().lowercase()}|${r.mobile.trim()}"
        groups.getOrPut(key) { mutableListOf() }.add(r)
    }
    return groups.map { (k, records) ->
        ContactGroup(
            key = k,
            person = records.first(),
            // Drop legacy "МОЛ" marker rows — they aren't real warehouses
            warehouses = records.filter { it.hasRealWarehouse() }
        )
    }
}

@Composable
private fun ContactGroupBlock(
    group: ContactGroup,
    searchMode: com.example.otlhelper.domain.model.SearchMode,
    isAdminOrDev: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Warehouse pills — collapsed by default, one per warehouse this person owns
        for (wh in group.warehouses) {
            ContactWarehousePill(record = wh, modifier = Modifier.fillMaxWidth())
        }
        // Contact card — shows warehouse IDs in the expandable detail section
        MolRecordCard(
            record = group.person,
            modifier = Modifier.fillMaxWidth(),
            searchMode = searchMode,
            isAdminOrDev = isAdminOrDev,
            warehouseIds = group.warehouses.map { it.warehouseId }.filter { it.isNotBlank() }
        )
    }
}

/** Collapsible warehouse pill — click toggles between title-only and full body. */
@Composable
private fun ContactWarehousePill(record: MolRecord, modifier: Modifier = Modifier) {
    var expanded by remember(record.remoteId) { mutableStateOf(false) }
    WarehousePillBar(
        record = record,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier
    )
}
