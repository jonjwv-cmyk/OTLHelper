# `domain/` — чистый Kotlin-слой

SF-2026 §4.1-4.3 + Phase 13 (KMP prep).

## Правила

- **Нет `android.*` и `androidx.*`** в этом пакете. Проверка:
  ```bash
  grep -rn --include='*.kt' -E 'import (android|androidx)' app/src/main/java/com/example/otlhelper/domain/
  ```
  Должен вернуть пусто (status quo на 2026-04-17 после фаз 0-12).

- **Логика через data-class + enum**, без Android-fragment'ов.

## Current state

Структура:
```
domain/
├── features/
│   ├── Features.kt          — pure Kotlin data class (10 флагов + isEnabled)
│   └── FeaturesParser.kt    — ⚠ использует org.json.JSONObject
├── limits/
│   └── Limits.kt            — pure Kotlin constants
├── model/
│   ├── FeedItem.kt          — ⚠ payload: org.json.JSONObject
│   ├── FeedItemView.kt      — pure data class
│   ├── MolRecord.kt         — pure data class
│   ├── NewsPriority.kt      — pure enum
│   ├── Reactions.kt         — pure object
│   └── Role.kt              — pure enum + extensions
├── permissions/
│   └── Permissions.kt       — pure object с функциями
└── policy/
    ├── FeedViewPolicy.kt    — ⚠ принимает org.json.JSONObject
    └── MonitoringTabPolicy.kt — pure
```

## To-KMP-migration (следующая итерация, не блокирует релиз)

1. Убрать `org.json.JSONObject` из трёх файлов ↑ — заменить на:
   - `@Serializable data class FeedItemRaw(...)` в `domain/model/` + `FeedItemRawJson` парсер в `data/mapper/`.
   - `FeatureFlagsRaw` data class для features-JSON.
   - `FeedViewPolicy.toView(item: FeedItemRaw, role: Role)` принимает чистый data-class.
2. Добавить gradle plugin `org.jetbrains.kotlinx.serialization` (уже есть в plugins, но не applied).
3. В `build.gradle.kts` apply:
   ```kotlin
   plugins {
     alias(libs.plugins.kotlin.serialization)
   }
   dependencies {
     implementation(libs.kotlinx.serialization.json)
   }
   ```
4. В `libs.versions.toml` добавить:
   ```toml
   kotlinx-serialization = "1.7.3"
   kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
   kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
   ```

## Структура для будущего `:shared` модуля

Когда решим делать KMP-десктоп:
- Переносим `domain/` целиком в `:shared/commonMain/kotlin/…/domain/`.
- Уходят с `domain/`: ничего Android-специфичного (после шага 1).
- `data/pending/PendingAction.kt` становится KMP после абстрагирования ApiClient
  (Ktor multiplatform) и PendingActionDao через `expect/actual`.
- `data/network/WsClient.kt` можно заменить на Ktor WebSocket client (KMP).
