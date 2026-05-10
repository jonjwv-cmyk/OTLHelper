package com.example.otlhelper.desktop.sap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.example.otlhelper.desktop.core.debug.DebugLogger
import com.example.otlhelper.desktop.data.network.ApiClient
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.BgApp
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * §0.11.10 — глобальный orchestrator Ctrl+Q flow.
 *
 * При hotkey: запускает detect → если 0/1/many → state-машина:
 *   • 0 → ошибка SAP not open
 *   • 1 → search автоматически
 *   • many → показать chooser → search после выбора
 * После search → показать Inspector с результатами.
 */
object SapInspectorController {
    private val _state = MutableStateFlow<SapInspectorState>(SapInspectorState.Hidden)
    val state: StateFlow<SapInspectorState> = _state.asStateFlow()

    fun trigger() {
        _state.value = SapInspectorState.Detecting
    }

    fun reset() {
        _state.value = SapInspectorState.Hidden
    }

    fun setState(s: SapInspectorState) {
        _state.value = s
    }
}

sealed class SapInspectorState {
    object Hidden : SapInspectorState()
    object Detecting : SapInspectorState()
    data class DetectError(val reason: String) : SapInspectorState()
    data class Choose(val docs: List<SapOpenDoc>) : SapInspectorState()
    data class Searching(val doc: SapOpenDoc) : SapInspectorState()
    data class Results(
        val doc: SapOpenDoc,
        val matches: List<SearchMatch>,
        val truncated: Boolean,
    ) : SapInspectorState()
    data class SearchError(val doc: SapOpenDoc, val reason: String) : SapInspectorState()
}

data class SearchMatch(
    val sheet: String,
    val row: Int,
    val headers: List<String>,
    val cells: List<String>,
)

@Composable
fun SapInspectorRoot() {
    val state by SapInspectorController.state.collectAsState()
    val scope = rememberCoroutineScope()

    // Detect → switch state
    LaunchedEffect(state) {
        when (val s = state) {
            is SapInspectorState.Detecting -> {
                val res = withContext(Dispatchers.IO) { SapDocumentDetector.detect() }
                when (res) {
                    is DetectResult.Success -> {
                        val docs = res.docs.filter { it.hasNumber }
                        when {
                            docs.isEmpty() -> SapInspectorController.setState(
                                SapInspectorState.DetectError("В SAP не открыт ни один заказ или поставка.\n\n" +
                                    "Откройте документ в SAP и попробуйте снова.")
                            )
                            docs.size == 1 -> {
                                SapInspectorController.setState(SapInspectorState.Searching(docs.first()))
                                runSearch(docs.first(), scope)
                            }
                            else -> SapInspectorController.setState(SapInspectorState.Choose(docs))
                        }
                    }
                    is DetectResult.SapNotRunning -> SapInspectorController.setState(
                        SapInspectorState.DetectError("SAP GUI не запущен.\n\nОткройте SAP Logon и войдите в систему.")
                    )
                    is DetectResult.ScriptingDisabled -> SapInspectorController.setState(
                        SapInspectorState.DetectError("SAP scripting выключен.\n\nВключите его: SAP Logon → Customize Local Layout → Options → Accessibility & Scripting → Scripting → Enable scripting.")
                    )
                    is DetectResult.InternalError -> SapInspectorController.setState(
                        SapInspectorState.DetectError("Внутренняя ошибка детектора SAP:\n${res.message}")
                    )
                }
            }
            is SapInspectorState.Searching -> {
                runSearch(s.doc, scope)
            }
            else -> {}
        }
    }

    when (val s = state) {
        is SapInspectorState.Hidden -> {}
        else -> SapInspectorDialog(s)
    }
}

private fun runSearch(doc: SapOpenDoc, scope: kotlinx.coroutines.CoroutineScope) {
    scope.launch(Dispatchers.IO) {
        try {
            val resp = ApiClient.request("search_sap_doc") {
                put("doc_number", doc.documentNumber)
                put("doc_type", doc.documentType.name)
            }
            if (!resp.optBoolean("ok", false)) {
                val err = resp.optString("error", "unknown")
                SapInspectorController.setState(SapInspectorState.SearchError(doc, "Сервер: $err"))
                return@launch
            }
            val arr = resp.optJSONArray("results")
            val matches = mutableListOf<SearchMatch>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    matches += SearchMatch(
                        sheet = r.optString("sheet", ""),
                        row = r.optInt("row", 0),
                        headers = jsonArrToStrings(r.optJSONArray("headers")),
                        cells = jsonArrToStrings(r.optJSONArray("cells")),
                    )
                }
            }
            val truncated = resp.optBoolean("truncated", false)
            SapInspectorController.setState(SapInspectorState.Results(doc, matches, truncated))
        } catch (e: Throwable) {
            DebugLogger.error("SAP_INSPECTOR", "search failed", e)
            SapInspectorController.setState(SapInspectorState.SearchError(doc, e.message ?: "сетевая ошибка"))
        }
    }
}

private fun jsonArrToStrings(arr: org.json.JSONArray?): List<String> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).map { arr.optString(it, "") }
}

@Composable
private fun SapInspectorDialog(state: SapInspectorState) {
    val dialogState = rememberDialogState(
        position = WindowPosition(Alignment.Center),
        size = DpSize(900.dp, 640.dp),
    )
    DialogWindow(
        onCloseRequest = { SapInspectorController.reset() },
        state = dialogState,
        title = "OTL — SAP документ в нашей таблице",
        resizable = true,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().background(BgApp).padding(16.dp),
        ) {
            when (state) {
                is SapInspectorState.Hidden -> {}
                is SapInspectorState.Detecting -> CenterSpinner("Читаем что у вас открыто в SAP…")
                is SapInspectorState.DetectError -> CenterError(state.reason)
                is SapInspectorState.Choose -> ChooserView(state.docs)
                is SapInspectorState.Searching -> CenterSpinner("Ищем «${state.doc.documentNumber}» в таблице…")
                is SapInspectorState.SearchError -> CenterError(
                    "Не удалось найти данные:\n${state.reason}",
                    refreshDoc = state.doc,
                )
                is SapInspectorState.Results -> ResultsView(state)
            }
        }
    }
}

@Composable
private fun CenterSpinner(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Accent, strokeWidth = 3.dp)
            Spacer(Modifier.height(16.dp))
            Text(text, color = TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun CenterError(text: String, refreshDoc: SapOpenDoc? = null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(40.dp),
        ) {
            Text(
                text,
                color = TextPrimary,
                fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (refreshDoc != null) {
                    Button(
                        onClick = { SapInspectorController.setState(SapInspectorState.Searching(refreshDoc)) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = BgCard,
                        ),
                    ) { Text("Повторить", fontWeight = FontWeight.SemiBold) }
                }
                Button(
                    onClick = { SapInspectorController.reset() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BgElevated,
                        contentColor = TextPrimary,
                    ),
                ) { Text("Закрыть") }
            }
        }
    }
}

@Composable
private fun ChooserView(docs: List<SapOpenDoc>) {
    Column {
        Text(
            "В SAP открыто несколько документов. Выберите какой искать:",
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(docs) { doc ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BgCard, RoundedCornerShape(8.dp))
                        .border(1.dp, BorderDivider, RoundedCornerShape(8.dp))
                        .clickable {
                            SapInspectorController.setState(SapInspectorState.Searching(doc))
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Column {
                        Text(doc.displayLabel, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        Text(doc.titleText.take(120), color = TextTertiary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultsView(state: SapInspectorState.Results) {
    val grouped = state.matches.groupBy { it.sheet }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.doc.displayLabel,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                val txt = if (state.matches.isEmpty()) {
                    "Не найдено в нашей таблице."
                } else {
                    "Найдено: ${state.matches.size} строк${if (state.truncated) " (показаны первые 500)" else ""}, на ${grouped.size} лист${ruEnding(grouped.size)}"
                }
                Text(txt, color = TextSecondary, fontSize = 12.sp)
            }
            TextButton(onClick = {
                // Refresh — повторно запустить detect+search
                SapInspectorController.setState(SapInspectorState.Searching(state.doc))
            }) { Text("↻ Обновить", color = Accent) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { SapInspectorController.reset() }) {
                Text("Закрыть", color = TextSecondary)
            }
        }
        Spacer(Modifier.height(12.dp))
        Divider(color = BorderDivider)
        Spacer(Modifier.height(12.dp))

        if (state.matches.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("В нашей таблице пусто по этому номеру", color = TextTertiary, fontSize = 13.sp)
            }
            return
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            grouped.forEach { (sheet, rows) ->
                item {
                    Text(
                        "📋 $sheet — ${rows.size} ${ruRowEnding(rows.size)}",
                        color = Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                item { TableView(rows) }
            }
        }
    }
}

@Composable
private fun TableView(rows: List<SearchMatch>) {
    val first = rows.firstOrNull() ?: return
    val headers = first.headers
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderDivider, RoundedCornerShape(6.dp))
            .background(BgCard, RoundedCornerShape(6.dp)),
    ) {
        Column(modifier = Modifier.horizontalScroll(scrollState)) {
            // Header row
            Row(
                modifier = Modifier
                    .background(BgElevated)
                    .padding(vertical = 6.dp, horizontal = 8.dp),
            ) {
                CellText("№ строки", true, 80.dp)
                headers.forEach { h -> CellText(h, true, 140.dp) }
            }
            Divider(color = BorderDivider)
            // Data rows
            rows.forEachIndexed { idx, m ->
                Row(modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)) {
                    CellText(m.row.toString(), false, 80.dp, color = TextTertiary)
                    m.cells.forEach { c -> CellText(c, false, 140.dp) }
                }
                if (idx < rows.size - 1) Divider(color = BorderDivider.copy(alpha = 0.3f))
            }
        }
    }
}

@Composable
private fun CellText(
    text: String,
    bold: Boolean,
    width: androidx.compose.ui.unit.Dp,
    color: Color = TextPrimary,
) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        fontFamily = if (bold) null else FontFamily.Monospace,
        modifier = Modifier.width(width).padding(horizontal = 4.dp),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun ruEnding(n: Int): String = when {
    n == 1 -> "е"
    n in 2..4 -> "ах"
    else -> "ах"
}

private fun ruRowEnding(n: Int): String = when {
    n == 1 -> "строка"
    n in 2..4 -> "строки"
    else -> "строк"
}
