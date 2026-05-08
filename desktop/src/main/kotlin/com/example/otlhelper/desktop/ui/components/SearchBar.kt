package com.example.otlhelper.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BgInput
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.Space
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary

/**
 * §TZ-DESKTOP 0.3.2 — inline-SearchBar для фильтрации текущего списка
 * (чаты или новости). Появляется над tab-контентом когда юзер жмёт на
 * иконку поиска в [PanelToggleBar] или ⌘F. Filter применяется к списку
 * ПО CLIENT-СТОРОНЕ (WorkspacePanel делает state.copy(rows=filtered)) —
 * никаких серверных запросов не генерируется.
 *
 * UX:
 *   • auto-focus TextField на появлении
 *   • X-кнопка справа — очищает и закрывает
 *   • плейсхолдер "Поиск..." пока поле пустое
 *
 * Не зависит от конкретного source-объекта — просто forwards onChange. Где
 * и как фильтровать — решает caller.
 */
@Composable
fun SearchBar(
    query: String,
    onChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Поиск...",
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(BgCard)
            .padding(horizontal = Space.sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(BgInput)
                .border(0.5.dp, BorderDivider, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(Space.xs))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(placeholder, color = TextTertiary, fontSize = 13.sp)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(Accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Закрыть поиск",
                tint = TextSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
