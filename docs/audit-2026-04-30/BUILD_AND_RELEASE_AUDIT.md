# BUILD_AND_RELEASE_AUDIT

**Дата:** 2026-04-30 · **Текущая версия:** Desktop 0.5.2 · APK 2.3.42

## 1. Build flow Android

### Локальный build
```sh
cd ~/StudioProjects/OTLHelper2
gradle :app:assembleRelease   # (debug — assembleDebug)
# Output: app/build/outputs/apk/release/app-release.apk
```

### Подписание
- `signingConfigs.release` в `app/build.gradle.kts` читает свойства из `~/.gradle/gradle.properties`:
  - `otl.signing.keystorePath`
  - `otl.signing.keystorePassword`
  - `otl.signing.keyAlias`
  - `otl.signing.keyPassword`
- Keystore: `~/Desktop/otl-release.jks` (актуальный) + backup в `~/Documents/HELPERS/`.

### Release script
```sh
~/Documents/HELPERS/otl-release.sh
# → пункт 1 (Android) → версия → soft/hard
```
Делает: `gradle assembleRelease` → upload в R2 → D1 update `app_version[main]`.

### CI для Android — НЕТ
Не сделан, т.к. release-keystore не должен попасть в GH Secrets (security policy). Альтернатива: использовать GitHub OIDC + temporary keystore — отложено.

## 2. Build flow Desktop

### Локальный build (Mac)
```sh
gradle :desktop:packageReleaseDmg     # → DMG ~102 MB
gradle :desktop:packageReleaseExe     # на Mac не работает, нужен Win runner
```

### CI flow (`.github/workflows/release-desktop.yml`)
Параллельно `build-mac` (macos-latest) + `build-win` (windows-latest):
1. Checkout
2. Foojay setup → JDK 17 (auto-provisioned)
3. `gradle :desktop:packageReleaseDmg` или `Exe`
4. ProGuard через Compose plugin (`optimize=false`)
5. Wrangler upload → R2 `otl-app-releases/desktop/<os>/<filename>`
6. Wrangler D1 update `app_version[desktop-<os>]`

После обоих: `broadcast` job → `broadcast_app_version` push.

### Время
- mac: ~5 мин
- win: ~10 мин (более медленный runner)
- Total: ~12 мин с broadcast

## 3. KCEF Chromium bundle workflow

`.github/workflows/upload-kcef-bundles.yml` — **отдельный**, ручной запуск только при апгрейде KCEF version.

```sh
gh workflow run upload-kcef-bundles.yml -f platforms=all
```

Скачивает Chromium для каждой OS, заливает в R2 как `kcef-bundle/jcef-<os>-<arch>.tar.gz`.

⚠ Mac amd64 пока не делается (нет Intel юзеров). При появлении — `-f platforms=macos-amd64`.

## 4. Что используется в CI (env / secrets)

### GitHub Actions Secrets:
- `CLOUDFLARE_API_TOKEN` — для wrangler (R2 + D1 edit scope)
- `CLOUDFLARE_ACCOUNT_ID` — `b194444f42174edc9371be1c05870e68`
- `RELEASE_SECRET` — HMAC для broadcast
- (НЕТ: keystore — на устройстве владельца)

### Workflow inputs:
- `version` — `0.5.X`
- `platform` — `mac` / `win` / `both`
- `mode` — `soft` / `hard` (soft = update prompt, hard = block)

## 5. Что РАБОТАЕТ хорошо

✅ **CI собирает обе платформы за раз** — нет drift между Mac и Win artifacts.
✅ **R2 upload + D1 update + broadcast в одном workflow** — атомарно.
✅ **Foojay auto-provisioning JDK 17** — не зависит от runner default Java.
✅ **ProGuard `optimize=false`** — обход JCEF IncompleteClassHierarchyException (документировано в комментариях).
✅ **Cache `~/.gradle/caches`** в actions — ускоряет повторные runs.

## 6. Что плохо

| # | Описание | Severity |
|---|---|---|
| BR-1 | **Нет Android CI** — release требует Mac (или mostly available developer) | medium |
| BR-2 | **Pinned actions versions через `@v*`** (не SHA) — supply-chain risk | low |
| BR-3 | **Нет SBOM / dependency scanning** | low |
| BR-4 | **Build не reproducible** — JCEF native libs могут различаться между runners | low (не критично для internal) |
| BR-5 | **Win EXE без иконки в TaskBar** до 0.5.2 (потом исправлено через ImageMagick в CI) | resolved |
| BR-6 | **`integrity_check` `binary_sha` enforcement DISABLED** — modified jar logged in без detection | low (internal team only) |
| BR-7 | **APK in `~/Documents/HELPERS/`** — не в archive/, путаница после релиза | low |
| BR-8 | **`local.properties` / `gradle.properties`** — нужно проверить, что в `.gitignore` | medium-high (potential keystore leak) |

## 7. Verification — `.gitignore` audit

Нужно проверить (read-only):
```sh
cd ~/StudioProjects/OTLHelper2
git check-ignore local.properties        # должно сказать local.properties
git check-ignore gradle.properties       # ⚠ обычно НЕ ignored
git status --ignored | grep -E 'jks|properties|secret'
```

Если `gradle.properties` НЕ в `.gitignore` — мигрировать `signingConfigs` на чтение из ENV или `extraProperties.gradle.kts.local` (последний — в gitignore).

## 8. Release pipeline rollback

Если CI release сломал prod (clients не открываются):

```sh
# 1. Откатить D1 на старую current_version
wrangler d1 execute otl-db --remote --command="UPDATE app_version SET current_version='0.5.2' WHERE app_scope IN ('desktop-mac','desktop-win');"

# 2. Если нужно — переопубликовать broadcast
curl -X POST https://api.otlhelper.com/api -H 'Content-Type: application/json' \
  --data '{"action":"broadcast_app_version","release_secret":"...","scope":"desktop-mac"}'

# 3. R2 артефакты остаются (не удалять старые версии до подтверждения, что новые работают)
```

Old EXE/DMG останутся доступны по их direct URL — `latest` указывает на `current_version` в D1.

## 9. Что нужно сделать для 2.4.0 / 0.6.0

| # | Что | Когда | Кто |
|---|---|---|---|
| 1 | Bump `versionCode` + `versionName` в Android | Шаг C.1 | я |
| 2 | Bump `version` в Desktop | Шаг C.1 | я |
| 3 | Audit `.gitignore` (BR-8) | Шаг C.1 | я (read-only) |
| 4 | (Опционально) Add Android CI workflow + GH OIDC keystore | пост-релиз | потом |
| 5 | Pin actions to SHA (BR-2) | пост-релиз | потом |
| 6 | Включить Dependabot | пост-релиз | потом |
| 7 | `wrangler r2 object delete` старых файлов | Шаг A.5 после релиза | я |
| 8 | Документировать что `binary_sha` пока DISABLED (BR-6) | Шаг 11 | я |

## 10. Готовность к релизу 2.4.0/0.6.0

| Компонент | Готовность |
|---|---|
| Android CI | 🟡 ручной (есть `otl-release.sh`) |
| Desktop CI | ✅ полностью автоматизирован |
| Сигнатуры | ✅ Android keystore + backup |
| R2 upload | ✅ через CI |
| D1 update | ✅ через CI |
| Broadcast | ✅ через CI |
| Force update | ✅ `min_version` field в `app_version` |
| Stable URLs | ✅ `/desktop/<os>/latest` |
| Rollback | ✅ через D1 UPDATE |

**Вывод**: pipeline готов; для 2.4.0/0.6.0 нет блокирующих проблем CI.
