package com.example.otlhelper.presentation.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.AvatarBg
import com.example.otlhelper.core.theme.BgApp
import com.example.otlhelper.core.theme.BgElevated
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.theme.StatusError
import com.example.otlhelper.core.theme.StatusErrorBorder
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
import com.example.otlhelper.core.ui.components.DialogDragHandle
import com.example.otlhelper.presentation.home.HomeViewModel
import com.example.otlhelper.presentation.home.dialogs.AvatarPickerDialog

/**
 * Account — big avatar (tap → AvatarPickerDialog), name / role / login,
 * change-password CTA. Lives in a ModalBottomSheet so the navigation feel
 * matches every other menu-level action (settings, system control, audit,
 * user management). Back press / outside tap = dismiss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountScreen(
    viewModel: HomeViewModel,
    onDismiss: () -> Unit,
    onChangePassword: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val fullName = viewModel.getFullName().ifBlank { "Без имени" }
    val login = viewModel.getLogin()
    val role = viewModel.getRoleLabel()
    val initials = fullName.split(" ").filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
    val avatarUrl = state.avatarUrl
    val context = LocalContext.current

    var showAvatarPicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgElevated,
        dragHandle = { DialogDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))

            // Outer box is NOT clipped — the camera badge sits on the
            // bottom-right corner OUTSIDE the inscribed avatar circle, so
            // clipping the whole thing to CircleShape would shear it.
            Box(
                modifier = Modifier.size(120.dp),
                contentAlignment = Alignment.Center
            ) {
                // Clipped avatar layer — takes the inner 112dp circle.
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .clip(CircleShape)
                        .background(AvatarBg)
                        .clickable { if (!state.avatarUploading) showAvatarPicker = true },
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarUrl.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(avatarUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = fullName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                    } else {
                        Text(
                            initials.ifBlank { "?" },
                            color = TextPrimary,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (state.avatarUploading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(Color(0xCC000000)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = TextPrimary,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }

                // Camera badge — on the outer box (not clipped by the
                // avatar's CircleShape). Slight offset tucks it onto the
                // avatar's edge without protruding into the sheet padding.
                if (!state.avatarUploading) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = (-4).dp, y = (-4).dp)
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Accent)
                            .clickable { showAvatarPicker = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.CameraAlt,
                            contentDescription = "Изменить фото",
                            tint = BgApp,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(fullName, color = TextPrimary, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(role, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Text(login, color = TextTertiary, style = MaterialTheme.typography.labelSmall)

            if (avatarUrl.isBlank() && !state.avatarUploading) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "Нажмите на круг, чтобы добавить фото",
                    color = TextTertiary,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (state.avatarUploadError.isNotBlank() && !state.avatarUploading) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = StatusError.copy(alpha = 0.25f),
                    contentColor = StatusErrorBorder
                ) {
                    Text(
                        "Ошибка загрузки — попробуйте ещё раз",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            OutlinedButton(
                onClick = onChangePassword,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(0.5.dp, BorderDivider),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) {
                Icon(
                    Icons.Outlined.Key,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = TextSecondary
                )
                Spacer(Modifier.width(10.dp))
                Text("Сменить пароль", style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    if (showAvatarPicker) {
        AvatarPickerDialog(
            onDismiss = { showAvatarPicker = false },
            onAvatarPicked = { bytes, mime, name ->
                showAvatarPicker = false
                viewModel.uploadAvatar(bytes, mime, name)
            }
        )
    }
}
