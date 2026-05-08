package com.example.otlhelper.core.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §TZ-2.3.6 — тонкий helper для инициации звонка из приложения. После
 * пользовательского подтверждения (CallConfirmDialog) запускает ACTION_CALL
 * — системный dialer берёт управление: SIM picker (на dual-SIM), сам звонок,
 * Завершить, громкая связь. In-app панель звонка / TelecomManager endCall /
 * AudioManager speakerphone — избыточны (юзер всё равно в системной звонилке
 * во время разговора), сняты.
 */
@Singleton
class CallStateManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Нет ли grant'а CALL_PHONE. UI должен вызвать перед startCall. */
    fun missingPermissions(): List<String> {
        return if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) listOf(Manifest.permission.CALL_PHONE) else emptyList()
    }

    /**
     * Запускает звонок через ACTION_CALL — открывает системный dialer с
     * автоматическим выбором SIM (если две). Юзер говорит и завершает звонок
     * прямо там; на возврате — мы в том же state.
     */
    @SuppressLint("MissingPermission")
    fun startCall(phoneNumber: String): Boolean {
        if (missingPermissions().isNotEmpty()) return false
        val telUri = PhoneFormatter.toTelUri(phoneNumber)
        if (telUri.isBlank()) return false
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$telUri")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "startCall failed: ${e.message}", e)
            false
        }
    }

    companion object {
        private const val TAG = "CallStateManager"
    }
}
