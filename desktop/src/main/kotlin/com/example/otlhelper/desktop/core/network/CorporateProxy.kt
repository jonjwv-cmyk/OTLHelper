package com.example.otlhelper.desktop.core.network

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * §TZ-DESKTOP-0.9.1 — Corporate-proxy support для Windows ноутбуков
 * подключённых к корпоративной сети с Squid + Integrated Windows Auth.
 *
 * Проблема: офисный Squid (corporate proxy host:port) возвращает 407 на любой
 * наш direct VPS-вызов (DNS-override на VPS IP не проходит — corp firewall
 * блочит TCP к non-whitelisted IPs). Браузер ходит через прокси с auto-NTLM
 * (current Windows-domain-user через SSPI).
 *
 * Решение:
 *   1. На старте app определяем системный прокси (через JVM ProxySelector
 *      или PowerShell fallback).
 *   2. Если прокси есть → переключаем OkHttp на маршрут через прокси,
 *      выключаем DNS-override + cert pinning (прокси сам резолвит и может
 *      делать TLS-interception; payload защищён E2E-шифрованием отдельным
 *      слоем выше, см. E2EInterceptor).
 *   3. На 407 challenge от прокси WAFFLE-JNA генерит Negotiate/NTLM-токен
 *      из current Windows-юзера через Win32 SSPI — без ввода паролей.
 *   4. При отсутствии Windows / WAFFLE / прокси → no-op, всё работает как
 *      и было (прямой VPS-путь).
 */
object CorporateProxy {

    private val initialized = AtomicBoolean(false)

    /** Закэшированный результат detect(). null = нет прокси / direct. */
    @Volatile
    private var cached: ProxyConfig? = null
    private val cacheReady = AtomicBoolean(false)

    /** OS-check: WAFFLE и Win-SSPI доступны только на Windows. */
    val isWindows: Boolean by lazy {
        System.getProperty("os.name")?.lowercase()?.contains("windows") == true
    }

    /**
     * Init, который надо вызвать раз на старте приложения (Main.kt).
     * Включает JVM ProxySelector чтобы он читал Windows IE settings + WPAD.
     */
    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        SspiLogger.log("CorporateProxy.init: isWindows=$isWindows, log=${SspiLogger.logPath()}")
        if (isWindows) {
            // §TZ-DESKTOP-0.9.1 — JVM auto-detect Windows system proxy
            // (читает IE Internet Settings + WPAD/PAC discovery).
            System.setProperty("java.net.useSystemProxies", "true")
            // §TZ-DESKTOP-0.9.1 — Java по дефолту запрещает Basic/NTLM auth для
            // CONNECT-туннеля (HTTPS via proxy). Очищаем чтобы наш custom
            // authenticator мог отрабатывать.
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "")
            System.setProperty("jdk.http.auth.proxying.disabledSchemes", "")
            SspiLogger.log("CorporateProxy.init: Windows system properties configured")
        }
    }

    /**
     * Детектит системный прокси для конкретного URI. Кэширует результат —
     * повторные вызовы возвращают то же без повторного PowerShell-инвока.
     *
     * Стратегия:
     *   1. Java [ProxySelector] (читает реестр / WPAD при useSystemProxies=true)
     *   2. Fallback: PowerShell `[System.Net.WebRequest]::DefaultWebProxy.GetProxy(...)`
     *      — на некоторых машинах JVM не подхватывает PAC, а .NET stack читает
     *      шире (включая GPO / Edge policy).
     *   3. Если оба ничего не отдают → null (direct).
     */
    fun detect(targetUrl: String = "https://otlhelper.com/api"): ProxyConfig? {
        if (cacheReady.get()) return cached
        synchronized(this) {
            if (cacheReady.get()) return cached
            val result = doDetect(targetUrl)
            cached = result
            cacheReady.set(true)
            return result
        }
    }

    /** Очистить cache (если юзер вручную поменял прокси в Settings). */
    fun reset() {
        synchronized(this) {
            cached = null
            cacheReady.set(false)
        }
    }

    private fun doDetect(targetUrl: String): ProxyConfig? {
        SspiLogger.log("CorporateProxy.detect: probing $targetUrl, isWindows=$isWindows")
        // 1. JVM ProxySelector (с useSystemProxies=true).
        runCatching {
            val uri = URI.create(targetUrl)
            val proxies = ProxySelector.getDefault().select(uri)
            SspiLogger.log("CorporateProxy.detect: JVM ProxySelector returned ${proxies.size} entries: $proxies")
            for (p in proxies) {
                if (p.type() == Proxy.Type.HTTP) {
                    val addr = p.address() as? InetSocketAddress ?: continue
                    val hp = "${addr.hostString}:${addr.port}"
                    SspiLogger.log("CorporateProxy.detect: ✓ JVM ProxySelector found HTTP proxy $hp")
                    return ProxyConfig(host = addr.hostString, port = addr.port, source = "ProxySelector ($hp)")
                }
            }
        }.onFailure { SspiLogger.log("CorporateProxy.detect: JVM ProxySelector exception: ${it.message}") }

        // 2. PowerShell fallback (only on Windows).
        if (isWindows) {
            runCatching {
                SspiLogger.log("CorporateProxy.detect: trying PowerShell fallback")
                val ps = ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-Command",
                    "[System.Net.WebRequest]::DefaultWebProxy.GetProxy('$targetUrl').AbsoluteUri",
                ).redirectErrorStream(true).start()
                ps.waitFor()
                val out = ps.inputStream.bufferedReader().readText().trim()
                SspiLogger.log("CorporateProxy.detect: PowerShell stdout='$out' exit=${ps.exitValue()}")
                if (out.isNotEmpty() && out.startsWith("http")) {
                    val uri = URI.create(out)
                    val host = uri.host ?: return@runCatching null
                    val port = if (uri.port > 0) uri.port else 3128
                    SspiLogger.log("CorporateProxy.detect: ✓ PowerShell found proxy $host:$port")
                    return ProxyConfig(host = host, port = port, source = "PowerShell ($out)")
                }
                null
            }.onFailure { SspiLogger.log("CorporateProxy.detect: PowerShell exception: ${it.message}") }
        }
        SspiLogger.log("CorporateProxy.detect: ✗ no proxy found, falling back to direct VPS")
        return null
    }

    /**
     * Найденный прокси. Source — для diagnostic-логов чтобы понимать откуда
     * взяли (system / PowerShell / manual override).
     *
     * §TZ-DESKTOP-0.9.6+0.9.7 — `spnCandidates` = список FQDN для Kerberos SPN.
     * Java `InetAddress.canonicalHostName` ОКАЗАЛСЯ ненадёжен — на машине юзера
     * для внутреннего IP он вернул один FQDN вместо реального canonical hostname.
     * Windows-API (`ping -a`) даёт правильный canonical. Пробуем оба источника
     * + оригинальное имя как fallback.
     */
    data class ProxyConfig(val host: String, val port: Int, val source: String) {
        /**
         * Список FQDN-кандидатов для SPN Kerberos. Authenticator пробует их по
         * порядку: первый что даёт реальный SPNEGO+Kerberos token (>200b,
         * начинается с 0x60) считается победителем.
         */
        val spnCandidates: List<String> by lazy {
            val candidates = LinkedHashSet<String>()
            // 1. Windows ping -a + nslookup PTR (=Windows DNS API; то же что использует браузер)
            //    Может вернуть несколько имён (multi-PTR IPs).
            candidates.addAll(windowsReverseDns(host))
            // 2. Original hostname как configured
            candidates += host
            // 3. Java InetAddress canonical (часто неточен на multi-A-records)
            runCatching {
                java.net.InetAddress.getByName(host).canonicalHostName
            }.getOrNull()?.let { candidates += it }
            val list = candidates.toList()
            SspiLogger.log("ProxyConfig.spnCandidates: $list")
            list
        }

        /** Backwards-compat: первый кандидат как single canonical. */
        @Deprecated("use spnCandidates instead", level = DeprecationLevel.HIDDEN)
        val canonicalHost: String get() = spnCandidates.firstOrNull() ?: host

        fun toJavaProxy(): Proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
        override fun toString(): String = "$host:$port [$source]"
    }
}

/**
 * §TZ-DESKTOP-0.9.7+0.9.8 — Reverse DNS lookup через Windows-native tools.
 *
 * 0.9.7 баг: regex по `"Pinging"` / `"Обмен пакетами с"` не матчил RU-вывод
 * потому что Java читает stdout с дефолтным charset'ом (UTF-8) а cmd.exe
 * пишет в CP866 → cyrillic превращается в мусор.
 *
 * 0.9.8 фикс: ASCII-only regex который ищет FQDN перед `[IP]` независимо
 * от языка вывода. Plus добавили fallback через `nslookup -type=PTR <ip>`
 * который возвращает ВСЕ PTR-записи (на multi-A IPs их обычно несколько).
 *
 * Возвращает СПИСОК candidate FQDN (в порядке: ping result, nslookup PTRs).
 * `ping` и `nslookup` — Microsoft-signed system tools, не блокируются EDR.
 */
private fun windowsReverseDns(host: String): List<String> {
    if (!CorporateProxy.isWindows) return emptyList()
    val candidates = LinkedHashSet<String>()

    // 1. ping -a — даёт основное canonical имя (то же что использует Windows API)
    runCatching {
        val proc = ProcessBuilder("ping", "-a", "-n", "1", "-w", "1500", host)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        // §0.9.8 — Encoding-independent: ищем FQDN-like ASCII string перед "[<ip>]"
        // Cyrillic мусор от CP866 mojibake пропускается т.к. regex принимает только ASCII.
        val fqdnPattern = Regex("""([a-zA-Z][a-zA-Z0-9.-]*\.[a-zA-Z][a-zA-Z0-9.-]+)\s+\[\d+\.\d+\.\d+\.\d+]""")
        fqdnPattern.findAll(output).forEach { m ->
            val fqdn = m.groupValues[1].trim().trimEnd('.')
            if (fqdn.isNotEmpty() && fqdn != host) {
                SspiLogger.log("windowsReverseDns/ping: $host → $fqdn")
                candidates += fqdn
            }
        }
    }.onFailure { SspiLogger.log("windowsReverseDns/ping exception: ${it.message}") }

    // 2. nslookup -type=PTR <ip> — даёт ВСЕ PTR записи (multi-PTR IPs обычные в корпах)
    runCatching {
        val ip = java.net.InetAddress.getByName(host).hostAddress
        val proc = ProcessBuilder("nslookup", "-type=PTR", ip)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        // Match "name = X" lines (works in any locale — keys are ASCII)
        val namePattern = Regex("""name\s*=\s*([a-zA-Z][a-zA-Z0-9.-]+)""")
        namePattern.findAll(output).forEach { m ->
            val name = m.groupValues[1].trim().trimEnd('.')
            if (name.isNotEmpty() && name != host) {
                SspiLogger.log("windowsReverseDns/nslookup PTR: $ip → $name")
                candidates += name
            }
        }
    }.onFailure { SspiLogger.log("windowsReverseDns/nslookup exception: ${it.message}") }

    return candidates.toList()
}
