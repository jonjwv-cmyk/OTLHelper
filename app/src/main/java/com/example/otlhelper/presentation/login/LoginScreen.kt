package com.example.otlhelper.presentation.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.otlhelper.core.ui.components.AppButton
import com.example.otlhelper.core.ui.components.AppButtonState
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.otlhelper.core.theme.*

@Composable
fun LoginScreen(
    onNavigateToHome: () -> Unit,
    onNavigateToChangePassword: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.navigateTo) {
        when (state.navigateTo) {
            LoginNavTarget.Home -> { viewModel.clearNavTarget(); onNavigateToHome() }
            LoginNavTarget.ChangePassword -> { viewModel.clearNavTarget(); onNavigateToChangePassword() }
            LoginNavTarget.None -> {}
        }
    }

    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgApp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo / title area
            Text("OTL Helper", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Войдите в аккаунт", color = TextSecondary, fontSize = 15.sp)
            Spacer(Modifier.height(40.dp))

            // Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = BgCard,
                tonalElevation = 0.dp
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // §0.11.3 — autofill hints через semantics. Google Smart
                    // Lock / Password Manager увидит ContentType.Username +
                    // ContentType.Password → предложит сохранить creds после
                    // успешного login (как Telegram/WhatsApp/etc).
                    OtlTextField(
                        value = login,
                        onValueChange = { login = it },
                        label = "Логин",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                        modifier = Modifier.semantics {
                            contentType = ContentType.Username
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                    OtlTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Пароль",
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            viewModel.login(login, password)
                        }),
                        modifier = Modifier.semantics {
                            contentType = ContentType.Password
                        },
                    )
                    Spacer(Modifier.height(8.dp))

                    AnimatedVisibility(visible = state.error.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                        Text(state.error, color = StatusErrorBorder, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
                    }

                    // Mandatory update прямо на экране логина: если сервер отверг
                    // с `app_version_too_old`, сразу даём кнопку «Скачать
                    // обновление» — иначе юзер без аккаунта в замкнутом круге
                    // (не может зайти, чтобы получить push, чтобы обновиться).
                    var showUpdateDialog by remember { mutableStateOf(false) }
                    if (state.mandatoryUpdateUrl.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { showUpdateDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent,
                                contentColor = androidx.compose.ui.graphics.Color.Black,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                "Скачать обновление" + if (state.mandatoryUpdateMinVersion.isNotBlank())
                                    " · v${state.mandatoryUpdateMinVersion}" else "",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                            )
                        }
                    }
                    if (showUpdateDialog && state.mandatoryUpdateUrl.isNotBlank()) {
                        com.example.otlhelper.presentation.home.dialogs.SoftUpdateDialog(
                            version = state.mandatoryUpdateMinVersion,
                            url = state.mandatoryUpdateUrl,
                            onDismiss = { showUpdateDialog = false },
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Drive AppButton state from VM's loading/error flags.
                    // Error flashes for 1.6s then reverts to Idle so user can retry.
                    var transientError by remember { mutableStateOf(false) }
                    LaunchedEffect(state.error) {
                        if (state.error.isNotBlank() && !state.isLoading) {
                            transientError = true
                            delay(1600)
                            transientError = false
                        }
                    }
                    val btnState = when {
                        state.isLoading -> AppButtonState.Loading
                        transientError -> AppButtonState.Error
                        else -> AppButtonState.Idle
                    }
                    AppButton(
                        text = "Войти",
                        onClick = { viewModel.login(login, password) },
                        state = btnState,
                        enabled = login.isNotBlank() && password.isNotBlank()
                    )
                }
            }
        }
    }
}

@Composable
private fun OtlTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TextSecondary) },
        modifier = Modifier.fillMaxWidth().then(modifier),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedBorderColor = Accent,
            unfocusedBorderColor = BorderDivider,
            cursorColor = Accent,
            focusedContainerColor = BgInput,
            unfocusedContainerColor = BgInput
        )
    )
}
