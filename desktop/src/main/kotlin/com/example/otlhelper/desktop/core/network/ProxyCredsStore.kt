package com.example.otlhelper.desktop.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * §TZ-DESKTOP-0.9.3 — Хранилище creds корпоративного прокси, когда SSPI
 * auto-auth не сработал и нужно явно спросить юзера.
 *
 * Поток:
 *   1. [WindowsSspiAuthenticator] пытается auto-auth → всё ок → сюда не приходим
 *   2. Если SSPI 3 раза подряд не справился — переключаемся на manual mode:
 *      [requestCreds] показывает диалог через [showDialog] StateFlow
 *   3. UI наблюдает [showDialog] и рендерит [ManualProxyCredsDialog]
 *   4. Юзер вводит → [submitCreds] → CountDownLatch отпускает
 *      [requestCreds] и возвращает creds в [JcifsNtlmAuthenticator]
 *   5. На сессию creds кэшируются. При логауте/закрытии — стираются.
 *
 * **Безопасность**: creds живут только в памяти (AtomicReference). Не
 * сериализуются на диск. Persist в Windows Credential Manager — следующая
 * итерация (0.9.4+) если потребуется выживание между запусками.
 */
object ProxyCredsStore {

    /**
     * Текущие сохранённые creds (на сессию). null = ещё не введены.
     */
    private val cachedCreds = AtomicReference<ProxyCredentials?>(null)

    /**
     * UI-state: должен ли быть показан диалог. Compose следит и рендерит
     * [ManualProxyCredsDialog] когда true.
     */
    private val _showDialog = MutableStateFlow(false)
    val showDialog: StateFlow<Boolean> = _showDialog.asStateFlow()

    /**
     * Адрес прокси для отображения в диалоге ("Корпоративный прокси
     * <host>:<port> требует авторизации"). Реальный hostname определяется
     * runtime через PowerShell ProxySettings — не хардкодится.
     */
    private val _proxyAddress = MutableStateFlow<String?>(null)
    val proxyAddress: StateFlow<String?> = _proxyAddress.asStateFlow()

    /** Латч на котором authenticator ждёт пока юзер введёт creds. */
    @Volatile
    private var pendingLatch: CountDownLatch? = null

    /**
     * Вызывается из [JcifsNtlmAuthenticator] когда нужны creds. Если уже
     * есть в кэше — возвращает сразу. Иначе показывает диалог + блочит
     * до 5 минут пока юзер не введёт. timeout → возврат null.
     *
     * Вызывается с background-thread'а OkHttp; ОК блокировать.
     */
    fun requestCreds(proxyHostPort: String): ProxyCredentials? {
        cachedCreds.get()?.let { return it }

        // Поднимаем dialog
        synchronized(this) {
            if (pendingLatch == null) {
                pendingLatch = CountDownLatch(1)
                _proxyAddress.value = proxyHostPort
                _showDialog.value = true
                SspiLogger.log("ProxyCredsStore: requesting manual creds for $proxyHostPort")
            }
        }
        val latch = pendingLatch ?: return cachedCreds.get()
        // Ждём 5 минут — юзер должен ввести
        val ok = try {
            latch.await(5, TimeUnit.MINUTES)
        } catch (_: InterruptedException) {
            false
        }
        if (!ok) {
            SspiLogger.log("ProxyCredsStore: creds dialog timeout (5 min)")
        }
        return cachedCreds.get()
    }

    /**
     * Юзер ввёл creds в диалоге → сохраняем + отпускаем pending requests.
     * [domain] может быть пустым если логин в формате `DOMAIN\login` или
     * `login@domain` (jcifs парсит автоматически).
     */
    fun submitCreds(login: String, password: String, domain: String? = null) {
        val creds = ProxyCredentials(login = login.trim(), password = password, domain = domain?.trim())
        cachedCreds.set(creds)
        SspiLogger.log("ProxyCredsStore: creds submitted (login=${creds.login}, domain=${creds.domain ?: "auto"})")
        synchronized(this) {
            _showDialog.value = false
            pendingLatch?.countDown()
            pendingLatch = null
        }
    }

    /** Юзер отменил диалог — отменяем pending requests. */
    fun cancelDialog() {
        SspiLogger.log("ProxyCredsStore: dialog cancelled by user")
        synchronized(this) {
            _showDialog.value = false
            pendingLatch?.countDown()
            pendingLatch = null
        }
    }

    /** Удалить cached creds (например при логауте). */
    fun clear() {
        cachedCreds.set(null)
        SspiLogger.log("ProxyCredsStore: cleared")
    }

    /** Текущие creds (для UI / Settings). null = не введены. */
    fun current(): ProxyCredentials? = cachedCreds.get()
}

/**
 * Доменные creds для NTLM auth.
 * [login] может быть в формате `DOMAIN\user`, `user@DOMAIN.COM` или просто `user`.
 * [domain] — опционален, нужен если login в простом формате.
 */
data class ProxyCredentials(
    val login: String,
    val password: String,
    val domain: String? = null,
) {
    /** Парсит DOMAIN\login или login@DOMAIN.COM, возвращает (domain, plainUser). */
    fun parsedDomainAndUser(): Pair<String, String> {
        if (login.contains('\\')) {
            val (d, u) = login.split('\\', limit = 2)
            return d.trim() to u.trim()
        }
        if (login.contains('@')) {
            val (u, d) = login.split('@', limit = 2)
            return d.trim() to u.trim()
        }
        return (domain ?: "") to login
    }
}
