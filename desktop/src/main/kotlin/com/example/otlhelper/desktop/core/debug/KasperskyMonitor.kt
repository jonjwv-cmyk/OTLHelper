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
        // Kaspersky
        "avp.exe", "avpui.exe", "kavfs.exe", "kxetray.exe",
        "klsvc.exe", "kavtray.exe", "klnagent.exe", "klserver.exe",
        // ESET
        "ekrn.exe", "egui.exe",
        // Avast / AVG
        "avastsvc.exe", "avastui.exe", "avgsvc.exe",
        // Norton / Symantec
        "ns.exe", "nortonsecurity.exe", "ccsvchst.exe",
        // McAfee
        "mfevtps.exe", "mcshield.exe", "mcafeesecuritysvc.exe",
        // Bitdefender
        "bdservicehost.exe", "bdagent.exe",
        // Microsoft Defender
        "msmpeng.exe", "nissrv.exe",
        // Other
        "dr.web", "drwagntd.exe",
    )

    /** Маркеры injected DLL. */
    private val AV_DLL_MARKERS = listOf(
        // Kaspersky
        "klhk", "klflt", "klsft", "mzva", "klogon", "klsihk",
        "klwtbbho", "klwfp", "klsnsr",
        // Defender
        "mpclient", "mpoav", "msmpeng",
        // ESET
        "eepw", "eelam",
        // Avast
        "ashbase", "avastfileshield",
        // Generic
        "antivirus", "endpoint",
    )

    @Volatile private var monitorThread: Thread? = null

    fun start() {
        if (monitorThread?.isAlive == true) return
        if (!BuildInfo.IS_WINDOWS) {
            DebugLogger.log(TAG, "skip: not Windows")
            return
        }

        val thread = Thread({
            try {
                // Initial deep scan
                DebugLogger.log(TAG, "===== INITIAL AV SCAN =====")
                scanProcesses(initial = true)
                scanLoadedDlls()
                logEnvironment()
                DebugLogger.log(TAG, "===== INITIAL AV SCAN END =====")

                // Periodic re-scan
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(PERIODIC_INTERVAL_MS)
                    scanProcesses(initial = false)
                }
            } catch (_: InterruptedException) {
                // graceful
            } catch (e: Throwable) {
                DebugLogger.error(TAG, "monitor thread crashed", e)
            }
        }, "OTLD-AVMonitor")
        thread.isDaemon = true
        thread.start()
        monitorThread = thread
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
}
