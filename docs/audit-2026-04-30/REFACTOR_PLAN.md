# REFACTOR_PLAN — порядок изменений для «чистого старта» 2.4.0/0.6.0

**Дата:** 2026-04-30 · **Подход:** Без обратной совместимости. Force-update через `min_version`.

## 1. Принципы

1. **Каждый этап — собирается и работает локально** до коммита.
2. **Тесты после каждого этапа** (не после всех 11 шагов).
3. **Один коммит = одна осмысленная единица** (атомарный rollback).
4. **`gradle :app:assembleDebug`** + **`gradle :desktop:run`** — sanity после изменения.
5. **Не трогать `:shared` если не уверен в KMP-совместимости** — сейчас JVM-only.

## 2. Этапы (последовательность)

### ЭТАП Я — отчёты (Шаг 1)
**Без изменений кода.** Только MD-файлы в `docs/audit-2026-04-30/`. Готово.

---

### ЭТАП P — passwords reset (Шаг 2)
**Не код, SQL UPDATE на production D1.**
- Только после **одобрения**.
- После выполнения — все юзеры пере-логинятся при следующем `app_status`.
- Idempotent: можно прогнать ещё раз, новый hash без вреда.

---

### ЭТАП C — Client cleanup (Шаг 3)

**Подэтапы (атомарные коммиты):**

#### C.1 — Bump versions
- `app/build.gradle.kts`: `versionCode +1`, `versionName = "2.4.0"`.
- `desktop/build.gradle.kts`: `version = "0.6.0"`, `compose.desktop.application.nativeDistributions.packageVersion = "0.6.0"`.
- `~/Documents/HELPERS/PROJECT_PASSPORT.md` шапка → 2.4.0/0.6.0 + дата.

Тест: `gradle :app:assembleDebug` + `gradle :desktop:packageReleaseDmg`.

#### C.2 — Force E2E на Android
- `AppSettings.e2eeEnabled` — удалить toggle, всегда true. (или оставить toggle для debug, но убрать default false случай)
- `E2EInterceptor.intercept` — на crypto-encrypt fail: throw IOException, не proceed plaintext.
- Удалить тест-флаги если есть.

Тест: `gradle :app:test` + manual `gradle :app:installDebug` → app starts → login + send message.

#### C.3 — Desktop E2EInterceptor
- Создать `desktop/.../core/security/E2EInterceptor.kt` — mirror Android.
- Создать `desktop/.../core/security/E2ECrypto.kt` — mirror.
- Загрузить `otl_server_pub.bin` в `desktop/src/main/resources/`.
- Подключить в `desktop/.../HttpClientFactory.kt` `rest` builder: `addInterceptor(E2EInterceptor(...))`.

Тест: `gradle :desktop:run` → login → send message → wrangler tail видит `[crypto-envelope] decrypted ok`.

#### C.4 — RouteAware retry + drop replay
- `core/network/RouteState.kt` (Android + Desktop, или `:shared`).
- `core/network/RouteAwareInterceptor.kt`.
- `data/pending/PendingActionFlusher.kt` — добавить `if (error == "replay_detected") drop`.
- `OtlApp.onCreate` (Android) / `Main.kt` (Desktop) — pre-flight ping.

Тест: на Mac `iptables -A OUTPUT -d 45.12.239.5 -j DROP` (или `pfctl`) → клиент через 1-2 запроса работает через backup. Открыть iptables → возврат через 10 мин.

#### C.5 — Desktop metrics
- `desktop/.../ApiClient.kt` — добавить `onActionLatency` hook.
- В Main.kt: `ApiClient.onActionLatency = { action, dur, ok, http, err -> Telemetry.timing(...) }`.
- `MetricsCalls` для desktop — flush раз в 5 мин.

Тест: открыть desktop, сделать запросов → `wrangler d1 execute "SELECT * FROM network_metrics WHERE platform='desktop' LIMIT 5"`.

#### C.6 — Cleanup legacy
- `PinningConfig.kt` — реальные pins (после Шаг 8 — снимем).
- `Authorization` header — удалён в `signedCopy()`, проверить что нигде в коде нет hardcoded.
- TZ-2.3.X комментарии оставить как breadcrumb.
- Убрать `Unknown` PendingAction handler — все типы должны быть в `Types`.

Тест: full `gradle build`.

**Коммит этапа:** `0.6.0 / 2.4.0: clean start — E2E mandatory, RouteManager, Desktop metrics`.

---

### ЭТАП S — Server hardening (Шаг 4)

#### S.1 — Worker enforce E2E
- `server-modular/index.js`: на POST `/api` без `X-OTL-Crypto: v1` → `return new Response(json({ok:false, error:'e2e_required'}), {status: 426})`.
- Сохранить decryption logic как раньше.
- Удалить любые код-пути «принять plaintext».

#### S.2 — Stable URL `/desktop/<os>/latest`
Уже есть в 0.5.2 (см. `index.js` строка ~197). Проверить, не сломан.

#### S.3 — Log sanitization
- `auth.js verifySignedRequest`: `console.log` только на error path (`return ok:false`).
- `handlers-*.js`: убрать `console.log(action, request.body)` если есть.

#### S.4 — `request_dedup` cleanup cron
- `wrangler.toml`: добавить `[triggers] crons = ["0 4 * * *"]` (раз в сутки 4 утра UTC).
- В `index.js scheduled`: `DELETE FROM request_dedup WHERE seen_at < unixepoch() - 600`.

#### S.5 — Bump min_version
- `wrangler d1 execute otl-db --remote --command="UPDATE app_version SET min_version='2.4.0' WHERE app_scope='main'; UPDATE app_version SET min_version='0.6.0' WHERE app_scope IN ('desktop-mac','desktop-win');"`

#### S.6 — `wrangler deploy`
- **⚠ ОДОБРЕНИЕ требуется**.
- После deploy: `wrangler tail` 2 мин — нет ли ошибок.

---

### ЭТАП V — Main VPS hardening (Шаг 5)
**Требует ssh-add от юзера.**

#### V.1 — Backup current config
```sh
ssh root@45.12.239.5 'cp /etc/nginx/nginx.conf /etc/nginx/nginx.conf.bak.$(date +%s)'
```

#### V.2 — Sanitize logs
- Убрать `access_log` с `$request_uri` / `$http_authorization` если есть.
- Заменить на `'$status $bytes_sent $request_time'` минимально.

#### V.3 — SSH hardening
- `PasswordAuthentication no` (если ещё нет).
- fail2ban `[sshd]` `maxretry=3`.

#### V.4 — Verify
```sh
nginx -t && systemctl reload nginx
tail -f /var/log/nginx/access.log  # должны быть только status/bytes/time
```

---

### ЭТАП B — Backup VPS (Шаг 6)
**Требует регистрации Cloud.ru / IP+SSH key от юзера.**

См. `BACKUP_VPS_DESIGN.md` для полного deployment script. Ключевые подэтапы:

1. На VPS: `apt install nginx fail2ban ufw`.
2. Записать `nginx.conf` (stream pass-through на 45.12.239.5).
3. Firewall outbound = только Main VPS:443.
4. SSH hardening.
5. Health endpoint.
6. Verification из Mac (`curl --resolve`).

---

### ЭТАП R — RouteManager в коде (Шаг 7)

Этот этап уже частично реализован в C.4. Добавить:

- В `Secrets.kt` (Desktop) и `HttpClientFactory.kt` (Android) **реальный backup IP** — XOR-обфусц.
- Feature flag `backup_vps_enabled` в D1 — клиент при `app_status` получает; если 0 — backup IP игнорируется.
- Manual test: переключение фейлится → switching to backup → возврат.

---

### ЭТАП T — TLS pinning (Шаг 8)

#### T.1 — Снять реальные pins
```sh
echo | openssl s_client -servername api.otlhelper.com -connect api.otlhelper.com:443 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary \
  | openssl enc -base64
# Это leaf cert public key SHA256.

# Intermediate (Cloudflare):
echo | openssl s_client -servername api.otlhelper.com -connect api.otlhelper.com:443 -showcerts 2>/dev/null \
  | awk '/BEGIN CERT/{c++} c==2,/END CERT/' \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary \
  | openssl enc -base64
```

#### T.2 — Update `PinningConfig.kt`
- `PRIMARY_PIN = "<leaf_b64_sha256>"`
- `BACKUP_PIN = "<intermediate_b64_sha256>"`
- `enabledForHost` теперь возвращает `true` для `api.otlhelper.com`.

#### T.3 — Mirror на Desktop
- Создать `desktop/.../core/security/PinningConfig.kt` (если ещё нет) или вынести в `:shared`.

#### T.4 — Test
- На Mac: запустить debug build, подсунуть фейк-cert через `mitmproxy` → должна быть `SSLPeerUnverifiedException`.

---

### ЭТАП X — Release (Шаг 9)
**⚠ ОДОБРЕНИЕ требуется**.

#### X.1 — Desktop CI
```sh
gh workflow run release-desktop.yml -f version=0.6.0 -f platform=both -f mode=soft
```

#### X.2 — Android (локально на Mac, нужен keystore)
```sh
~/Documents/HELPERS/otl-release.sh
# → 1 (Android) → 2.4.0 → soft
```

#### X.3 — Verify
- R2: `wrangler r2 object list otl-app-releases --prefix desktop` → 0.6.0 EXE+DMG есть.
- D1: `wrangler d1 execute "SELECT * FROM app_version"` → current_version = новая.
- Broadcast: `wrangler tail` показывает `broadcast_app_version` push улетел.

---

### ЭТАП U — User testing (Шаг 10)
**Юзер делает.** См. checklist в плане работ.

---

### ЭТАП D — Docs (Шаг 11)

Я обновляю:
- `~/Documents/HELPERS/PROJECT_PASSPORT.md` шапка + § 1 (current state)
- `~/Documents/HELPERS/HANDOFF.md` — что было сделано в 2.4.0/0.6.0
- `~/Documents/HELPERS/TZ-NEXT.md` — пусто или новые задачи (отложенные monoliths)
- `~/Documents/HELPERS/SECRETS.md` — credentials backup VPS
- `~/Documents/HELPERS/archive/docs/` — переместить старые docs если нужно
- В `OTLHelper2/docs/audit-2026-04-30/` — обновить если что-то по факту разошлось с планом

## 3. Риски каждого этапа

| Этап | Риск | Митигация |
|---|---|---|
| C.1 (bump) | gradle cache stale | `gradle clean` перед build |
| C.2 (force E2E Android) | Live юзеры перестанут работать без 2.4.0 | force-update min_version после C.6 (S.5) |
| C.3 (desktop E2E) | Desktop ломается в production | сначала Шаг 4 (server enforce), потом Шаг 9 (release) — синхронно |
| C.4 (RouteManager) | flapping primary↔backup | hysteresis (3 успеха для возврата); no flapping в логах |
| S.1 (server enforce) | старые клиенты сразу ломаются | Шаг 9 release **до** S.1 deploy! Или: после S.1 broadcast force-update. **Критическая последовательность.** |
| V (VPS) | сломать nginx → офлайн всем | backup config; rollback via `cp nginx.conf.bak`; `nginx -t` перед reload |
| B (Backup VPS) | misconfigured firewall → unreachable | health endpoint :80 для проверки |
| T (Pinning) | реальные pins не подходят (CF rotation) | первый release с **soft-fail** (log не блок); следующий — enforce |
| X (Release) | CI fail | проверить foojay JDK 17 + ProGuard `optimize=false` всё ещё в config |

## 4. Критическая последовательность

**Order matters!**

```
Шаг 1: отчёты              (read-only)
Шаг 2: SQL reset           (production data, idempotent)
Шаг 3: client cleanup      (local code)
Шаг 8: TLS pinning         (local code, snapshot реальных pins)
Шаг 7: RouteManager        (local code, integrates C.4)
Шаг 9: Release new clients (юзеры получают 2.4.0/0.6.0)  ← ⚠ ДОЛЖНО БЫТЬ ДО Шаг 4
Шаг 4: Server enforce E2E  (старые <2.4.0 клиенты получают 426)
Шаг 5: Main VPS hardening  (logs cleanup)
Шаг 6: Backup VPS deploy   (новый узел)
Шаг 10: User testing
Шаг 11: Docs
```

**Объяснение**: enforce E2E на сервере **должно быть после** rolled out клиента 2.4.0. Иначе если сначала сделаем S.1 — все юзеры с 2.3.42 (и любые старые) получат 426 и не смогут даже скачать новый APK. Поэтому: **сначала клиент → потом enforcement**.

Альтернатива: enforce E2E как warning + grace period 24h, потом hard. Но проще: release новых, потом enforce, force-update min_version делает всё остальное.
