package com.example.otlhelper.core.security

/**
 * SF-2026 security hardening: TLS certificate pinning.
 *
 * Защищает канал от MITM даже если rogue-сертификат попал в Android trust store
 * (корпоративный прокси, скомпрометированный WiFi, рутованный телефон).
 *
 * **Pure Kotlin** — никаких android/okhttp-зависимостей. Готово к переносу в
 * `:shared/commonMain` для KMP-десктопа (Ktor на desktop использует те же pin'ы).
 *
 * ## Как получить hash'и для production
 *
 * Для Cloudflare Workers backend'а лучше пинить **два** хеша:
 *  1. Leaf cert (текущий серверный сертификат) — точная привязка
 *  2. Intermediate CA cert — backup на случай ротации leaf'а
 *
 * Извлечение через openssl (запустить в терминале):
 * ```bash
 * # Leaf (текущий серт)
 * echo | openssl s_client -servername otl-api.jond-horizon.workers.dev \
 *   -connect otl-api.jond-horizon.workers.dev:443 2>/dev/null \
 *   | openssl x509 -pubkey -noout \
 *   | openssl pkey -pubin -outform der \
 *   | openssl dgst -sha256 -binary \
 *   | openssl enc -base64
 *
 * # Все серты в chain сразу:
 * openssl s_client -servername otl-api.jond-horizon.workers.dev \
 *   -connect otl-api.jond-horizon.workers.dev:443 -showcerts </dev/null 2>/dev/null \
 *   | awk '/BEGIN CERT/,/END CERT/' > /tmp/chain.pem
 * # Затем: вытащить pubkey каждого и посчитать sha256.
 * ```
 *
 * Полученные base64-хеши вставить в [PRIMARY_PIN] и [BACKUP_PIN].
 *
 * ## Что делать когда Cloudflare ротирует leaf-сертификат
 *
 * При ротации — клиенты с жёсткими пинами начнут получать
 * `SSLPeerUnverifiedException`. Защита:
 *  - [BACKUP_PIN] = intermediate CA (стабильнее leaf'а, годами одинаковый для Cloudflare).
 *  - Вариант 1: перед ротацией раскатить новый клиент с обновлёнными пинами.
 *  - Вариант 2: в release builds оставить enforcement, в debug — soft-fail (логировать
 *    несоответствие, но не блокировать).
 *
 * ## Безопасный режим на время сбора пинов
 *
 * Если [PRIMARY_PIN] ещё не заполнен (`<FILL_ME_…>`) — pinning **выключается
 * автоматически** ([enabledForHost]). TLS продолжает работать, but без
 * дополнительной привязки. Это позволяет:
 *  1. Собрать debug-build без pin'ов.
 *  2. Один раз запустить, извлечь реальные хеши (см. выше).
 *  3. Обновить константы и перевыпустить release.
 */
object PinningConfig {

    /** Хост, к которому применяется пининг. */
    const val HOST: String = "api.otlhelper.com"

    // §TZ-2.4.0 — pinning enforcement enabled.
    //
    // Архитектура: клиент ходит на api.otlhelper.com через DNS override на VPS
    // 45.12.239.5. VPS делает TLS termination со своим self-signed cert
    // (`otl_vps_cert.pem` в res/raw/). КЛИЕНТ ВИДИТ VPS cert, НЕ CF cert.
    // Поэтому пин на VPS SPKI, не на Cloudflare.
    //
    // PRIMARY_PIN — текущий VPS self-signed cert, exp 2036-04-16
    //   (subject=CN=api.otlhelper.com, O=OTL Helper VPS)
    // BACKUP_PIN — пока совпадает (один cert на оба VPS). Когда backup
    //   VPS получит собственный cert — заменить.
    //
    // Снять SPKI заново:
    //   cat app/src/main/res/raw/otl_vps_cert.pem | \
    //     openssl x509 -pubkey -noout | \
    //     openssl pkey -pubin -outform der | \
    //     openssl dgst -sha256 -binary | openssl enc -base64

    const val PRIMARY_PIN: String = "IvrWDtD7Arjrtu/gI0J68V+RAuuHxU3BHXiet00E5w8="

    /** Backup VPS pin (в production — отдельный cert). Пока тот же что primary. */
    const val BACKUP_PIN: String = "IvrWDtD7Arjrtu/gI0J68V+RAuuHxU3BHXiet00E5w8="

    /** Включён ли pinning для данного хоста. Только api.otlhelper.com. */
    fun enabledForHost(host: String): Boolean {
        if (host != HOST) return false
        if (PRIMARY_PIN.startsWith("<FILL_ME")) return false
        return true
    }

    /** Активные пины для host. Дубликат обнаружится OkHttp'ом — без вреда. */
    fun pinsForHost(host: String): List<String> {
        if (!enabledForHost(host)) return emptyList()
        return if (PRIMARY_PIN == BACKUP_PIN) listOf(PRIMARY_PIN) else listOf(PRIMARY_PIN, BACKUP_PIN)
    }
}
