# ARCHITECTURE_AUDIT

**Дата:** 2026-04-30 · **Scope:** Android `:app`, Desktop `:desktop`, Worker `otl-api`

## 1. Слои и нарушения

### Android — текущая структура (clean-like)

```
presentation/  (Compose UI, ViewModels)
    ↓
domain/        (models, policies, use-cases — частично)
    ↓
data/          (repositories, network, local DB)
    ↓
core/          (cross-cutting: auth, crypto, network, settings, push, telemetry, ui)
    ↓
shared/:shared module — wire-format constants only (ApiFields)
```

### Главные нарушения

| # | Файл | Нарушение |
|---|---|---|
| A1 | `presentation/home/HomeViewModel.kt` (~1800 LoC) | Monolith — UI state + domain logic + repository orchestration в одном классе |
| A2 | `presentation/home/HomeScreen.kt` (~1219 LoC) | 17 диалогов в одном Composable — god-screen |
| A3 | `ApiClient.kt` (object) | Singleton с `@Volatile var` token, deviceId, hook — race conditions при logout |
| A4 | `data/network/api/*CallsImpl.kt` | Делают `JSONObject.put()` руками — нет типизации wire-format |
| A5 | `desktop/data/network/ApiClient.kt` | НЕ использует `:shared` ApiFields — duplicates literals |
| A6 | Desktop ↔ Android | Дублируется `HttpClientFactory`, `AuthSigningInterceptor` (2× файла) — должно быть в `:shared` |
| A7 | `core/security/E2EInterceptor.kt` | **Только Android**, на Desktop отсутствует — VPS видит plaintext payload от Mac/Win |

### Worker — текущая структура

```
index.js (entry)
  → resolveAuth → handlers-*.js
                    ↓ db.js (D1)
                    ↓ R2 bindings
crypto-envelope.js (E2E decrypt — оборачивает action dispatch)
ws-room.js (Durable Object)
```

### Worker — нарушения

| # | Файл | Нарушение |
|---|---|---|
| B1 | `index.js` | Очень длинный switch на actions; нет route table |
| B2 | `auth.js` `verifySignedRequest` | `console.log('security:...')` — логирует action name даже на success path |
| B3 | E2E enforcement | Не enforce'ed: client может слать plaintext, сервер примет |
| B4 | Replay dedup `request_dedup` | TTL не очищается автоматически — нужен cron cleanup |

## 2. Дублирование между Android и Desktop

| Что | Android путь | Desktop путь | Решение |
|---|---|---|---|
| OkHttp config + DNS override | `app/.../HttpClientFactory.kt` | `desktop/.../HttpClientFactory.kt` | Вынести в `:shared/jvmMain` |
| HMAC signing + clock skew | `app/.../AuthSigningInterceptor.kt` | `desktop/.../AuthSigningInterceptor.kt` | Вынести в `:shared` |
| ApiFields | `:shared` ✓ | НЕ используется (literals) | Импортировать |
| E2E envelope | `app/.../E2EInterceptor.kt` + `E2ECrypto.kt` | **отсутствует** | Добавить в `:shared` |
| Cert pin config | `app/.../PinningConfig.kt` (placeholders) | hardcode в `Secrets.kt` | Унифицировать |

## 3. Зависимости (build.gradle Android)

Compose BOM 2025.04.01, Hilt 2.55, Room 2.7.1 + SQLCipher 4.9.0, OkHttp 4.12 (НЕ Retrofit), Coil 3, Media3 1.5.1, Lottie 6.4, WorkManager 2.10, Firebase BOM 33.7.0, Biometric 1.1, Play Integrity 1.4.

**Не используется:** Retrofit, Kotlinx Serialization (json через org.json), Paging 3, Detekt/Ktlint.

## 4. Что РАБОТАЕТ хорошо (не трогать)

✅ **Action-based REST** — единая POST `/api` точка входа, action в body. Простой routing, легко добавлять новые actions.
✅ **PendingAction sealed class** — каждый offline action = один data class. Idempotency через `local_item_id`. Чисто.
✅ **AuthSigningInterceptor** — HMAC + clock-skew self-heal через server `Date` header — продуманно.
✅ **Custom DNS override + TrustManager** — chain работает; cert pin VPS вроде системного fallback.
✅ **E2E envelope (server-side)** — X25519+HKDF+AES-GCM с AAD-binding. Стандартная криптография, корректно.
✅ **Replay protection (`request_dedup` UNIQUE)** — простой и надёжный.

## 5. Что нужно поправить (приоритеты для «чистого старта» 2.4.0/0.6.0)

### P0 (must fix перед релизом)
- [P0-1] Force E2E (отказ plaintext) на Worker — `index.js` отвергает POST /api без `X-OTL-Crypto: v1`.
- [P0-2] E2EInterceptor для Desktop (`:shared/jvmMain` или копия в `desktop/`).
- [P0-3] PendingActionFlusher: на `replay_detected` — drop pending action, не retry (фикс цикла Egor_L 40 replays).
- [P0-4] При retry на новом маршруте — пересчёт `X-Request-Ts`/`X-Request-Sig` (иначе RouteManager ловит replay_detected при failover).
- [P0-5] Удалить fallback на plaintext в E2EInterceptor catch — fail-closed, не fail-open.

### P1 (rebuild чистый, но не критично)
- [P1-1] Вынести в `:shared` HttpClientFactory + AuthSigningInterceptor + E2EInterceptor.
- [P1-2] Bump `min_version` для Android и Desktop → текущая (force-update всех старых).
- [P1-3] Desktop ApiClient использует `:shared` ApiFields, не literals.
- [P1-4] Cleanup `console.log('security:*')` на success path в Worker.

### P2 (отложить — не часть «чистого старта»)
- [P2-1] HomeViewModel.kt разбить на несколько controllers (slice по экранам).
- [P2-2] HomeScreen.kt — выделить диалоги в отдельные Composables.
- [P2-3] WorkManager `BaseSyncWorker` — экспоненциальный backoff на сетевой fail (сейчас 57 RuntimeException/неделю).

## 6. Целевая структура (после «чистого старта»)

```
:shared
  api/         ApiFields, ApiActions
  network/     HttpClientFactory, AuthSigningInterceptor, RouteManager
  crypto/      E2ECrypto, E2EInterceptor, PinningConfig
  metrics/     OnActionLatency hook signature

:app (Android)
  presentation/  + UI / Compose
  data/local/    + Room/SQLCipher
  core/auth/     + SessionManager / Biometric
  core/push/     + FCM (Android-only)

:desktop
  ui/           + Compose Desktop windows
  sheets/       + KCEF / VLCJ
  app/          + Sheets-zone integrations

server-modular/
  index.js entry → enforce E2E
  handlers-*.js per-domain
  crypto-envelope.js + crypto-blob.js + crypto-ws.js
```

См. `REFACTOR_PLAN.md` для безопасного порядка изменений.
