package com.example.otlhelper.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.PresencePaused
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.UnreadGreen
import com.example.otlhelper.core.theme.avatarColor

/**
 * Avatar with presence-dot overlay. Online dot pulses; paused amber + offline
 * grey are static. Used across news cards, chat bubbles, admin inbox rows,
 * and the admin conversation header.
 */
@Composable
internal fun UserAvatar(
    avatarUrl: String,
    name: String,
    presenceStatus: String = "offline",
    size: Dp = 36.dp
) {
    val initials = name.split(" ").filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
    val isOnline = presenceStatus == "online"
    val isPaused = presenceStatus == "paused"
    val dotSize = (size.value * 0.28f).dp
    val context = LocalContext.current

    val dotColor = when {
        isOnline -> UnreadGreen
        isPaused -> PresencePaused
        else -> Color(0xFF555555)
    }

    Box(modifier = Modifier.size(size)) {
        if (avatarUrl.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(avatarUrl).crossfade(true).build(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape)
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().clip(CircleShape).background(avatarColor(name)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials.ifBlank { "?" },
                    color = TextPrimary,
                    fontSize = (size.value * 0.38f).sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // §TZ-2.3.8 — presence-dot статичный. Раньше «pulse» anima-glow для
        // online юзера работала в Feed/Reactions/UserList — визуально «плавает»
        // даже когда юзер не смотрит на аватар. В чатах это тоже было, но менее
        // заметно т.к. avatar мелкий. Юзер попросил убрать pulse везде — индикатор
        // остаётся как статичная точка, цвет зависит от presence.
        Box(
            modifier = Modifier.align(Alignment.BottomEnd),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(dotColor)
                    .border(1.5.dp, BgCard, CircleShape)
            )
        }
    }
}
