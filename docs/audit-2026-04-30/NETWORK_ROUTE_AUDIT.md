# NETWORK_ROUTE_AUDIT

**Дата:** 2026-04-30 · **Метрики:** D1 `network_metrics`, `app_errors` за 3-7 дней

## 1. Текущая схема (Primary Route)

```
[Android / Mac / Win client]
  │
  │ OkHttp.dns.lookup("api.otlhelper.com") → [45.12.239.5]
  │ TLS handshake to 45.12.239.5:443
  │   verified by composite TrustManager: VPS self-signed cert (res/raw/otl_vps_cert.pem)
  │   fallback to system CA (для Firebase/R2 public)
  │
  │ POST / body=binary-envelope headers=[X-OTL-Crypto:v1, X-Request-Ts, X-Request-Sig]
  │
  ▼
[Main VPS 45.12.239.5 nginx]
  │ TLS termination (отдаёт self-signed cert)
  │ Re-encrypt новый TLS handshake к CF
  │ proxy_pass → api.otlhelper.com (publicly resolves to 104.21.52.79 / 172.67.196.234)
  │
  ▼
[Cloudflare Worker otl-api]
  │ Decrypt envelope (X25519-ECDH + HKDF-SHA256 + AES-256-GCM)
  │ Verify HMAC sig (X-Request-Sig + X-Request-Ts)
  │ Replay dedup (request_dedup UNIQUE)
  │ Dispatch action handler
  │
  ▼
[D1 / R2 / WS Durable Object]
```

**Public DNS** для `api.otlhelper.com` сейчас → CF IPs (104.21.52.79, 172.67.196.234). VPS-маршрут включается **только** через client-side OkHttp DNS override — другие приложения / браузер ходят напрямую на CF.

## 2. Что видит каждый узел

| Узел | URL/path | Headers | Body | Action | Token | Login | Payload |
|---|---|---|---|---|---|---|---|
| ISP/DPI | TLS SNI=`api.otlhelper.com`, 45.12.239.5:443 | TLS-encrypted | TLS-encrypted | ❌ | ❌ | ❌ | ❌ |
| Main VPS (TLS termination) | `POST /` | `X-OTL-Crypto:v1`, `X-Request-Ts`, `X-Request-Sig`, `Content-Type:application/x-otl-crypto`, `Content-Length` | binary ciphertext | ❌ (в encrypted body) | ❌ (в `_auth_token` поле) | ❌ | ❌ |
| Worker | `POST /` | те же | decrypted JSON | ✅ | ✅ | ✅ | ✅ |
| D1/R2 | n/a | n/a | server-side | ✅ | session-only | ✅ | ✅ |

**Важно:** Main VPS видит **факт запроса** (размер, timing, частоту) и `X-Request-Sig` (нечитаем, HMAC), но НЕ видит action, login, payload, token (всё внутри encrypted envelope).

## 3. Реальные метрики (последние 3 дня)

### Top 4 самых медленных action (Android, по avg duration_ms):

| Action | Запросов | avg ms | max ms | fails |
|---|---|---|---|---|
| heartbeat | 39 | 112,061 | 1,147,110 | 7 |
| base_version | 16 | 61,944 | 580,814 | 6 |
| get_unread_counts | 37 | 57,531 | 1,146,636 | 7 |
| log_activity | 52 | 36,327 | 1,154,795 | 27 |

⚠ Maximums до **19 минут** — это включает WorkManager backoff / wakelock задержки в background. Реальная сеть-only latency значительно ниже, но **fail rate реальный**.

### Per-user (3 дня):

| User | App | Net | Запросов | Fails | Fail % | Avg ms |
|---|---|---|---|---|---|---|
| superadmin | 2.3.42 | wifi | 21 | 19 | **90%** | 395,022 |
| Egor_L | 2.3.37 | cellular | 30 | 17 | **57%** | 585 |
| Ersh_I | 2.3.37 | cellular | 82 | 18 | **22%** | 44,099 |
| Kop_i | 2.3.37 | cellular | 22 | 0 | 0% | 836 |
| superadmin | 2.3.42 | cellular | 6 | 2 | 33% | 822 |

### Top сетевые ошибки (7 дней, Android):

| error_code | http_status | count | platform |
|---|---|---|---|
| **replay_detected** | 401 | 55 | android |
| SocketTimeoutException | 0 | 26 | android |
| ConnectException | 0 | 12 | android |
| InterruptedIOException | 0 | 9 | android |
| SocketException | 0 | 3 | android |

**Desktop в `network_metrics` отсутствует** — clients не льют (gap в коде).

## 4. Топ ошибок в `app_errors`

`BaseSync[get_version]: timeout` (18 раз), `Failed to connect to /45.12.239.5:443` (10+), `failed to connect after 10000ms` (3+). Все 57 RuntimeException за неделю — это сетевой fail на VPS.

## 5. Условия для fallback

### Включить fallback (App → secondary VPS → Main VPS → CF) если:
- ConnectException на primary VPS
- SocketTimeoutException > 25s
- TLS handshake fail (composite TrustManager rejects)
- 3+ последовательных сетевых fail за 60s
- pre-flight ping primary VPS не отвечает за 3s

### НЕ включать fallback если:
- HTTP 401 (auth — приложение должно re-login, не маршрут менять)
- HTTP 4xx с error в JSON body (бизнес-ошибка)
- HTTP 401 `request_expired` (clock skew — AuthSigningInterceptor сам recovers)
- HTTP 401 `replay_detected` (наша же повторка — drop в pending queue)
- offline (no network) — UI показывает "нет сети", не маршрут менять
- Worker вернул `app_paused` / `version_too_old` — это application-level, fallback не поможет

### Возврат на primary:
- Каждые 10 минут — пробный health check primary
- Если 3 успеха подряд → переключение обратно
- Sticky на fallback при первом fail → primary, чтобы не флапать

## 6. DPI / блокировки

### Текущая защита
- DNS override клиент-side → ISP видит только трафик к 45.12.239.5 (РФ-IP, не CF)
- VPS делает TLS termination со своим self-signed cert → DPI видит **non-CF cert** → не троттлит как CF
- Это уже DPI-resistant transport. Подтверждено комментариями в коде (§TZ-2.3.10, §TZ-2.3.13, §TZ-2.3.17): VPN-тест showed 14-19s vs 352ms direct.

### Угроза для второго (backup) VPS
Если backup-VPS делает TCP pass-through (`nginx stream`), DPI увидит CF cert при handshake → может троттлить. Решение: backup-VPS тоже делает TLS termination со своим cert (либо клиент пинит другой cert на fallback) — см. `BACKUP_VPS_DESIGN.md`.

## 7. Конкретные жалобы юзеров (из метрик)

| Юзер | Проблема | Возможная причина |
|---|---|---|
| superadmin | 90% fail rate на wifi | Конкретная wifi-сеть (дом? офис?) роутится через ISP с агрессивным DPI / упавший VPS |
| Ersh_I | 22% fail rate на cellular, ConnectException на 45.12.239.5:443 | Cellular ISP блокирует /троттлит конкретно VPS IP |
| Egor_L | 57% fail rate, 40 replay_detected | Cellular fail + клиент бесконечно retry'ит batched log_activity → server replay-rejects |

После релиза с fallback маршрутом ожидается падение fail rate до <5% у всех.

## 8. Что нужно сделать (action items)

См. `ROUTE_MANAGER_DESIGN.md` для алгоритма и `BACKUP_VPS_DESIGN.md` для второго узла.

Кратко:
1. Включить fallback IP в OkHttp DNS lookup (список вместо одного).
2. Pre-flight параллельный ping обоих VPS при старте.
3. Circuit breaker (5 fail / 60s → fallback).
4. Re-sign на retry (новый ts → новый sig, не replay).
5. Drop pending action на `replay_detected` (фикс Egor_L).
6. Desktop — добавить `onActionLatency` hook → `network_metrics`.
