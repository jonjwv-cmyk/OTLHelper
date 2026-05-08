package com.example.otlhelper.desktop.test

/**
 * §TZ-DESKTOP-TEST — Standalone тест который проверяет что JVM-SSPI Kerberos
 * через корпоративный прокси работает в РЕАЛЬНОЙ среде юзера.
 *
 * Запускать:
 *   "%LOCALAPPDATA%\OTLD Helper\runtime\bin\java.exe" \
 *     -cp "%LOCALAPPDATA%\OTLD Helper\app\*;otl-sspi-test.jar" \
 *     com.example.otlhelper.desktop.test.TestSspiMainKt
 *
 * Параметры подставляются через env-vars (чтобы корпоративные hostnames
 * не лежали в публичных источниках). Перед запуском установить:
 *   set OTL_TEST_PROXY_HOST=<corporate-proxy-fqdn>
 *   set OTL_TEST_PROXY_PORT=3128
 *   set OTL_TEST_SPN_PRIMARY=HTTP/<canonical-proxy-fqdn>
 *   set OTL_TEST_SPN_ALIAS=HTTP/<alias-fqdn>
 *   set OTL_TEST_SPN_OTHER=HTTP/<other-PTR-fqdn>
 *
 * Этот тест эмулирует ровно то что production будет делать:
 *   1. WAFFLE.getCurrent("Negotiate", spn) → SPNEGO+Kerberos token
 *   2. HttpURLConnection через proxy + Proxy-Authorization: Negotiate <token>
 *   3. Получает HTTP-код от https://otlhelper.com/api
 *
 * Если код 200 — JVM-SSPI Kerberos auth работает 100%.
 */
fun main() {
    println("=================================================")
    println("OTL JVM-SSPI Verification Test")
    println("=================================================")
    println()

    val proxyHost = System.getenv("OTL_TEST_PROXY_HOST")
        ?: error("OTL_TEST_PROXY_HOST env-var not set; see header doc")
    val proxyPort = System.getenv("OTL_TEST_PROXY_PORT")?.toIntOrNull() ?: 3128

    val spnsToTry = listOfNotNull(
        System.getenv("OTL_TEST_SPN_PRIMARY"),  // ожидаемый winner (есть Kerberos ticket)
        System.getenv("OTL_TEST_SPN_ALIAS"),    // alias (обычно нет ticket)
        System.getenv("OTL_TEST_SPN_OTHER"),    // другой PTR (нет ticket)
    )
    if (spnsToTry.isEmpty()) error("OTL_TEST_SPN_* env-vars not set; see header doc")

    var bestSpn: String? = null
    var bestToken: ByteArray? = null

    for (spn in spnsToTry) {
        println("─── Тестируем SPN: $spn ───")
        try {
            val ctx = waffle.windows.auth.impl.WindowsSecurityContextImpl.getCurrent("Negotiate", spn)
            val token = ctx.token ?: ByteArray(0)
            val firstByte = token.firstOrNull()?.toInt()?.and(0xff) ?: 0
            val first4Hex = token.take(4).joinToString("") { "%02x".format(it.toInt() and 0xff) }

            println("  Token size: ${token.size} bytes")
            println("  First 4 bytes hex: $first4Hex")

            val isSpnegoKerberos = token.size > 100 && firstByte == 0x60
            val isNtlmRaw = token.size in 30..80 && first4Hex.startsWith("4e544c4d")

            when {
                isSpnegoKerberos -> {
                    println("  ✅ SPNEGO+Kerberos AP-REQ — РЕАЛЬНЫЙ Kerberos token!")
                    if (bestSpn == null) {
                        bestSpn = spn
                        bestToken = token
                    }
                }
                isNtlmRaw -> println("  ⚠️ NTLMSSP raw (degenerate, proxy отвергнет)")
                token.size > 100 -> println("  ⚠️ Other format (token >100b but не SPNEGO header)")
                else -> println("  ❌ Degenerate (~40b SPNEGO без mechToken)")
            }
            ctx.dispose()
        } catch (t: Throwable) {
            println("  EXCEPTION: ${t.javaClass.simpleName}: ${t.message}")
        }
        println()
    }

    if (bestSpn == null || bestToken == null) {
        println("=================================================")
        println("❌ НЕТ ни одного SPN с Kerberos AP-REQ token")
        println("=================================================")
        println("Возможные причины:")
        println("  1. klist пустой — проверь: klist get \$OTL_TEST_SPN_PRIMARY")
        println("  2. WAFFLE-JNA не работает в этой JVM (редкий случай)")
        return
    }

    println("=================================================")
    println("ВЫИГРАВШИЙ SPN: $bestSpn (token ${bestToken!!.size} bytes)")
    println("=================================================")
    println()
    println("Тест #2: реальный HTTP через прокси с этим токеном")
    println()

    try {
        val proxy = java.net.Proxy(
            java.net.Proxy.Type.HTTP,
            java.net.InetSocketAddress(proxyHost, proxyPort),
        )
        val url = java.net.URL("https://otlhelper.com/api")
        val conn = url.openConnection(proxy) as java.net.HttpURLConnection
        val tokenB64 = java.util.Base64.getEncoder().encodeToString(bestToken)
        conn.setRequestProperty("Proxy-Authorization", "Negotiate $tokenB64")
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000

        val code = conn.responseCode
        println("HTTP-код от прокси+API: $code")

        when {
            code == 200 -> {
                val body = conn.inputStream.bufferedReader().readText().take(200)
                println("Response body: $body")
                println()
                println("✅ ✅ ✅ УСПЕХ! JVM-SSPI Kerberos auth через прокси РАБОТАЕТ.")
                println("    0.9.8 100% сработает идентично.")
            }
            code == 407 -> {
                println()
                println("⚠️ Прокси отверг токен (407 Proxy Auth Required)")
                println("    Хотя WAFFLE сгенерил SPNEGO+Kerberos — Squid не принял.")
                println("    Возможно channel binding или другая корп-политика.")
            }
            else -> {
                val body = (conn.errorStream ?: conn.inputStream).bufferedReader().readText().take(500)
                println("Response: $body")
                println()
                println("⚠️ Неожиданный код $code, см. response выше.")
            }
        }
    } catch (t: Throwable) {
        println("EXCEPTION на HTTP-запросе: ${t.javaClass.simpleName}: ${t.message}")
        t.stackTrace.take(5).forEach { println("  at $it") }
    }
}
