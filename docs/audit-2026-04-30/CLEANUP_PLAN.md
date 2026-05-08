# CLEANUP_PLAN — что убрать перед релизом 2.4.0 / 0.6.0

**Дата:** 2026-04-30 · **Контекст:** обратная совместимость не нужна (force-update min_version = current → старые клиенты получают 426).

## 1. Что убрать ТОЧНО (категория A — гарантированный мусор)

### A.1 Worker `server-modular/`
- **Plaintext fallback в action dispatch** — после force E2E enforcement (Шаг 4), любой POST без `X-OTL-Crypto: v1` отвергается. Убрать остатки логики, которая принимает `body` без decrypt'а.
- **`console.log('security:unsigned_request')`** на success path — keep только на enforcement-fail path.
- **Old avatar URL `/avatar/<login>`** — оставить (legacy clients), но новые загрузки писать только `/a/<public_id>`.
- **Worker `RELEASE_SECRET` старого формата** — убрать если есть legacy константа в коде.

### A.2 Android `:app`
- **`PinningConfig.PRIMARY_PIN = "<FILL_ME_AFTER_DOMAIN_MIGRATION>"`** — заменить на реальный pin (Шаг 8).
- **Plaintext fallback в `E2EInterceptor.intercept` catch** — если encrypt failed, fail-closed: throw, не proceed.
- **`AppSettings.e2eeEnabled` runtime toggle** — убрать (всегда true). E2E не опционально.
- **Legacy `Authorization: Bearer` header в коде** — после E2E forced, токен живёт только в `_auth_token` поле encrypted body. Убрать любые места где `addHeader("Authorization", ...)` (кроме самой `signedCopy().removeHeader("Authorization")`, которая остаётся для defensive cleanup).
- **Старые `local_item_id == ""` cases** — все write-actions должны проставлять local_item_id. Убедиться что нет mutation actions без поля.

### A.3 Desktop `:desktop`
- **Hardcoded `Bearer` header в `AuthSigningInterceptor.kt` Desktop** — заменить на mirror Android (token в body).
- **Отсутствующий E2EInterceptor** — добавить, не "TODO" комментарий (Шаг 3).

### A.4 Build
- **`local.properties`** — должен быть в `.gitignore`. Проверить (см. BUILD_AND_RELEASE_AUDIT).
- **APK 2.3.42 в `~/Documents/HELPERS/otl-helper-2.3.42.apk`** — переместить в `archive/binaries/` после релиза 2.4.0.

### A.5 R2 cleanup (после успешного релиза 2.4.0/0.6.0)
```sh
wrangler r2 object delete otl-app-releases/desktop/win/otl-helper-0.5.0.exe --remote
wrangler r2 object delete otl-app-releases/desktop/win/otl-helper-0.5.1.exe --remote
wrangler r2 object delete otl-app-releases/desktop/win/otl-helper-0.5.2.exe --remote
wrangler r2 object delete otl-app-releases/desktop/mac/otl-helper-0.5.0.dmg --remote
wrangler r2 object delete otl-app-releases/desktop/mac/otl-helper-0.5.1.dmg --remote
wrangler r2 object delete otl-app-releases/desktop/mac/otl-helper-0.5.2.dmg --remote
wrangler r2 object delete otl-app-releases/apk/main/otl-helper-2.3.40.apk --remote  # если есть
wrangler r2 object delete otl-app-releases/apk/main/otl-helper-2.3.41.apk --remote
wrangler r2 object delete otl-app-releases/apk/main/otl-helper-2.3.42.apk --remote
```

После cleanup: на R2 только `0.6.0` (Mac+Win) и `2.4.0` (APK) + `kcef-bundle/*`.

## 2. Что убрать ВЕРОЯТНО (категория B — нужна проверка)

### B.1 Dead code на Android
- **`AppSettings.cachedReactionVotersJson` и сеттер** — used? grep'ом.
- **`AppSettings.cachedUsersListJson`** — used? Если живой write-through cache — оставить.
- **TZ-маркеры `// §TZ-2.3.X`** в комментариях — оставить (полезный contextual breadcrumbs), но 2.4.0 коммиты пометить новой буквой `§TZ-2.4.0-CLEAN`.
- **Lottie/Coil ассеты** — какие-нибудь не использованы? Запустить `gradle :app:lint` после рефакторинга.

### B.2 Worker
- **`migrate-encrypt-r2.js`** — миграционный скрипт, выполнен или нет? Если выполнен один раз — переместить в `archive/migrations/`.
- **handler'ы для actions, которых нет в клиенте 2.4.0** — нужно сравнить ApiActions consts ↔ handlers.
- **`integrity.js`** — `play_integrity_enforced=0` в feature_flags. Если флаг не используется → убрать. Если хотим вернуться → оставить.

### B.3 Documents/HELPERS
- **`archive/docs/HANDOVER_*`** — старые хэндоверы. Уже в архиве, можно ужать в zip и удалить per-file.
- **`google update BD.js`** — в repo (HELPERS/), но это исходник Apps Script внутри Google Sheets. Может быть устарел. Сверить с актуальным после релиза.

## 3. Что НЕ убирать (категория C)

- **Replay protection `request_dedup`** — критично. Не трогать.
- **HMAC signing** — критично.
- **DNS override клиент-side** — критично для DPI.
- **Custom TrustManager** — пока pinning не активирован.
- **WorkManager `BaseSyncWorker`** — генерит RuntimeException, но нужен для базы МОЛ. Поправить retry/backoff, не удалять.
- **Encrypted at-rest на Desktop** (`~/.otldhelper/`) — критично, не трогать.
- **SQLCipher на Android** — критично.
- **Foojay JDK 17 toolchain** — нужен для ProGuard 7.2 compat.
- **`optimize=false` для ProGuard** — JCEF compat.
- **`dev.datlag:kcef:2025.03.23`** — последняя fixed версия.

## 4. Что под вопросом (категория D — нужно решение)

- **`AppSettings.cacheRetentionDays = 30`** — стоит ли менять default? Зависит от forensics-resistance стратегии.
- **`AppSettings.lockOnResumeSeconds = 0`** — biometric lock disabled by default. Включать? — это user pref, не security policy.
- **Apps Script `google update BD.js`** — рабочий механизм импорта МОЛ. Не трогать без понимания.
- **`/avatar/<login>` legacy URL** — оставлять для совместимости старых клиентов? После force-update min_version старых не будет, но Worker может legacy этот путь оставить.

## 5. Порядок безопасной чистки (привязан к шагам)

| Шаг | Что чистится |
|---|---|
| Шаг 3 | A.2 (E2E mandatory, Authorization removal, etc), A.3 (Desktop) |
| Шаг 4 | A.1 (Worker — enforce, log cleanup) |
| Шаг 5 | A.5 (R2 cleanup) |
| Шаг 9 | A.4 (locally — APK move to archive) |
| После Шага 11 | B (опционально — dead code, после успешного релиза) |

## 6. Что НЕ войдёт в 2.4.0 (отложено)

- HomeViewModel.kt monolith разбиение — слишком большой scope, отдельный sprint.
- HomeScreen.kt 17 dialogs split — то же.
- Multi-module refactor (`:shared` расширение) — частично делаем для E2E + RouteManager, остальное потом.
- Migration старых avatar URLs → opaque public_id — handlers оба, клиент шлёт новый формат.
- Apps Script авторизация ужесточение — отдельная задача.

## 7. Verification после cleanup

```sh
# В коде нет TODO/FIXME про legacy/compat:
grep -r "TODO.*compat\|FIXME.*legacy\|TZ-2.3" app/src desktop/src

# Нет hardcoded Authorization headers:
grep -r 'addHeader("Authorization"' app/src desktop/src
# Должно быть только в AuthSigningInterceptor.kt → removeHeader

# Нет plaintext fallback в Worker:
grep -r "if (!isEncrypted\|if (e2eFallback" server-modular/

# Нет placeholders в pinning:
grep "FILL_ME" app/src desktop/src
# Должно быть пусто

# All write actions используют local_item_id:
grep -r "actionType.*=.*\"send_\|actionType.*=.*\"create_\|actionType.*=.*\"vote_" app/src
# Все они должны сериализовать local_item_id в payload
```
