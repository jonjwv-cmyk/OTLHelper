# PROJECT_MAP — карта проекта OTL Helper

**Дата:** 2026-04-30 · **Состояние на:** 0.5.2 / Android 2.3.42 · **Branch:** `codex/project-architecture-cleanup`

## 1. Дерево модулей

```
OTLHelper2/
├── settings.gradle.kts          # include :shared, :app, :desktop
├── build.gradle.kts             # root
├── gradle.properties
├── shared/                      # KMP-ready (JVM-only пока) — общие константы
│   └── src/main/kotlin/com/example/otlhelper/shared/api/
│       └── ApiFields.kt         # имена полей JSON wire-format
├── app/                         # Android client (~96 Kt files, ~13.5k LoC)
│   └── src/main/{java,res,AndroidManifest.xml}
└── desktop/                     # Compose Multiplatform Mac+Win
    └── src/main/{kotlin,resources}
```

Внешние, не в этом git:
- `~/Documents/HELPERS/server-modular/` — Cloudflare Worker `otl-api` (JS, ~25 файлов)
- `~/Documents/HELPERS/server workflow/` — Worker `dsync` (Sheets proxy)

## 2. Android (`:app`) — ключевые зоны

| Зона | Путь | Назначение |
|---|---|---|
| Entry | `OtlApp.kt`, `MainActivity.kt` | Hilt + Composition root |
| Network | `data/network/HttpClientFactory.kt` | OkHttp + DNS override + custom TrustManager |
| API facade | `ApiClient.kt` | Делегат на 6 zone-implementations |
| API zones | `data/network/api/{Auth,Feed,Admin,Push,Metrics,System}CallsImpl.kt` | Per-domain action calls |
| Crypto | `core/security/{E2EInterceptor,E2ECrypto,PinningConfig}.kt` | E2E envelope + cert pinning |
| Sign | `core/network/AuthSigningInterceptor.kt` | HMAC + token-in-body + clock skew |
| Push | `core/push/{OtlFirebaseMessagingService,PushTokenManager,PushEventBus,NotificationChannels}.kt` | FCM |
| Auth | `core/auth/AuthEvents.kt`, `SessionManager.kt` | Bearer + dead-token signal |
| Storage | Room + SQLCipher; `data/local/...` | Encrypted local DB |
| Pending queue | `data/pending/{PendingAction,PendingActionFlusher}.kt` | Offline writes + idempotency |
| Settings | `core/settings/AppSettings.kt` | SharedPreferences toggles (e2eeEnabled default ON) |
| WebSocket | `data/network/WsClient.kt` | Live updates |
| UI | `presentation/...` | Compose screens; `home/HomeViewModel.kt` ~1800 LoC monolith |
| WorkManager | `data/sync/BaseSyncWorker.kt` | Periodic base sync (источник 57 RuntimeException/неделя) |

## 3. Desktop (`:desktop`) — ключевые зоны

| Зона | Путь | Назначение |
|---|---|---|
| Entry | `Main.kt`, `App.kt` | Compose Desktop window |
| Network | `desktop/data/network/HttpClientFactory.kt` | OkHttp + DNS override (mirror Android) |
| API client | `desktop/data/network/ApiClient.kt` | Action-based POST с request_id |
| Sign | `desktop/data/network/AuthSigningInterceptor.kt` | Mirror Android |
| Secrets | `desktop/data/security/Secrets.kt` | XOR-обфусцированный VPS_HOST_IP, BASE_URL, CERT_PATH |
| Sheets | `desktop/sheets/{SheetsRuntime,SheetsWebView,SheetsCss}.kt` | KCEF Chromium bridge |
| Update | `desktop/core/update/AppUpdate.kt` | Soft-update + restart |
| WebSocket | `desktop/data/network/WsClient.kt` | Mirror Android |

⚠ **Desktop НЕ имеет E2EInterceptor** (Android-only сейчас) — gap. Также **НЕ льёт network_metrics** — слепая зона по диагностике.

## 4. Server `otl-api` (Cloudflare Worker)

| Файл | Назначение |
|---|---|
| `index.js` | Entry, CORS, public GET routes, action dispatch |
| `auth.js` | Bearer + HMAC-SHA256 verify + replay protection (`request_dedup`) |
| `crypto-envelope.js` | X25519-ECDH + HKDF-SHA256 + AES-256-GCM (E2E) |
| `crypto-blob.js` | Attachment blob crypto |
| `crypto-ws.js` | WebSocket message crypto |
| `passwords.js` | PBKDF2-SHA256 (100k iters — CF Worker hard cap) |
| `roles.js` | user / admin / superadmin guards |
| `db.js` | D1 helpers |
| `handlers-{admin,base,chat,feed,media,metrics,reactions,session,sheets,telemetry,drafts}.js` | Per-domain handlers |
| `integrity.js` | Play Integrity verify (выключен сейчас, flag=0) |
| `push.js` | FCM send |
| `migrate-encrypt-r2.js` | R2 attachment encryption migration |
| `ws-room.js` | Durable Object for WebSocket fan-out |
| `wrangler.toml` | Bindings: DB, BASE_BUCKET, WS_ROOM, secrets |
| `migrations/*.sql`, `migrations.sql` | D1 schema |

### D1 таблицы (35 шт.)
Auth: `users`, `sessions`, `request_dedup`
Feed: `app_messages`, `app_messages_edits`, `attachments`, `message_attachments`, `message_polls`, `message_poll_options`, `message_poll_votes`, `message_reactions`, `news_polls`, `news_poll_options`, `news_poll_votes`
Chat: `muted_contacts`, `user_message_reads`
Admin: `admin_actions_log`, `app_control_state`, `app_control_events`, `feature_flags`, `app_version`, `client_commands`
Metrics: `app_errors`, `app_performance`, `network_metrics`, `user_activity`
System: `base_meta`, `base_records`, `mol_synonyms`, `import_lock`
Push: `push_subscriptions`
Drafts/scheduled: `drafts`, `scheduled_messages`, `sync_queue`
Internal: `_cf_KV`, `sqlite_sequence`

### R2 buckets
- `otl-app-releases` — APK + DMG + EXE + KCEF bundles
- `otl-avatars` — user avatars
- `otl-media` — encrypted attachments

## 5. CI/CD

| Workflow | Что |
|---|---|
| `.github/workflows/release-desktop.yml` | DMG/EXE build → R2 → D1 → broadcast |
| `.github/workflows/upload-kcef-bundles.yml` | Один раз — Chromium tarball на R2 |
| (Android) `~/Documents/HELPERS/otl-release.sh` | Локальный signed build (нет CI workflow) |

## 6. HELPERS (`~/Documents/HELPERS/`) — операционные файлы

См. `HELPERS_AUDIT.md` для детального аудита. Кратко:
- 4 docs (PASSPORT, HANDOFF, TZ-NEXT, SECRETS)
- 1 архив старых docs
- 2 локальных Worker repo
- Android keystore + backup
- Release script
- E2E backup keys
- Apps Script для импорта МОЛ

## 7. Точки входа сетевого трафика

```
Android/Desktop client
  ↓ OkHttp DNS override: api.otlhelper.com → 45.12.239.5 (VPS)
  ↓ TLS handshake (custom TrustManager pins VPS self-signed cert)
  ↓ POST / body=encrypted-envelope (X25519+AES-GCM) headers=[X-OTL-Crypto:v1, X-Request-Ts, X-Request-Sig]
[Main VPS 45.12.239.5 nginx]
  ↓ TLS terminate + re-encrypt to CF
  ↓ Forward to api.otlhelper.com (CF custom domain)
[Cloudflare Worker otl-api]
  ↓ Decrypt envelope → JSON
  ↓ Verify HMAC sig + replay dedup
  ↓ Dispatch action handler
[D1 / R2 / WS Durable Object]
```

## 8. Hotspots (большие/проблемные файлы)

| Файл | Размер | Проблема |
|---|---|---|
| `app/.../home/HomeViewModel.kt` | ~1800 LoC | Monolith, рефакторинг отложен |
| `app/.../home/HomeScreen.kt` | ~1219 LoC | 17 dialog states |
| `app/.../home/internal/ChatController.kt` | ~600+ LoC | Логика ленты + draft + send |
| `desktop/.../sheets/SheetsCss.kt` | ~? | CSS chunks для Google Sheets маски |
| `server-modular/handlers-feed.js` | ~? | Все feed actions |
| `server-modular/handlers-admin.js` | ~? | Все admin actions |

## 9. Что не покрыто этим документом

- **UI presentation слой** — намеренно не разбиваем (вне scope security/network audit)
- **Compose theme system** — отдельная зона, см. CLAUDE.md (устарел) или live код
- **Lottie / Coil / VLCJ интеграции** — не sensitive

См. далее `ARCHITECTURE_AUDIT.md`, `NETWORK_ROUTE_AUDIT.md`, `SECURITY_AUDIT.md`.
