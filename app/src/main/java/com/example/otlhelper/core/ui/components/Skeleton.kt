package com.example.otlhelper.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.BgElevated
import com.example.otlhelper.core.theme.BubbleOther
import com.example.otlhelper.core.theme.BubbleOwn

/**
 * Shimmer brush — animated gradient that sweeps left-to-right across the
 * rectangle. Apply as `background(brush = rememberShimmerBrush())`.
 *
 * Used for skeleton placeholders that hint at upcoming layout while the first
 * batch of data is loading (no harsh spinner, no layout pop-in).
 */
@Composable
fun rememberShimmerBrush(
    base: Color = BgElevated,
    highlight: Color = Color(0xFF3A3A3A),
    durationMs: Int = 1100
): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate by transition.animateFloat(
        initialValue = -600f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(translate, 0f),
        end = Offset(translate + 400f, 0f)
    )
}

/** Skeleton block — animated shimmer rectangle with corner radius. */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(brush = rememberShimmerBrush())
    )
}

/** Skeleton circle — for avatars. */
@Composable
fun SkeletonCircle(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(brush = rememberShimmerBrush())
    )
}

// ── Domain skeletons ─────────────────────────────────────────────────────────

/** News card placeholder — header + 3 lines of body. */
@Composable
fun NewsCardSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BgCard)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkeletonCircle(32.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                SkeletonBlock(Modifier.height(11.dp).width(120.dp))
                Spacer(Modifier.height(5.dp))
                SkeletonBlock(Modifier.height(9.dp).width(70.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        SkeletonBlock(Modifier.height(13.dp).fillMaxWidth())
        Spacer(Modifier.height(7.dp))
        SkeletonBlock(Modifier.height(13.dp).fillMaxWidth(0.92f))
        Spacer(Modifier.height(7.dp))
        SkeletonBlock(Modifier.height(13.dp).fillMaxWidth(0.6f))
    }
}

/** Chat bubble placeholder — alternating own/other to feel realistic. */
@Composable
fun ChatBubbleSkeleton(modifier: Modifier = Modifier, isOwn: Boolean = false) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isOwn) {
            SkeletonCircle(32.dp)
            Spacer(Modifier.width(6.dp))
        }
        Column {
            if (!isOwn) {
                SkeletonBlock(Modifier.height(10.dp).width(80.dp).padding(horizontal = 4.dp))
                Spacer(Modifier.height(4.dp))
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isOwn) BubbleOwn else BubbleOther)
                    .padding(12.dp)
            ) {
                Column {
                    SkeletonBlock(Modifier.height(11.dp).width(if (isOwn) 140.dp else 180.dp))
                    Spacer(Modifier.height(6.dp))
                    SkeletonBlock(Modifier.height(11.dp).width(if (isOwn) 100.dp else 120.dp))
                }
            }
        }
        if (isOwn) Spacer(Modifier.width(38.dp))
    }
}

/** Inbox/contact row placeholder — avatar + name + last message. */
@Composable
fun ContactRowSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BgCard)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SkeletonCircle(44.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            SkeletonBlock(Modifier.height(13.dp).width(140.dp))
            Spacer(Modifier.height(6.dp))
            SkeletonBlock(Modifier.height(11.dp).fillMaxWidth(0.7f))
        }
    }
}

// ── Screen-level skeleton lists ──────────────────────────────────────────────

@Composable
fun NewsListSkeleton(modifier: Modifier = Modifier, count: Int = 4) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(count) { NewsCardSkeleton() }
    }
}

@Composable
fun ChatListSkeleton(modifier: Modifier = Modifier, count: Int = 6) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (i in 0 until count) {
            ChatBubbleSkeleton(isOwn = (i % 2 == 1))
        }
    }
}

@Composable
fun ContactListSkeleton(modifier: Modifier = Modifier, count: Int = 5) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(count) { ContactRowSkeleton() }
    }
}
