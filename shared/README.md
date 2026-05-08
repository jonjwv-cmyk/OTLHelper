# Shared Module

Pure Kotlin contracts used by both Android and desktop clients.

## What Belongs Here

- role model and role parsing (`auth/Role.kt`, `RolePolicies.kt`)
- permission matrix (`auth/Permissions.kt`)
- server action names and field constants (`api/ApiActions.kt`, `api/ApiFields.kt`)
- shared DTOs and API result wrapping (`api/dto/UserDto.kt`, `api/ApiResult.kt`)
- platform-free crypto helpers — AES-256-GCM blob decryption used by both
  clients to read R2-stored encrypted blobs (`security/BlobCrypto.kt`)

## Rules

1. No Android, Compose, desktop, OkHttp, Room, or JSON dependencies.
   Currently only `junit` for tests.
2. Keep APIs stable and covered by unit tests
   (see `src/test/kotlin/.../*Test.kt`).
3. Add compatibility facades in `app/domain` or `desktop/model` during
   migration instead of rewriting large UI areas in one pass.
