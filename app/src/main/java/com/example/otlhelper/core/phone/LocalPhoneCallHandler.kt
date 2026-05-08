package com.example.otlhelper.core.phone

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Payload клика по телефону — номер + имя контакта. Имя HomeScreen показывает
 * в CallConfirmDialog'е над номером (§TZ-2.3.6 UX: админ перед звонком видит
 * КОМУ и на какой номер, не только +7…). Имя опционально — для legacy
 * call-sites где контекст неизвестен (`null` → dialog показывает только номер).
 */
data class PhoneCallRequest(
    val number: String,
    val contactName: String? = null,
)

/**
 * CompositionLocal через который любой phone-chip в subtree (MolRecordCard,
 * WarehouseCard, будущие компоненты) получает обработчик клика по номеру.
 *
 * `null` = handler не предоставлен (fallback на системный ACTION_DIAL).
 * HomeScreen провайдит реализацию, открывающую CallConfirmDialog + запускающую
 * CallStateManager.startCall() при подтверждении.
 */
val LocalPhoneCallHandler = staticCompositionLocalOf<((PhoneCallRequest) -> Unit)?> { null }
