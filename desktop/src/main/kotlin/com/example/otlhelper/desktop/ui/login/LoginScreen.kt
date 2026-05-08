package com.example.otlhelper.desktop.ui.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.core.session.QrRenderer
import com.example.otlhelper.desktop.data.network.ApiClient
import com.example.otlhelper.desktop.data.security.blobAwareUrl
import com.example.otlhelper.desktop.data.session.SessionStore
import com.example.otlhelper.desktop.model.Role
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.BgApp
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BgInput
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * §TZ-0.10.5 — переписанный LoginScreen.
 *
 * Default mode: QR — показывает 320x320 QR-код, polls every 2s до redeem.
 * Fallback: "Войти по паролю" — обычный login/password + counter '0/3'.
 *   После успешного password login server создаёт сессию с computed expiry
 *   (work-window OR now+30min) — UI просто переходит в HomeScreen.
 *   Если за пределами рабочего окна — server даёт +30 мин, но юзер дальше
 *   живёт через extension'ы.
 *
 * §TZ-DESKTOP-0.10.0 — все old-style логин (action='login') удалили: теперь
 * ВСЕГДА через QR или password_login_pc. После релиза 0.10.5 сервер всё ещё
 * принимает старый login (для backward-compat юзеров < 0.10.5), но новый
 * desktop никогда не использует.
 */
private val APP_VERSION = com.example.otlhelper.desktop.BuildInfo.VERSION

private enum class LoginMode { QR, PASSWORD }

@Composable
fun LoginScreen(onLoggedIn: (login: String, role: Role, fullName: String, avatarUrl: String) -> Unit) {
    var mode by remember { mutableStateOf(LoginMode.QR) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier.fillMaxSize().background(BgApp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(BgCard)
                .border(0.5.dp, BorderDivider, RoundedCornerShape(18.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource("icon.png"),
                contentDescription = "OTLD Helper",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)),
            )
            Spacer(Modifier.height(10.dp))
            Text("OTLD Helper", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(20.dp))

            when (mode) {
                LoginMode.QR -> QrLoginPanel(onLoggedIn = onLoggedIn, scope = scope)
                LoginMode.PASSWORD -> PasswordLoginPanel(onLoggedIn = onLoggedIn, scope = scope)
            }

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = {
                mode = if (mode == LoginMode.QR) LoginMode.PASSWORD else LoginMode.QR
            }) {
                Text(
                    if (mode == LoginMode.QR) "Войти по паролю" else "Вернуться к QR",
                    color = TextSecondary, fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text("v$APP_VERSION", color = TextTertiary, fontSize = 11.sp)
        }
    }
}

// ── QR Login mode ──────────────────────────────────────────────────────────

@Composable
private fun QrLoginPanel(
    onLoggedIn: (login: String, role: Role, fullName: String, avatarUrl: String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var qrPayload by remember { mutableStateOf<String?>(null) }
    var challenge by remember { mutableStateOf("") }
    var ttlSec by remember { mutableStateOf(60) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    // Генерация и polling.
    LaunchedEffect(Unit) {
        while (true) {
            try {
                loading = true
                val deviceLabel = (System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: "PC").take(40)
                val os = if (System.getProperty("os.name")?.lowercase()?.contains("mac") == true) "mac" else "win"
                val resp = ApiClient.requestPcSessionQr(deviceLabel, os)
                if (resp.optBoolean("ok", false)) {
                    qrPayload = resp.optString("qr_payload")
                    challenge = resp.optString("challenge")
                    ttlSec = resp.optInt("ttl_sec", 60)
                    error = ""
                    loading = false
                } else {
                    error = "Ошибка QR: ${resp.optString("error", "unknown")}"
                    loading = false
                    delay(5_000); continue
                }
            } catch (e: Exception) {
                error = "Нет связи: ${e.message ?: e.javaClass.simpleName}"
                loading = false
                delay(5_000); continue
            }

            // Poll status каждые 2 сек, пока ttl не истечёт.
            val deadline = System.currentTimeMillis() + ttlSec * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(2_000)
                try {
                    val s = ApiClient.checkPcSessionStatus(challenge)
                    val status = s.optString("status")
                    if (status == "redeemed") {
                        val sess = s.optJSONObject("session") ?: continue
                        val token = sess.optString("token")
                        val login = sess.optString("login")
                        val role = Role.fromServer(sess.optString("role"))
                        val fullName = sess.optString("full_name")
                        val avatarUrl = sess.optString("avatar_url")
                        val expiresAt = sess.optString("expires_at")
                        val deviceId = SessionStore.ensureDeviceId()
                        val os = if (System.getProperty("os.name")?.lowercase()?.contains("mac") == true) "mac" else "win"
                        ApiClient.setToken(token)
                        SessionStore.save(
                            SessionStore.Session(
                                login = login,
                                role = role,
                                token = token,
                                deviceId = deviceId,
                                expiresAt = expiresAt,
                                os = os,
                                fullName = fullName,
                                avatarUrl = avatarUrl,
                            )
                        )
                        onLoggedIn(login, role, fullName, avatarUrl)
                        return@LaunchedEffect
                    }
                    if (status == "expired" || s.optString("error") == "qr_expired") break
                } catch (_: Exception) {
                    // network blip — продолжаем
                }
            }
            // TTL вышел — генерим заново.
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(280.dp).clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1A1A1C)),
            contentAlignment = Alignment.Center,
        ) {
            val payload = qrPayload
            if (loading || payload == null) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            } else {
                val bitmap = remember(payload) { QrRenderer.render(payload, sizePx = 320) }
                Image(
                    painter = BitmapPainter(bitmap),
                    contentDescription = "QR",
                    modifier = Modifier.size(260.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Отсканируйте через Android-приложение",
            color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        )
        Text(
            "Меню → «Войти на ПК»",
            color = TextSecondary, fontSize = 12.sp,
        )
        if (error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = Color(0xFFEF4444), fontSize = 12.sp)
        }
    }
}

// ── Password fallback mode ─────────────────────────────────────────────────

@Composable
private fun PasswordLoginPanel(
    onLoggedIn: (login: String, role: Role, fullName: String, avatarUrl: String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var counterUsed by remember { mutableStateOf(0) }
    var counterLimit by remember { mutableStateOf(3) }

    fun mapError(raw: String): String = when (raw) {
        "user_not_found" -> "Пользователь не найден"
        "user_inactive" -> "Пользователь деактивирован"
        "user_suspended" -> "Пользователь заблокирован"
        "wrong_password" -> "Неверный пароль"
        "desktop_role_forbidden" -> "Desktop-версия только для администраторов и разработчиков"
        "password_login_weekly_limit" -> "Превышен лимит парольных входов на этой неделе. Попросите разработчика сбросить."
        else -> "Ошибка входа: $raw"
    }

    suspend fun loadCounter(forLogin: String) {
        try {
            val resp = ApiClient.getPasswordCounter(forLogin)
            if (resp.optBoolean("ok", false)) {
                counterUsed = resp.optInt("used", 0)
                counterLimit = resp.optInt("limit", 3)
            }
        } catch (_: Exception) { /* ignore */ }
    }

    fun submit() {
        if (loading) return
        if (login.isBlank() || password.isBlank()) {
            error = "Укажи логин и пароль"; return
        }
        error = ""; loading = true
        scope.launch {
            try {
                val deviceLabel = (System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: "PC").take(40)
                val os = if (System.getProperty("os.name")?.lowercase()?.contains("mac") == true) "mac" else "win"
                val resp = ApiClient.passwordLoginPc(
                    login = login.trim(),
                    password = password,
                    deviceLabel = deviceLabel,
                    desktopOs = os,
                )
                if (resp.optBoolean("ok", false)) {
                    val token = resp.optString("token")
                    val expiresAt = resp.optString("expires_at")
                    val user = resp.optJSONObject("user")
                    val serverLogin = user?.optString("login").orEmpty().ifBlank { login.trim() }
                    val serverRole = Role.fromServer(user?.optString("role").orEmpty())
                    val fullName = user?.optString("full_name").orEmpty()
                    val avatarUrl = if (user != null) blobAwareUrl(user, "avatar_url") else ""
                    val deviceId = SessionStore.ensureDeviceId()
                    ApiClient.setToken(token)
                    SessionStore.save(
                        SessionStore.Session(
                            login = serverLogin,
                            role = serverRole,
                            token = token,
                            deviceId = deviceId,
                            expiresAt = expiresAt,
                            os = os,
                            fullName = fullName,
                            avatarUrl = avatarUrl,
                        )
                    )
                    val pc = resp.optJSONObject("password_counter")
                    if (pc != null) {
                        counterUsed = pc.optInt("used", counterUsed)
                        counterLimit = pc.optInt("limit", counterLimit)
                    }
                    onLoggedIn(serverLogin, serverRole, fullName, avatarUrl)
                } else {
                    error = mapError(resp.optString("error", ""))
                    if (login.isNotBlank()) loadCounter(login.trim())
                }
            } catch (e: Exception) {
                error = "Нет связи: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                loading = false
            }
        }
    }

    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = BgInput,
        unfocusedContainerColor = BgInput,
        disabledContainerColor = BgInput,
        focusedIndicatorColor = Accent,
        unfocusedIndicatorColor = BorderDivider,
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        focusedLabelColor = TextSecondary,
        unfocusedLabelColor = TextSecondary,
        cursorColor = Accent,
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OutlinedTextField(
            value = login,
            onValueChange = {
                login = it; error = ""
                if (it.isNotBlank()) {
                    scope.launch { loadCounter(it.trim()) }
                }
            },
            label = { Text("Логин") },
            singleLine = true,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = "" },
            label = { Text("Пароль") },
            singleLine = true,
            enabled = !loading,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Использовано: $counterUsed/$counterLimit на этой неделе",
                color = TextTertiary, fontSize = 11.sp,
            )
        }
        if (error.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(error, color = Color(0xFFEF4444), fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { submit() },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgApp),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(color = BgApp, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Text("Войти", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}
