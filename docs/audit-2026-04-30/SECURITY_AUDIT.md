# SECURITY_AUDIT

**Дата:** 2026-04-30 · **Scope:** Android, Desktop (Mac/Win), CF Worker, Main VPS, GitHub CI, local storage, network

## 1. Threat model — атакующие

| # | Атакующий | Что видит | Защита | Невозможно полностью |
|---|---|---|---|---|
| T1 | ISP / DPI | TLS bytes, размеры, timing, IP destination | Custom domain TLS termination на VPS, не CF fingerprint | timing analysis |
| T2 | Main VPS (TLS termination) | Request size, timing, HMAC sig, ciphertext envelope | E2E payload encrypted (X25519+AES-GCM), no plaintext payload | размер, частота, IP клиента |
| T3 | Backup VPS (planned) | то же что T2, или меньше (TCP pass-through) | TCP-only mode, outbound только на Main VPS, no Cloudflare creds | то же |
| T4 | Cloudflare Worker compromise | всё (это backend) | Cloudflare account 2FA; secrets в `wrangler secret`; minimal logging | если Worker rooted — потерян всё |
| T5 | GitHub repo leak | source code | secrets через GH Secrets, не в repo; APK не содержит plaintext secrets | если PAT leaked — clone all repos |
| T6 | Android spyware (Accessibility / screenrec / root) | UI, clipboard, screenshots, plaintext в RAM | FLAG_SECURE на чувствительных экранах, biometric, encrypted at-rest, no clipboard, no notification preview | screenrec на rooted = всё видно |
| T7 | macOS/Windows malware | RAM, файлы, network | secure storage (Keychain/DPAPI), encrypted DB, short sessions | admin malware = root |
| T8 | Route downgrade attacker | hijack DNS / ARP / cert | DNS override hardcode-IP; cert pinning (когда включим) | network-physical control |
| T9 | Fake relay (impersonate VPS) | traffic | TrustManager ставит cert match; pinning блокирует MITM | до включения pinning — composite TM, грубее |
| T10 | Replay attack | snooped requests | server `request_dedup` UNIQUE on HMAC sig | — |

## 2. Network security

### Что есть
- **TLS pinning ВЫКЛЮЧЕН** (`PinningConfig.PRIMARY_PIN` = placeholder) — баг безопасности.
- **DNS override** — захардкожен IP, не ходит через системный resolver — защита от DNS spoofing.
- **Composite TrustManager** — VPS self-signed cert + system fallback. Не равен pinning, но базовая защита есть.
- **HMAC-SHA256 sig** на каждый запрос — `sig = hmac(token, ts || action || sha256(body))`. Replay window ±300s + dedup table.
- **E2E envelope** — X25519-ECDH + HKDF-SHA256 + AES-256-GCM. AAD binding на ephemeral_pub. Per-request fresh key (forward secrecy partial — server priv key compromise не расшифровывает прошлое если VPS не залоggal payload).
- **DNS не leak'ает** в публичный DNS info про VPS — `api.otlhelper.com` публично резолвится в CF, VPS известен только клиенту.

### Что плохо
- **Pinning placeholders** — `<FILL_ME_AFTER_DOMAIN_MIGRATION>` в `PinningConfig.kt`. Pinning отключён, защищает только TrustManager.
- **Desktop без E2E** — VPS видит plaintext payload от Mac/Win. Gap.
- **`Authorization: Bearer` ещё может попадать на VPS** — на Android при E2E=on Authorization удаляется (`AuthSigningInterceptor.signedCopy().removeHeader("Authorization")`), но если E2E OFF (debug) — header идёт в plaintext.

## 3. Local storage security

### Android
- **SQLCipher** — да, через Room с passphrase из Android Keystore (StrongBox если есть, TEE fallback).
- **EncryptedSharedPreferences** для session token.
- **Encrypted media cache** в `~/.otldhelper/` — нет на Android (это desktop path); на Android используется internal storage + SQLCipher.
- **FLAG_SECURE** — нужно проверить на каких экранах.
- **Clipboard** — не используется для sensitive data (нужно подтвердить grep'ом).
- **Notification previews** — могут показывать текст; для sensitive - надо отключать на lockscreen.
- **Biometric lock on resume** — есть в `AppSettings.lockOnResumeSeconds` (default 0 = выкл).

### Desktop (Mac/Win)
- **AES-256-GCM при rest** в `~/.otldhelper/` — да, ключ от PBKDF2(login + device_id, salt, 100k).
- **Token storage** — Mac Keychain / Win DPAPI (не plaintext json).
- **Media cache** — encrypted `<sha1>.enc`, decrypt-on-demand в temp, wipe on logout.
- **Update files** — лежат в `updates/`; нужна верификация подписи перед apply (см. P3 ниже).

## 4. CI / build pipeline

### Что есть
- GitHub Actions `release-desktop.yml` — secrets через `${{ secrets.* }}`, не печатаются.
- ProGuard/R8 на release builds (но `optimize=false` для JCEF compat).
- Toolchain JDK 17 (auto-provisioned через foojay).

### Что нужно проверить (gap для read-only audit)
- [G-1] `gradle.properties` — содержит `otl.signing.keystorePassword`. **Проверить, что в `.gitignore`** (см. ниже).
- [G-2] `local.properties` — обычно в `.gitignore`, но проверить.
- [G-3] GitHub Actions logs — открытые? (приватный repo — OK для now).
- [G-4] Pinned versions actions (`uses: actions/checkout@v4` vs `@SHA`) — supply-chain risk. Сейчас `@v*`.
- [G-5] Dependabot / SCA — не включён.

### Действие
- Перенести `signingConfigs` на `environmentVariable("OTL_SIGNING_*")` или `extraProperties.gradle.kts` исключённый из git.
- Включить Dependabot (бесплатно для private repo).

## 5. Worker security

### Что есть
- Bearer token verification + HMAC sig + replay dedup.
- Role-based guards (`roles.js`).
- Rate limiting via Cloudflare WAF (по умолчанию).
- Input validation в `handlers-*.js` (валидация полей).
- D1 prepared statements (SQL injection защита).

### Что плохо
- **`console.log('security:*')`** в `auth.js` — печатает action name + token_source даже на success path. Cloudflare logs retain. **На non-error paths — silent.** См. `ARCHITECTURE_AUDIT.md` B2.
- **E2E НЕ enforced** — Worker принимает plaintext POST. Атакующий с украденным токеном может просто слать plaintext.
- **`request_dedup` cleanup** — нет cron'а; таблица растёт. Не security-bug, но performance.

## 6. R2 / D1 audit

### R2
- 3 buckets: `otl-app-releases` (public-ish — APK/EXE раздаются через Worker), `otl-avatars`, `otl-media` (encrypted).
- **Avatar URLs** — `/avatar/<login>` (login открытый) и `/a/<public_id>` (opaque, новые). Старые URL — login leak.
- **Media** — encrypted `crypto-blob.js`. Per-message ключ.
- **APK раздача через Worker** — public route, `key.endsWith('.apk')` check (защита от утечки base-snapshots).

### D1
- `users.password_hash` — PBKDF2-SHA256, 100k iter, salt per-user. ОК.
- `sessions` — UUID, expiration, revoke flag.
- `request_dedup` — UNIQUE constraint на HMAC sig.
- Нет SQL injection (prepared stmts всюду).

## 7. Найденные баги безопасности

| # | Тяжесть | Описание | Фикс |
|---|---|---|---|
| **S1** | high | E2E НЕ enforced на Worker — plaintext запросы принимаются | Шаг 4 (Worker enforcement) |
| **S2** | medium | Desktop НЕ имеет E2EInterceptor — все Mac/Win запросы plaintext от VPS до Worker | Шаг 3 (добавить interceptor) |
| **S3** | medium | TLS pinning DISABLED (placeholders) | Шаг 8 |
| **S4** | low | `console.log('security:*')` на success path в Worker auth.js | Шаг 4 |
| **S5** | low | `request_dedup` без TTL/cleanup | Шаг 4 (добавить cron) |
| **S6** | informational | Old `/avatar/<login>` URLs leak login (новые `/a/<public_id>` opaque) | пост-релиз — после migration новые pulls |
| **S7** | informational | `gradle.properties` риск попадания keystore password в git | проверить `.gitignore`, мигрировать на env |
| **S8** | informational | Anthropic API key неизвестного назначения | revoke если не используется |

## 8. Что мы можем защитить vs что не можем (честный список)

### Можем
✅ От network-перехвата (cert pinning + E2E)
✅ От Main VPS log-leak (E2E payload не в логах)
✅ От backup VPS (если запретить outbound + не давать CF creds)
✅ От replay attack (HMAC + dedup)
✅ От устаревшего клиента (force-update через min_version)
✅ От rogue cert через corp/Wifi (pinning when enabled)
✅ От GitHub leak — нет plaintext secrets в repo / APK / EXE

### Не можем (и это ОК)
❌ От rooted Android с screen recording — невозможно фундаментально
❌ От admin malware на Win/Mac — невозможно
❌ От timing/size analysis на трафик (только VPN/Tor)
❌ От Cloudflare account compromise — это backend, корневой trust
❌ От keystore loss — backup критичен

## 9. P0 действия (этот sprint)

- [SEC-1] Шаг 4 — Worker enforce E2E (S1)
- [SEC-2] Шаг 3 — Desktop E2EInterceptor (S2)
- [SEC-3] Шаг 8 — TLS pinning enable (S3)
- [SEC-4] Шаг 4 — log sanitization (S4)
- [SEC-5] Шаг 4 — `request_dedup` cleanup cron (S5)
- [SEC-6] Шаг 11 — git/gradle audit (S7), Anthropic revoke (S8)

См. `REFACTOR_PLAN.md` для последовательности.
