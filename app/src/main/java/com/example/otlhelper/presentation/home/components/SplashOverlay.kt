package com.example.otlhelper.presentation.home.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.BuildConfig
import com.example.otlhelper.core.theme.BgApp
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
import com.example.otlhelper.core.ui.BrandSplashMark

/**
 * Branded splash overlay — warm dual glow + pin-drop animation (BrandSplashMark)
 * + version footer. Shown over the main UI while the first load resolves.
 */
@Composable
internal fun SplashOverlay(status: String) {
    Box(modifier = Modifier.fillMaxSize().background(BgApp)) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = (-36).dp, y = (-30).dp)
                .size(260.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x66F57621),
                            Color(0x1AF57621),
                            Color.Transparent
                        ),
                        radius = 340f
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = 50.dp, y = 6.dp)
                .size(220.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x52F7C657),
                            Color(0x16F7C657),
                            Color.Transparent
                        ),
                        radius = 300f
                    )
                )
        )

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BrandSplashMark()
            Spacer(Modifier.height(18.dp))
            Text(
                "OTL Helper",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Spacer(Modifier.height(10.dp))
            AnimatedContent(
                targetState = status,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220))
                        .togetherWith(fadeOut(animationSpec = tween(160)))
                },
                label = "splash_status"
            ) { s ->
                Text(s, color = TextSecondary, fontSize = 13.sp)
            }
        }

        // Version footer — navigationBarsPadding keeps the text clear of
        // the gesture bar / 3-button nav on edge-to-edge devices.
        Text(
            "v${BuildConfig.VERSION_NAME}",
            color = TextTertiary,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        )
    }
}
