package com.example.otlhelper.presentation.pc_login

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.otlhelper.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * §TZ-2.5.6 — Telegram-style QR scanner. ML Kit + CameraX через [MlKitQrScanner].
 *
 * Развилка:
 *   1. Не имеем CAMERA permission → rationale + кнопка "Разрешить".
 *   2. Permission granted → fullscreen camera preview (без рамок/линий).
 *   3. ML Kit detect QR → парсим payload → redeem_qr_token → success.
 *   4. Success → текст "Сессия активна, до X:XX UTC" + Закрыть.
 */
@Composable
fun PcLoginScannerScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("") }
    var success by remember { mutableStateOf(false) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var resetKey by remember { mutableStateOf(0) }  // увеличить → пересоздать scanner

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        permissionDenied = !granted
    }

    fun handleQr(payload: String) {
        if (processing || success) return
        processing = true
        scope.launch {
            status = "Проверка QR…"
            try {
                val obj = withContext(Dispatchers.Default) { JSONObject(payload) }
                val type = obj.optString("type")
                val endpoint = obj.optString("endpoint")
                val challenge = obj.optString("challenge")
                if (type != "pc_login" || challenge.isBlank()) {
                    status = "QR неверного формата"
                    processing = false
                    return@launch
                }
                if (!endpoint.startsWith("https://api.otlhelper.com")) {
                    status = "Чужой сервер: $endpoint"
                    processing = false
                    return@launch
                }
                status = "Привязываю PC к вашему аккаунту…"
                val resp = withContext(Dispatchers.IO) { ApiClient.redeemQrToken(challenge) }
                if (resp.optBoolean("ok", false)) {
                    val s = resp.optJSONObject("session")
                    val until = s?.optString("expires_at")?.takeLast(8)?.dropLast(3) ?: ""
                    val device = s?.optString("device_label") ?: ""
                    status = "✅ PC сессия активна${if (device.isNotBlank()) " ($device)" else ""}.\nДо $until UTC."
                    success = true
                } else {
                    status = "Ошибка: ${resp.optString("error", "unknown")}"
                    processing = false
                }
            } catch (e: Exception) {
                status = "Сбой: ${e.message ?: e.javaClass.simpleName}"
                processing = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            success -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "Войти на ПК",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(status, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("Закрыть") }
                }
            }
            !hasPermission -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "Войти на ПК",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Для сканирования QR-кода нужен доступ к камере",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                        fontSize = 14.sp,
                    )
                    if (permissionDenied) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Разрешение отклонено. Откройте Настройки → Приложения → " +
                                "OTLHelper → Разрешения → Камера → Разрешить.",
                            color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("Разрешить камеру") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("Отмена") }
                }
            }
            else -> {
                // Полноэкранный preview камеры — Telegram-style.
                MlKitQrScanner(
                    onScanned = { value -> handleQr(value) },
                    modifier = Modifier.fillMaxSize(),
                )
                // Минимальная панель снизу: status / Cancel.
                // §TZ-2.5.7 — navigationBarsPadding чтобы кнопка "Отмена" не
                // оказывалась под системной кнопкой навигации Android (back/home).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color(0x88000000))
                        .navigationBarsPadding()
                        .padding(16.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(
                            status.ifBlank { "Наведите камеру на QR-код с экрана ПК" },
                            color = Color.White, fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onClose,
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            shape = RoundedCornerShape(10.dp),
                        ) { Text("Отмена", color = Color.White) }
                    }
                }
            }
        }
    }
}
