package com.example.otlhelper.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.PresencePaused
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.UnreadGreen

// §TZ-DESKTOP 0.2.0 — 6 цветов с hue-spacing ≥60° (было 8 с конфликтами
// teal↔cyan, rose↔orange, и amber столкновением с брендовым Accent).
// Порядок hue: blue → purple → pink → orange → green → teal.
private val AvatarPalette = listOf(
    Color(0xFF4F7CCD),  // blue
    Color(0xFF9A5FC0),  // purple
    Color(0xFFCF5A82),  // pink
    Color(0xFFCC7340),  // warm-orange (отличается от Accent #D4A467 насыщенностью)
    Color(0xFF4D9E5C),  // green
    Color(0xFF3A9AB5),  // teal
)

fun avatarColor(name: String): Color {
    if (name.isBlank()) return Color(0xFF2A2A30)
    val hash = name.fold(7) { acc, c -> acc * 31 + c.code }
    return AvatarPalette[kotlin.math.abs(hash) % AvatarPalette.size]
}

/** §TZ-DESKTOP 0.2.0 — снапим fontSize к целым sp по таблице, без
 *  нецелочисленных значений (SF-шрифт плохо рендерит дробные размеры). */
private fun initialsFontSize(size: Dp): Int = when {
    size >= 72.dp -> 24
    size >= 56.dp -> 20
    size >= 44.dp -> 16
    size >= 36.dp -> 14
    size >= 28.dp -> 12
    else -> 10
}

private fun String.initials(): String =
    split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifEmpty { "?" }

/** §TZ-DESKTOP-0.1.0 — user avatar: если есть [avatarUrl] (с blob fragment
 *  `#k=&n=`) — грузим через NetworkImage с дешифровкой; иначе — цветной круг
 *  с инициалами. Плюс presence-dot (online/paused) в правом-нижнем углу. */
@Composable
fun UserAvatar(
    name: String,
    avatarUrl: String = "",
    presenceStatus: String = "offline",
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(size)) {
        if (avatarUrl.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(avatarColor(name)),
            ) {
                NetworkImage(
                    url = avatarUrl,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    useIntrinsicSize = false,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(avatarColor(name)),
                contentAlignment = Alignment.Center,
            ) {
                val fs = initialsFontSize(size)
                Text(
                    name.initials(),
                    color = TextPrimary,
                    // §TZ-DESKTOP 0.2.1 — точная центровка глифа в круге:
                    //  1. fontSize целое (раньше .34f → дробные sp)
                    //  2. lineHeight = fontSize → line-box = глиф
                    //  3. LineHeightStyle Center/Trim Both → остаток сверху/
                    //     снизу трим-ится, глиф геометрически центрирован
                    // На Android ещё пригодится includeFontPadding=false, но
                    // в Compose Desktop этого поля нет в PlatformTextStyle.
                    // Skia рендерит глиф «чисто», без extra padding.
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        fontSize = fs.sp,
                        lineHeight = fs.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both,
                        ),
                    ),
                )
            }
        }
        val dotColor = when (presenceStatus) {
            "online" -> UnreadGreen
            "paused" -> PresencePaused
            else -> null
        }
        if (dotColor != null) {
            val dotSize = (size.value * 0.28f).dp.coerceAtLeast(9.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(dotColor)
                    .border(1.5.dp, BgCard, CircleShape),
            )
        }
    }
}
