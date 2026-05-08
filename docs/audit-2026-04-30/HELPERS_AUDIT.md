# HELPERS_AUDIT — `~/Documents/HELPERS/`

**Дата:** 2026-04-30 · **Scope:** операционная папка вне репо (ключи, скрипты, локальные сервера, docs)

⚠ В этом отчёте **никогда** не печатаются значения секретов — только **тип** и **расположение**.

## 1. Файлы верхнего уровня

| Имя | Тип | Назначение | Риск | Рекомендация |
|---|---|---|---|---|
| `PROJECT_PASSPORT.md` | doc | Главный документ — entry point | низкий | актуализировать после 2.4.0 |
| `HANDOFF.md` | doc | Снимок текущего состояния | низкий | актуализировать |
| `TZ-NEXT.md` | doc | Приоритеты для след. сессии | низкий | очистить после релиза |
| `SECRETS.md` | doc | **Все ключи / токены / пароли** | **критичный** | НЕ коммитить, restrict 600 perms |
| `DESKTOP_DIST.md` | doc | Detail про distribution | низкий | без изменений |
| `otl-release.sh` | script | Android+emergency desktop release | средний | проверить нет ли plaintext-secrets внутри |
| `otl-release-BACKUP.jks` | **keystore** | Android signing backup | **критичный** | владение только владельцу, perms 400 |
| `.otl-release-secret` | **secret** | Worker broadcast HMAC | **критичный** | perms 400, не в логи |
| `otl-helper-2.3.42.apk` | binary | Последний built APK | низкий | можно архивировать |
| `google update BD.js` | script | Apps Script (МОЛ → D1) | средний | содержит endpoint, не секрет — но проверить |

## 2. Subdirectories

| Папка | Что внутри | Риск | Рекомендация |
|---|---|---|---|
| `server-modular/` | Текущий Worker `otl-api` (JS, ~25 файлов) | средний — содержит `wrangler.toml` с binding names | не в git; secrets через wrangler, не в файлах |
| `server workflow/` | Worker `dsync` (Sheets proxy) | средний | то же |
| `e2e-keys/` | E2E backup keys (приватные) | **критичный** | perms 600/400, не в git, не печатать |
| `archive/docs/` | Старые docs (HANDOVER_*, OTLD_DESKTOP_STATE) | низкий | не читать, только история |

## 3. Ключи / секреты — типы (без значений)

| Тип секрета | Расположение | Владелец | Backup |
|---|---|---|---|
| Android signing keystore | `~/Desktop/otl-release.jks` (актуальный) + `~/Documents/HELPERS/otl-release-BACKUP.jks` | jon | оба файла нужны |
| Keystore password | `~/.gradle/gradle.properties` | jon | в SECRETS.md |
| GitHub PAT | в SECRETS.md | jon | revokable |
| Cloudflare API token | в SECRETS.md + GitHub Secrets | jon | revokable |
| RELEASE_SECRET (broadcast HMAC) | `~/Documents/HELPERS/.otl-release-secret` + Worker secret + GitHub Secret | jon | tri-mirror — нужны все 3 в синке |
| VPS SSH ed25519 | `~/.ssh/otl_vps` (private) + `.pub` + passphrase в SECRETS.md | jon | replaceable |
| VPS legacy SSH | `~/.ssh/otl_vps_setup` | jon | duplicate, можно удалить со временем |
| Self-signed VPS cert (PEM) | `/etc/nginx/otl-cert.crt` (на VPS) + `app/.../res/raw/otl_vps_cert.pem` + `desktop/.../resources/otl_vps_cert.pem` | jon | embedded в клиенты |
| E2E server private key (X25519) | Worker secret `E2E_PRIVATE_KEY_PKCS8` | Worker | плюс backup в `e2e-keys/` |
| E2E server public key (X25519) | `app/.../res/raw/otl_server_pub.bin` + аналог desktop | embedded | plain (public) |
| Apps Script auth secret | Worker secret + `google update BD.js` | jon | revokable |
| Anthropic API key | в SECRETS.md | jon | назначение неизвестно — revoke если не нужен |

## 4. Что НЕ должно попасть в git (правило)

```
~/Documents/HELPERS/SECRETS.md
~/Documents/HELPERS/.otl-release-secret
~/Documents/HELPERS/*.jks
~/Documents/HELPERS/e2e-keys/**
~/Documents/HELPERS/server-modular/.dev.vars  (если есть)
~/Documents/HELPERS/server workflow/.dev.vars  (если есть)
~/.ssh/otl_vps*
~/.gradle/gradle.properties  (содержит keystore password)
```

В корне `OTLHelper2/.gitignore` — проверено, что `local.properties` исключён, но `gradle.properties` **может попасть**. Проверить отдельно (см. `SECURITY_AUDIT.md` § 4).

## 5. Что попадает в Android APK / Desktop EXE/DMG

### APK Android (по `android/app/src/main/`)
- `res/raw/otl_vps_cert.pem` — self-signed VPS cert (public — OK to embed)
- `res/raw/otl_server_pub.bin` — E2E server public X25519 (public — OK)
- `assets/google-services.json` — Firebase config (public — OK)
- `BuildConfig` — `VERSION_NAME`, `VERSION_CODE`, `BUILD_TYPE`. **НЕ содержит секретов** (проверено grep'ом).

### Desktop EXE/DMG (по `desktop/src/main/resources/`)
- `otl_vps_cert.pem` — public cert
- (нет E2E pub-key — gap, см. P0-2 в ARCHITECTURE_AUDIT)
- `cat.json`, `darwin/`, `darwin-aarch64/`, `fonts/`, `icon.png` — assets, не secrets

### `Secrets.kt` (Desktop) — XOR-обфусцированные:
- `VPS_HOST_IP`, `BASE_URL`, `HOST_BASE`, `HOST_DOT_SUFFIX`, `CERT_PATH`. **Не криптография** — cosmetic barrier против `strings(1)` на бинаре. Реальный секрет не нужен в EXE — это просто хосты.

## 6. Риски

| Риск | Вероятность | Impact | Митигация |
|---|---|---|---|
| GitHub PAT leak (push history, accidentally в README) | средняя | high | `git secrets` pre-commit hook; revoke at first sign |
| Keystore loss | низкая | **catastrophic** (не сможем обновлять Android) | backup на 2 устройствах; cloud encrypted backup рекомендуется |
| RELEASE_SECRET out-of-sync (после ротации одной из 3 копий) | средняя | broadcast не работает | check-list при ротации; `wrangler secret list` |
| VPS SSH key compromise | низкая | high — root на VPS | passphrase + fail2ban + non-default port (опционально) |
| E2E private key leak | низкая | high — все прошлые requests декшифруемы при логе | rotation раз в 12 мес; backup в encrypted vault |
| Anthropic API key leak | unknown (не используется) | low | revoke если не нужен |

## 7. Рекомендации (P0 для «чистого старта»)

- [H-1] `chmod 600 ~/Documents/HELPERS/SECRETS.md` (если ещё не).
- [H-2] `chmod 400 ~/Documents/HELPERS/.otl-release-secret`, `*.jks`, `e2e-keys/*`.
- [H-3] Проверить `git status --ignored` в OTLHelper2 — `gradle.properties` не tracked.
- [H-4] Anthropic API key — revoke если не используется (security hygiene).
- [H-5] `~/.ssh/otl_vps_setup` (legacy) — удалить из `authorized_keys` на VPS.
- [H-6] При ротации `RELEASE_SECRET` — обновить все 3 копии (file + Worker + GH Secret) одновременно.

## 8. Что покрывает / не покрывает этот аудит

✅ Содержимое HELPERS — типы файлов, риски.
❌ Реальная криптографическая стойкость ключей — не аудит KCV/PEM формата.
❌ Backup стратегия — это операционный вопрос пользователя.
❌ Wrangler secrets list (требует `wrangler secret list` — production action, не делал read-only).
