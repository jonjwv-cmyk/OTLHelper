package com.example.otlhelper.presentation.home.dialogs.user_management

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.BgElevated
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.UnreadGreen
import com.example.otlhelper.core.ui.components.DialogDragHandle
import com.example.otlhelper.presentation.home.HomeViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserManagementDialog(
    viewModel: HomeViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateForm by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    // Spinner only on the very first fetch — subsequent 5s polls reuse the
    // already-visible list so the presence dots / names do not "reset"
    // (blink to spinner, blink back). Same pattern as hasLoadedOnce in tabs.
    var hasLoadedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(state.usersLoading) { if (!state.usersLoading) hasLoadedOnce = true }

    LaunchedEffect(Unit) { viewModel.loadUsersList() }
    // Quiet 5s refresh while the dialog is open so presence dots update
    // almost instantly without manual pull-to-refresh.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000L)
            viewModel.loadUsersList()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgElevated,
        dragHandle = { DialogDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .fillMaxWidth()
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Пользователи",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showCreateForm = !showCreateForm }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Add, null, tint = TextSecondary)
                }
            }

            if (statusMessage.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(statusMessage, color = UnreadGreen, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(12.dp))

            if (showCreateForm) {
                CreateUserForm(
                    onSubmit = { login, name, pass, role, mustChange ->
                        viewModel.createUserAdmin(login, name, pass, role, mustChange) { ok, err ->
                            if (ok) {
                                statusMessage = "Пользователь создан"
                                showCreateForm = false
                                viewModel.loadUsersList()
                            } else {
                                statusMessage = err
                            }
                        }
                    },
                    onCancel = { showCreateForm = false },
                    // §TZ-2.3.38 — передаём роль текущего пользователя, чтобы
                    // форма показала расширенный role-picker для developer'а.
                    myRole = viewModel.role,
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = BorderDivider)
                Spacer(Modifier.height(12.dp))
            }

            if (state.usersLoading && !hasLoadedOnce) {
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp)
                }
            } else if (state.usersList.isEmpty() && hasLoadedOnce) {
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    Text("Нет пользователей", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            } else if (state.usersList.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 500.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(state.usersList, key = { index, user ->
                        val login = user.optString("login", "").ifBlank { "u$index" }
                        "$login#$index"
                    }) { _, user ->
                        UserRow(
                            user = user,
                            viewModel = viewModel,
                            onStatusChange = { msg ->
                                statusMessage = msg
                                viewModel.loadUsersList()
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BgCard, contentColor = TextSecondary),
                shape = RoundedCornerShape(12.dp)
            ) { Text("Закрыть") }
        }
    }
}
