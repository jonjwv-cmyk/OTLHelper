package com.example.otlhelper.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.otlhelper.core.theme.*
import com.example.otlhelper.core.ui.components.DialogDragHandle
import com.example.otlhelper.domain.model.FeedDisplayType
import com.example.otlhelper.domain.model.FeedItemView
import org.json.JSONObject

/**
 * Шторка (ModalBottomSheet) с полным содержимым закреплённого элемента (§3.5).
 *
 * Рендерит знакомую `NewsCard` / `PollCard` внутри — UX консистентен с лентой.
 * Для admin/developer снизу появляется кнопка «Открепить» (при [FeedItemView.canPin]).
 * ⋮-меню карточки в шторке делегирует [onOpenStats] — родитель открывает нужный
 * stats-диалог (news readers / poll stats), шторка закрывается.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinnedItemSheet(
    item: JSONObject,
    view: FeedItemView,
    myLogin: String,
    onDismiss: () -> Unit,
    onPollVoteConfirm: (List<Long>) -> Unit,
    onOpenStats: () -> Unit,
    onUnpinClick: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BgElevated,
        dragHandle = { DialogDragHandle() }
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
            val stableItem = remember(item) { StableJsonItem(item) }
            when (view.displayType) {
                FeedDisplayType.POLL -> PollCard(
                    item = stableItem,
                    view = view,
                    myLogin = myLogin,
                    onVoteConfirm = onPollVoteConfirm,
                    onLongClick = {},
                    onOverflowClick = onOpenStats,
                    modifier = Modifier.fillMaxWidth()
                )
                FeedDisplayType.NEWS,
                FeedDisplayType.UNKNOWN -> NewsCard(
                    item = stableItem,
                    view = view,
                    myLogin = myLogin,
                    onOverflowClick = onOpenStats,
                    // §TZ-2.3.38 — в раскрытом закреплённом прячем pinned-pill:
                    // он дублирует сам факт открытия через pinned-row и
                    // обрезается клавиатурой на узких экранах.
                    hidePinnedBadge = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (view.canPin) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onUnpinClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BgCard,
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Открепить", fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
