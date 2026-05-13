package com.example.otlhelper.desktop.core.debug

import com.example.otlhelper.desktop.BuildInfo
import java.io.File

/**
 * §0.11.12 — мониторинг Касперского и других AV для диагностики
 * блокировок/задержек.
 *
 * Что делает:
 *  • На startup — детектит запущенные AV-процессы (Касперский, ESET, Avast,
 *    Defender, Norton, Bitdefender, McAfee, etc.) через `tasklist`.
 *  • Snapshot DLL загруженных в наш JVM-процесс — фильтрует AV-injected
 *    модули (Касперский: klhk*, klflt*, mzva*, klsft*; Defender: MpClient*,
 *    MsMpEng*; etc.). Эти DLL = AV-инжекция в наш процесс.
 *  • Periodic re-scan каждые 60 сек — если AV запустился/выключился во
 *    время работы, увидим в логе.
 *  • Логирует в Desktop\otl-debug.log с тегом [AV_MONITOR]. Юзер шлёт
 *    файл, мы видим что мешает.
 *
 * Логирование НЕ блокирующее (фоновый поток). Если tasklist долго отвечает
 * (Касперский inspecting child process) — это тоже логируется как latency.
 */
object KasperskyMonitor {

    private const val TAG = "AV_MONITOR"
    private const val PERIODIC_INTERVAL_MS = 60_000L

    /** Маркеры процессов AV — substring match (case-insensitive). */
    private val AV_PROCESS_MARKERS = listOf(
        // Kaspersky — extended (0.11.14.1)
        "avp.exe", "avpui.exe", "avpsus.exe", "avpcom.exe",
        "kavfs.exe", "kxetray.exe", "klsvc.exe", "kavtray.exe",
        "klnagent.exe", "klserver.exe", "klwtblfs.exe", "kavfsmui.exe",
        "ksde.exe", "ksdeui.exe",  // Kaspersky Security for Business
        // ESET — extended
        "ekrn.exe", "egui.exe", "eguiproxy.exe", "ecmd.exe",
        // Avast / AVG
        "avastsvc.exe", "avastui.exe", "avgsvc.exe", "avgui.exe",
        "aswengsrv.exe", "aswidsagent.exe",
        // Norton / Symantec
        "ns.exe", "nortonsecurity.exe", "ccsvchst.exe", "nortonbackup.exe",
        // McAfee
        "mfevtps.exe", "mcshield.exe", "mcafeesecuritysvc.exe", "mfemms.exe",
        // Bitdefender
        "bdservicehost.exe", "bdagent.exe", "bdwtxag.exe", "vsserv.exe",
        // Microsoft Defender
        "msmpeng.exe", "nissrv.exe", "securityhealthservice.exe",
        "mssense.exe", "mpdefendercoreservice.exe",
        // Sophos
        "sophosagent.exe", "savservice.exe", "alsvc.exe",
        // Trend Micro
        "tmcomm.exe", "tmccsf.exe", "tmpfw.exe",
        // F-Secure
        "fshoster32.exe", "fshoster64.exe",
        // Dr.Web
        "dr.web", "drwagntd.exe", "spideragent.exe",
        // Comodo
        "cmdagent.exe", "cis.exe",
    )

    /** Маркеры injected DLL. */
    private val AV_DLL_MARKERS = listOf(
        // Kaspersky — extended (0.11.14.1)
        "klhk", "klflt", "klsft", "mzva", "klogon", "klsihk",
        "klwtbbho", "klwfp", "klsnsr", "klids", "klick",
        "klolik", "klsiff", "klogon64",
        // Defender
        "mpclient", "mpoav", "msmpeng", "mssense", "defender",
        // ESET
        "eepw", "eelam", "eclamadm", "egnt",
        // Avast
        "ashbase", "avastfileshield", "ashscript", "aswarpot",
        // Norton
        "navshext", "symantec", "norton",
        // McAfee
        "mfencbdc", "mfehidk", "naixfltr", "mcaff",
        // Bitdefender
        "bdsec", "trufos", "bdfilespy",
        // Sophos
        "savhook", "sophos",
        // Trend Micro
        "tmevtmgr", "tmpreflt",
        // Generic
        "antivirus", "endpoint", "rasapi32", "edrkmde",
    )

    @Volatile private var monitorThread: Thread? = null

    /**
     * §1.0.2 — stealth mode. Без `OTLD_DIAG=1` отключены **periodic**
     * probes (tasklist каждую минуту + latencyProbe каждые 5 мин), потому
     * что они **сами триггерят** Kaspersky PDM на корп-машинах:
     *   • `tasklist /FO CSV /NH` — process enumeration → PDM красный флаг
     *   • `cmd /c echo` — child process spawn → PDM суммирует
     *   • `DNS resolve api.otlhelper.com` + `TCP connect 127.0.0.1:1` —
     *     network probes → PDM считает botnet checkin
     *
     * Initial scan на старте остаётся (он одноразовый, не паттерн), плюс
     * один scan через 30 мин для long-running detection.
     *
     * Verbose mode: OTLD_DIAG=1 → возвращается старое поведение
     * (full periodic monitoring для диагностики у дев-юзера).
     */
    private val verboseAv: Boolean by lazy {
        System.getenv("OTLD_DIAG")?.lowercase() in setOf("1", "true", "yes", "on")
    }

    fun start() {
        if (monitorThread?.isAlive == true) return
        if (!BuildInfo.IS_WINDOWS) {
            DebugLogger.log(TAG, "skip: not Windows")
            return
        }

        val thread = Thread({
            try {
                // Initial deep scan (одноразовый, не паттерн).
                DebugLogger.log(TAG, "===== INITIAL AV SCAN =====")
                scanProcesses(initial = true)
                scanLoadedDlls()
                logEnvironment()
                logExecutableLocation()
                // §1.0.2 — initial latencyProbe только в verbose mode (cmd spawn
                // на старте + DNS + TCP — суммарно 3 suspicious actions подряд,
                // PDM hookает первое же поведение нового процесса).
                if (verboseAv) {
                    latencyProbe(initial = true)
                }
                DebugLogger.log(TAG, "===== INITIAL AV SCAN END =====")

                // §1.0.2 — periodic re-scan ОТКЛЮЧЁН по умолчанию.
                // С verbose: каждые 60s tasklist + каждые 5 мин latencyProbe (старое поведение)
                // Без verbose: один scan через 30 мин для long-running detection
                if (verboseAv) {
                    var cycle = 0
                    while (!Thread.currentThread().isInterrupted) {
                        Thread.sleep(PERIODIC_INTERVAL_MS)
                        cycle++
                        scanProcesses(initial = false)
                        if (cycle % 5 == 0) {
                            latencyProbe(initial = false)
                        }
                    }
                } else {
                    // Stealth: один scan через 30 мин — поймать если AV запустился позже
                    Thread.sleep(30 * 60 * 1000L)
                    scanProcesses(initial = false)
                    DebugLogger.log(TAG, "stealth mode: no further periodic scans")
                }
            } catch (_: InterruptedException) {
                DebugLogger.log(TAG, "monitor thread interrupted (shutdown)")
            } catch (e: Throwable) {
                DebugLogger.error(TAG, "monitor thread crashed", e)
            }
        }, "OTLD-AVMonitor")
        thread.isDaemon = true
        thread.start()
        monitorThread = thread

        // §0.11.14.1 — shutdown hook фиксирует "process terminating"
        // событие в лог. Полезно отделить graceful exit от AV-kill (где
        // лог обрывается без shutdown event).
        runCatching {
            Runtime.getRuntime().addShutdownHook(Thread({
                runCatching {
                    DebugLogger.log(TAG, "shutdown hook fired (process terminating gracefully)")
                }
            }, "OTLD-Shutdown-AV"))
        }
    }

    fun stop() {
        monitorThread?.interrupt()
        monitorThread = null
    }

    private fun scanProcesses(initial: Boolean) {
        val t0 = System.currentTimeMillis()
        val procs = runCatching {
            ProcessBuilder("tasklist", "/FO", "CSV", "/NH")
                .redirectErrorStream(true)
                .start()
                .also { it.waitFor(8, java.util.concurrent.TimeUnit.SECONDS) }
                .inputStream.bufferedReader().readLines()
        }.getOrElse {
            DebugLogger.warn(TAG, "tasklist failed: ${it.message}")
            return
        }
        val tasklistMs = System.currentTimeMillis() - t0
        if (tasklistMs > 2000) {
            DebugLogger.warn(TAG, "tasklist slow: ${tasklistMs}ms (AV inspecting?)")
        }

        val avLines = procs.filter { line ->
            val lower = line.lowercase()
            AV_PROCESS_MARKERS.any { marker -> lower.contains(marker) }
        }

        if (avLines.isEmpty()) {
            DebugLogger.log(TAG, "no AV processes running")
            return
        }

        if (initial) {
            DebugLogger.log(TAG, "AV processes detected: ${avLines.size}")
            avLines.forEach { line ->
                // CSV: "name","pid","session","sessNum","mem"
                val parts = line.split(",").map { it.trim('"') }
                val name = parts.getOrNull(0) ?: line
                val pid = parts.getOrNull(1) ?: "?"
                val mem = parts.getOrNull(4) ?: "?"
                DebugLogger.log(TAG, "  $name pid=$pid mem=$mem")
            }
        } else {
            DebugLogger.log(TAG, "periodic: ${avLines.size} AV processes still running")
        }
    }

    /**
     * Snapshot DLL загруженных в нашем JVM-процессе. Если AV injection
     * есть — увидим в списке. Используем JNA через kernel32!K32EnumProcessModules.
     */
    private fun scanLoadedDlls() {
        val ourPid = ProcessHandle.current().pid()
        DebugLogger.log(TAG, "our process pid=$ourPid")

        val dllPaths = mutableListOf<String>()
        try {
            // List all loaded modules в JVM process через /proc/self/maps аналог.
            // На Win — через VirtualAlloc enumeration или EnumProcessModules.
            // Простой способ — listfiles в proc memory map недоступен на Win,
            // используем JNA EnumProcessModules.
            val kernel32 = com.sun.jna.platform.win32.Kernel32.INSTANCE
            val psapi = com.sun.jna.platform.win32.Psapi.INSTANCE
            val hProcess = kernel32.GetCurrentProcess()
            val modules = arrayOfNulls<com.sun.jna.platform.win32.WinDef.HMODULE>(512)
            val needed = com.sun.jna.ptr.IntByReference()
            val ok = psapi.EnumProcessModules(hProcess, modules, modules.size * com.sun.jna.Native.POINTER_SIZE, needed)
            if (ok) {
                val count = needed.value / com.sun.jna.Native.POINTER_SIZE
                for (i in 0 until minOf(count, modules.size)) {
                    val m = modules[i] ?: continue
                    val nameBuf = CharArray(260)
                    val len = psapi.GetModuleFileNameExW(hProcess, m, nameBuf, nameBuf.size)
                    if (len > 0) {
                        dllPaths.add(String(nameBuf, 0, len))
                    }
                }
            }
        } catch (e: Throwable) {
            DebugLogger.warn(TAG, "EnumProcessModules failed: ${e.message}")
            return
        }

        DebugLogger.log(TAG, "loaded modules count=${dllPaths.size}")

        val avDlls = dllPaths.filter { path ->
            val name = File(path).name.lowercase()
            AV_DLL_MARKERS.any { marker -> name.contains(marker) }
        }

        if (avDlls.isEmpty()) {
            DebugLogger.log(TAG, "no AV DLLs injected (clean)")
        } else {
            DebugLogger.log(TAG, "AV DLLs INJECTED: ${avDlls.size}")
            avDlls.forEach { path ->
                DebugLogger.log(TAG, "  ${File(path).name} (${path.substringBeforeLast('\\').take(60)})")
            }
        }
    }

    private fun logEnvironment() {
        val tempPath = System.getenv("TEMP") ?: "?"
        val homePath = System.getProperty("user.home") ?: "?"
        DebugLogger.log(TAG, "env: TEMP=$tempPath HOME=$homePath")

        // Probe %TEMP% write speed (если AV scanning slows it down)
        try {
            val probe = File(System.getProperty("java.io.tmpdir"), "otl_probe_${System.currentTimeMillis()}.tmp")
            val t0 = System.currentTimeMillis()
            probe.writeText("test")
            val writeMs = System.currentTimeMillis() - t0
            val readMs = run {
                val t = System.currentTimeMillis()
                probe.readText()
                System.currentTimeMillis() - t
            }
            probe.delete()
            DebugLogger.log(TAG, "tmp_probe: write=${writeMs}ms read=${readMs}ms")
            if (writeMs > 200 || readMs > 200) {
                DebugLogger.warn(TAG, "tmp I/O slow — AV scanning?")
            }
        } catch (e: Throwable) {
            DebugLogger.warn(TAG, "tmp_probe failed: ${e.message}")
        }
    }

    /**
     * §0.11.14.1 — Где живёт наш EXE и user dir. Полезно понять:
     *  • Program Files / Program Data — AV whitelist шанс выше
     *  • User\Downloads / Desktop / Temp — каждый запуск full scan
     *  • Junction / symlink — может cause permission issues
     */
    private fun logExecutableLocation() {
        runCatching {
            val ourPath = ProcessHandle.current().info().command().orElse(null)
            DebugLogger.log(TAG, "process_cmd=${ourPath ?: "?"}")

            val cwd = System.getProperty("user.dir") ?: "?"
            DebugLogger.log(TAG, "cwd=$cwd")

            // Detect install location class
            val pathLower = (ourPath ?: "").lowercase()
            val locationClass = when {
                pathLower.contains("\\program files\\") -> "program_files"
                pathLower.contains("\\program files (x86)\\") -> "program_files_x86"
                pathLower.contains("\\programdata\\") -> "program_data"
                pathLower.contains("\\appdata\\local\\") -> "appdata_local"
                pathLower.contains("\\appdata\\roaming\\") -> "appdata_roaming"
                pathLower.contains("\\users\\") && pathLower.contains("\\desktop\\") -> "desktop"
                pathLower.contains("\\users\\") && pathLower.contains("\\downloads\\") -> "downloads"
                pathLower.contains("\\users\\") -> "user_home"
                pathLower.contains("\\temp\\") -> "temp"
                else -> "other"
            }
            DebugLogger.log(TAG, "install_location_class=$locationClass")
        }.onFailure {
            DebugLogger.warn(TAG, "logExecutableLocation failed: ${it.message}")
        }
    }

    /**
     * §0.11.14.1 — periodic latency probe для детектирования AV scanning.
     *
     * Baseline measurements:
     *  • process_spawn — `cmd /c echo` round-trip (normal ~30-80ms, AV ~200-2000ms)
     *  • file_io — write+read+delete 1KB tmp file (normal <50ms, AV >300ms)
     *  • dll_load — Native.loadLibrary("kernel32") (proxies via JNA cache,
     *    но первый call meaningfully slower if AV hooks LoadLibrary)
     *
     * Каждые 5 мин (по периодике). Spike → AV inspecting.
     */
    private fun latencyProbe(initial: Boolean) {
        val prefix = if (initial) "initial" else "periodic"

        // 1) Process spawn — самый важный probe (cscript для VBS тоже spawn)
        runCatching {
            val t0 = System.currentTimeMillis()
            val p = ProcessBuilder("cmd", "/c", "echo", "ok")
                .redirectErrorStream(true)
                .start()
            val ok = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            val ms = System.currentTimeMillis() - t0
            val exit = if (ok) p.exitValue() else -1
            DebugLogger.event(TAG, "latency_$prefix" to "process_spawn",
                "ms" to ms, "exit" to exit)
            if (ms > 1000) {
                DebugLogger.warn(TAG, "process_spawn SLOW: ${ms}ms (AV hooking CreateProcess?)")
            }
            if (!ok) p.destroyForcibly()
        }.onFailure {
            DebugLogger.warn(TAG, "process_spawn probe failed: ${it.message}")
        }

        // 2) File I/O — 4KB write + read + delete (>1KB чтобы AV scanned)
        runCatching {
            val probe = File(
                System.getProperty("java.io.tmpdir"),
                "otl_lat_${System.currentTimeMillis()}.tmp",
            )
            val data = ByteArray(4096) { (it and 0x7F).toByte() }
            val t0 = System.currentTimeMillis()
            probe.writeBytes(data)
            val writeMs = System.currentTimeMillis() - t0
            val t1 = System.currentTimeMillis()
            probe.readBytes()
            val readMs = System.currentTimeMillis() - t1
            val t2 = System.currentTimeMillis()
            probe.delete()
            val deleteMs = System.currentTimeMillis() - t2
            DebugLogger.event(TAG, "latency_$prefix" to "file_io",
                "write_ms" to writeMs, "read_ms" to readMs, "delete_ms" to deleteMs)
            if (writeMs > 300 || readMs > 300 || deleteMs > 300) {
                DebugLogger.warn(TAG, "file I/O slow: w=${writeMs} r=${readMs} d=${deleteMs} (AV scanning tmp?)")
            }
        }.onFailure {
            DebugLogger.warn(TAG, "file_io probe failed: ${it.message}")
        }

        // 3) Network probe — localhost socket connect (baseline) + DNS resolve
        runCatching {
            val t0 = System.currentTimeMillis()
            val sock = java.net.Socket()
            try {
                sock.connect(java.net.InetSocketAddress("127.0.0.1", 1), 500)
                sock.close()
            } catch (_: Throwable) {
                // Port 1 закрыт — ожидаемо, но connect attempt время важно
            }
            val connectMs = System.currentTimeMillis() - t0

            val t1 = System.currentTimeMillis()
            val resolved = runCatching {
                java.net.InetAddress.getByName("api.otlhelper.com")
            }.getOrNull()
            val dnsMs = System.currentTimeMillis() - t1

            DebugLogger.event(TAG, "latency_$prefix" to "network",
                "tcp_loopback_ms" to connectMs,
                "dns_resolve_ms" to dnsMs,
                "dns_ok" to (resolved != null),
                "dns_resolved" to (resolved?.hostAddress ?: "null"))
            if (connectMs > 500) {
                DebugLogger.warn(TAG, "loopback socket slow: ${connectMs}ms (firewall/AV?)")
            }
            if (dnsMs > 1500) {
                DebugLogger.warn(TAG, "DNS slow: ${dnsMs}ms")
            }
        }.onFailure {
            DebugLogger.warn(TAG, "network probe failed: ${it.message}")
        }
    }
}
