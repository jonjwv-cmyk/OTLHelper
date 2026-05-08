# ROUTE_MANAGER_DESIGN

**Дата:** 2026-04-30 · **Scope:** OkHttp DNS lookup + circuit breaker + idempotent retry · **Платформы:** Android `:app` + Desktop `:desktop`

## 1. Цель

Когда Main VPS (45.12.239.5) недоступен → клиент автоматически переключается на Backup VPS. Когда Main VPS восстановился → возвращается. Без UI freeze, без duplicate writes, без снижения безопасности.

## 2. Состояния маршрутов

```
       ┌───────────────────────────────────────────────────────────────┐
       │                                                               │
       ▼                                                               │
  PRIMARY_HEALTHY  ─── 5 fail / 60s ──▶  PRIMARY_DEGRADED ──── ──▶  FALLBACK_ACTIVE
       │                                       │                       │
       │                                       │                       │
       └◀─── 3 success ping ─── PRIMARY_HEALTH_CHECK ◀── каждые 10мин ─┘
                                       │
                                       └ если primary всё ещё мёртв → остаёмся на FALLBACK
```

### Простая модель состояний (без полного state machine)

| Состояние | Условие | Действие |
|---|---|---|
| **HEALTHY** | fail rate < 30% за 5 минут | Использовать primary |
| **FALLBACK** | 5 последовательных fail или 60% fail rate за 60 секунд | Переключение на backup, periodic check primary |
| **RECOVERING** | 3 успешных ping primary подряд после fallback | Возврат на primary |

## 3. Реализация: OkHttp `Dns` interface

OkHttp нативно поддерживает несколько IP в `Dns.lookup()` — он сам перебирает по порядку при connection failure. Так что **львиная доля логики** — просто отдавать список:

```kotlin
// :shared/jvmMain/.../OtlDns.kt (новый)
class OtlDns(
    private val routeState: RouteState,
) : Dns {
    private val primaryIp: InetAddress = InetAddress.getByName("45.12.239.5")
    private val backupIp: InetAddress? = BACKUP_VPS_IP?.let { InetAddress.getByName(it) }

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname != "api.otlhelper.com" &&
            !hostname.endsWith(".otlhelper.com")) {
            return Dns.SYSTEM.lookup(hostname)
        }
        return when (routeState.preferred) {
            Route.PRIMARY -> listOfNotNull(primaryIp, backupIp)
            Route.BACKUP  -> listOfNotNull(backupIp, primaryIp)
        }
    }
}
```

OkHttp при `ConnectException` на первом IP **автоматически пробует следующий** в списке — это уже половина RouteManager.

## 4. RouteState — circuit breaker

```kotlin
// :shared/jvmMain/.../RouteState.kt
@Singleton
class RouteState {
    @Volatile var preferred: Route = Route.PRIMARY
        private set

    private val recentFails = ConcurrentLinkedDeque<Long>()
    private val recentTotal = AtomicLong(0)
    private val WINDOW_MS = 60_000L
    private val FAIL_THRESHOLD_PCT = 60
    private val MIN_REQUESTS_BEFORE_DECISION = 5

    fun reportSuccess() {
        // Если мы на BACKUP — копим успехи к возврату
        if (preferred == Route.BACKUP) successesOnBackup.incrementAndGet()
        prune()
    }

    fun reportFailure(errorClass: String) {
        // Только сетевые fail (не auth/business errors) идут в circuit breaker
        if (errorClass !in NETWORK_ERROR_CLASSES) return
        recentFails.add(System.currentTimeMillis())
        prune()
        evaluate()
    }

    private fun evaluate() {
        if (recentFails.size < MIN_REQUESTS_BEFORE_DECISION) return
        val window = recentFails.size * 100 / recentTotal.get().toInt()
        if (window >= FAIL_THRESHOLD_PCT && preferred == Route.PRIMARY) {
            preferred = Route.BACKUP
            Log.i(TAG, "route=BACKUP reason=primary_unhealthy fail_pct=$window")
        }
    }

    /** Periodic health-check restoration. Run every 10 min. */
    suspend fun probePrimaryRestore(probeFn: suspend () -> Boolean) {
        if (preferred != Route.BACKUP) return
        val ok = (0..2).all { probeFn(); /* sleep 1s between */ true }
        if (ok) {
            preferred = Route.PRIMARY
            recentFails.clear()
            Log.i(TAG, "route=PRIMARY reason=health_check_restored")
        }
    }

    companion object {
        val NETWORK_ERROR_CLASSES = setOf(
            "ConnectException", "SocketTimeoutException", "SocketException",
            "InterruptedIOException", "UnknownHostException",
            "SSLPeerUnverifiedException", // только при initial setup, не при steady-state
        )
    }
}

enum class Route { PRIMARY, BACKUP }
```

## 5. Pre-flight ping при старте

```kotlin
// OtlApp.onCreate (Android) / Main.kt (Desktop)
fun warmRoute() = scope.launch(Dispatchers.IO) {
    val primaryOk = async { ping("45.12.239.5", 443, timeoutMs = 3_000) }
    val backupOk  = async { BACKUP_VPS_IP?.let { ping(it, 80, timeoutMs = 3_000) } ?: false }
    when {
        primaryOk.await() -> routeState.preferred = Route.PRIMARY
        backupOk.await()  -> routeState.preferred = Route.BACKUP
        else              -> /* keep default */
    }
}

private fun ping(host: String, port: Int, timeoutMs: Int): Boolean = try {
    Socket().use { s -> s.connect(InetSocketAddress(host, port), timeoutMs); true }
} catch (_: Exception) { false }
```

Параллельный TCP-ping. Не TLS — слишком медленно для startup.

## 6. Re-sign на retry (КРИТИЧНО)

В текущем `AuthSigningInterceptor` есть retry на `request_expired` (clock skew). Нужно расширить: **при любом ConnectException/SocketTimeoutException retry → новая подпись**.

```kotlin
// AuthSigningInterceptor.intercept расширение
override fun intercept(chain: Interceptor.Chain): Response {
    val token = tokenProvider()
    if (token.isBlank()) return chain.proceed(chain.request())

    val original = chain.request()
    val bodyString = original.readBodyUtf8()
    val action = JSONObject(bodyString).optString("action", "")
    val bodyWithToken = embedAuthToken(bodyString, token)

    // Первая попытка
    val firstAttempt = signedCopy(original, token, bodyWithToken, action, clockOffsetSeconds.get())
    val response = try {
        chain.proceed(firstAttempt)
    } catch (e: IOException) {
        // OkHttp DNS list уже попробует backup. Если ВСЁ упало — пробрасываем.
        // На уровне RouteState регистрируем fail.
        routeState.reportFailure(e.javaClass.simpleName)
        throw e
    }

    routeState.reportSuccess()

    // 401 request_expired → clock skew retry (как было)
    if (response.code == 401) {
        val newOffset = learnOffsetFromResponse(response) ?: return response
        val prev = clockOffsetSeconds.getAndSet(newOffset)
        if (kotlin.math.abs(newOffset - prev) >= 5) {
            response.close()
            // НОВАЯ подпись с новым ts → не replay
            val retry = signedCopy(original, token, bodyWithToken, action, newOffset)
            return chain.proceed(retry)
        }
    }
    return response
}
```

Главное: **OkHttp перебирает список IP сам**, так что connection-fail → переключение происходит на низком уровне. Re-sign не нужен (тот же запрос тот же ts тот же sig — попадает на другой VPS, который пересылает на тот же Worker — Worker уже принял первый, replay_detected). Поэтому при failover на backup нам нужен новый ts/sig.

**Правильное решение**: при `ConnectException` к первому IP — **пересоздать запрос** с новым ts перед попыткой второго IP.

OkHttp не предоставляет такого хука нативно. Решения:
- (a) Своя логика поверх — не использовать OkHttp DNS-list, а вручную retry с новым `signedCopy()`.
- (b) Добавить интерцептор после OkHttp connection retry, который при retry attempts>1 — re-signs.

**Выбираем (a) — простой и явный**:

```kotlin
// RouteAwareInterceptor.kt (новый, заменяет OkHttp DNS-list)
class RouteAwareInterceptor(
    private val routeState: RouteState,
    private val signing: AuthSigningInterceptor,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val ips = routeState.orderedIps()  // [primary, backup] или [backup, primary]
        for ((i, ip) in ips.withIndex()) {
            try {
                val signedReq = signing.signFresh(req)  // новый ts/sig
                val urlOnIp = req.newBuilder()
                    .url(req.url.newBuilder().host(ip).build())
                    .build()
                val resp = chain.proceed(urlOnIp)
                routeState.reportSuccess()
                return resp
            } catch (e: IOException) {
                routeState.reportFailure(e.javaClass.simpleName)
                if (i == ips.size - 1) throw e   // последний — пробрасываем
                // иначе — пробуем следующий с новой подписью
            }
        }
        throw IOException("all_routes_exhausted")
    }
}
```

## 7. Drop pending action на `replay_detected`

Текущий `PendingActionFlusher` на 401 retry'ит batched actions. Если 401=`replay_detected` — это значит **сервер уже принял этот запрос** (или его дубликат). Drop pending row, не retry.

```kotlin
// PendingActionFlusher (расширение)
when (val resp = action.execute()) {
    has(error="replay_detected") -> {
        // Сервер уже принял (или мы дублировали). Drop.
        pendingDao.delete(actionId)
        Log.i(TAG, "drop pending action=$actionType reason=replay_detected")
    }
    has(error="request_expired") -> {
        // Clock skew — retry на следующий tick.
        // (AuthSigningInterceptor сам учится от Date header.)
    }
    has(ok=true) -> pendingDao.delete(actionId)
    else -> {
        // Other error — bump retry counter, schedule next attempt.
    }
}
```

Это снимет 40 повторений Egor_L → log_activity → replay_detected.

## 8. Безопасные метрики (нет payload)

```kotlin
data class RouteMetric(
    val route: Route,        // primary / backup
    val statusClass: String, // 2xx / 4xx / 5xx / network_fail
    val httpStatus: Int,     // 200 / 401 / 0
    val errorCode: String,   // "" / "ConnectException" / "replay_detected"
    val latencyMs: Long,
    // НЕТ: action, login, payload, headers, body, tokens, IPs (только route name)
)
```

`network_metrics` D1 таблица уже принимает это. Добавляем поле `route` в client → Worker → D1.

## 9. Защита от downgrade attack

| Атака | Защита |
|---|---|
| Привести клиента к plaintext (без E2E) | Worker enforces E2E — отказ HTTP 426 (см. SECURITY_AUDIT S1) |
| Подменить cert на rogue | TLS pinning enabled (после Шага 8) |
| Force на backup VPS чтобы перехватить там | Backup VPS делает TCP pass-through, не видит plaintext |
| Force на старый клиент с уязвимостью | Force-update min_version = current (Worker отказывает старым) |
| MITM на DNS чтобы подсунуть фейк IP | DNS override hardcode-IP в коде, не использует system DNS |

## 10. Защита от UI freeze

- Все network calls на `Dispatchers.IO`.
- Pre-flight ping в `OtlApp.onCreate` — fire-and-forget, не блокирует startup.
- При первом запросе если RouteState ещё не warmed — используется default (PRIMARY) и обновляется по факту.
- Switch route — без UI индикации (transparent для пользователя).

## 11. Алгоритм по шагам (упрощённый, в коде)

```
1. App start → OtlApp.onCreate
   1a. Async ping primary :443 + backup :80 параллельно (3s timeout)
   1b. Установить RouteState.preferred = healthier_one (default PRIMARY если оба ОК)

2. Каждый POST /api запрос:
   2a. RouteAwareInterceptor берёт preferred → строит URL с host=preferred_ip
   2b. AuthSigningInterceptor signs запрос (fresh ts/sig)
   2c. E2EInterceptor шифрует body
   2d. OkHttp выполняет
   2e. Success → RouteState.reportSuccess(); return
   2f. ConnectException → RouteState.reportFailure(); пробуем второй IP с новой подписью
   2g. 4xx/5xx error → НЕ trigger fallback (это не сетевая проблема)

3. Каждые 10 минут → если на BACKUP, ping PRIMARY 3 раза:
   3a. Если все 3 OK → переключение на PRIMARY

4. PendingActionFlusher:
   4a. На replay_detected → drop pending row
   4b. На network_fail → keep, retry позже с backoff
```

## 12. Тесты

- **unit**: `RouteState` — переход HEALTHY→FALLBACK→PRIMARY на разных fail patterns.
- **integration**: mock OkHttp с `ConnectException` на primary IP — должен retry на backup.
- **manual** (Шаг 10): на Mac iptables drop 45.12.239.5 → клиент через 1-2 запроса работает через backup; iptables clear → возврат через 10 мин.
