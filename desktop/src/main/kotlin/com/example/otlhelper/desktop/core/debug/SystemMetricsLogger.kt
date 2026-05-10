package com.example.otlhelper.desktop.core.debug

import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/**
 * §0.11.14.1 — periodic snapshot системных метрик в otl-debug.log.
 *
 * Цель: каждые 30 сек писать одну строку с heap / threads / GC / uptime,
 * чтобы post-mortem видеть memory leak, thread leak, GC pressure, hang
 * (uptime без событий = freeze).
 *
 * Также пишет deltas между snapshot'ами (gc_count delta, heap delta),
 * чтобы spike-detection было простое: если за интервал GC fired 50 раз —
 * memory pressure.
 *
 * Тег [SYS_METRICS]. Pull-based, без callbacks.
 */
object SystemMetricsLogger {

    private const val TAG = "SYS_METRICS"
    private const val INTERVAL_MS = 30_000L

    @Volatile private var thread: Thread? = null
    @Volatile private var lastGcCount = 0L
    @Volatile private var lastGcTimeMs = 0L
    @Volatile private var lastHeapUsedMb = 0L

    fun start() {
        if (thread?.isAlive == true) return

        val t = Thread({
            try {
                // Initial snapshot — без deltas (нет baseline).
                snapshot(initial = true)
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(INTERVAL_MS)
                    snapshot(initial = false)
                }
            } catch (_: InterruptedException) {
                // graceful
            } catch (e: Throwable) {
                DebugLogger.error(TAG, "metrics thread crashed", e)
            }
        }, "OTLD-SysMetrics")
        t.isDaemon = true
        t.start()
        thread = t
    }

    fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun snapshot(initial: Boolean) {
        val runtime = Runtime.getRuntime()
        val heapTotal = runtime.totalMemory()
        val heapFree = runtime.freeMemory()
        val heapUsed = heapTotal - heapFree
        val heapMax = runtime.maxMemory()

        val heapUsedMb = heapUsed / (1024 * 1024)
        val heapTotalMb = heapTotal / (1024 * 1024)
        val heapMaxMb = heapMax / (1024 * 1024)
        val heapPct = if (heapMax > 0) (heapUsed * 100 / heapMax) else 0L

        val threadMx = ManagementFactory.getThreadMXBean()
        val threadCount = threadMx.threadCount
        val peakThreads = threadMx.peakThreadCount
        val daemonThreads = threadMx.daemonThreadCount

        val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
        var gcCount = 0L
        var gcTimeMs = 0L
        for (gc in gcBeans) {
            gcCount += gc.collectionCount.coerceAtLeast(0)
            gcTimeMs += gc.collectionTime.coerceAtLeast(0)
        }

        val uptimeMx = ManagementFactory.getRuntimeMXBean()
        val uptimeMs = uptimeMx.uptime
        val uptimeSec = TimeUnit.MILLISECONDS.toSeconds(uptimeMs)

        val osMx = ManagementFactory.getOperatingSystemMXBean()
        val loadAvg = osMx.systemLoadAverage  // -1 на Windows, реальное на Mac/Linux
        val procCount = osMx.availableProcessors

        // Process CPU и memory через com.sun.management.OperatingSystemMXBean если есть
        var processCpuPct = -1.0
        var systemCpuPct = -1.0
        var processMemRss = -1L
        runCatching {
            val sunOsMx = osMx as? com.sun.management.OperatingSystemMXBean
            if (sunOsMx != null) {
                processCpuPct = (sunOsMx.processCpuLoad * 100).coerceIn(-1.0, 100.0)
                systemCpuPct = (sunOsMx.cpuLoad * 100).coerceIn(-1.0, 100.0)
                processMemRss = sunOsMx.committedVirtualMemorySize
            }
        }

        if (initial) {
            DebugLogger.event(
                TAG, "snapshot" to "initial",
                "uptime_s" to uptimeSec,
                "heap_used_mb" to heapUsedMb,
                "heap_total_mb" to heapTotalMb,
                "heap_max_mb" to heapMaxMb,
                "heap_pct" to heapPct,
                "threads" to threadCount,
                "threads_daemon" to daemonThreads,
                "gc_count" to gcCount,
                "gc_total_ms" to gcTimeMs,
                "cpu_proc_pct" to "%.1f".format(processCpuPct.coerceAtLeast(0.0)),
                "cpu_sys_pct" to "%.1f".format(systemCpuPct.coerceAtLeast(0.0)),
                "procs" to procCount,
                "load_avg" to "%.2f".format(loadAvg.coerceAtLeast(0.0)),
            )
        } else {
            val gcCountDelta = gcCount - lastGcCount
            val gcTimeDelta = gcTimeMs - lastGcTimeMs
            val heapUsedDelta = heapUsedMb - lastHeapUsedMb

            DebugLogger.event(
                TAG, "snapshot" to "periodic",
                "uptime_s" to uptimeSec,
                "heap_used_mb" to heapUsedMb,
                "heap_delta_mb" to (if (heapUsedDelta >= 0) "+$heapUsedDelta" else "$heapUsedDelta"),
                "heap_pct" to heapPct,
                "threads" to threadCount,
                "threads_peak" to peakThreads,
                "gc_count_delta" to gcCountDelta,
                "gc_time_delta_ms" to gcTimeDelta,
                "cpu_proc_pct" to "%.1f".format(processCpuPct.coerceAtLeast(0.0)),
                "cpu_sys_pct" to "%.1f".format(systemCpuPct.coerceAtLeast(0.0)),
            )

            // Spike warnings
            if (gcCountDelta > 20) {
                DebugLogger.warn(TAG, "GC pressure: $gcCountDelta collections in 30s (delta_ms=$gcTimeDelta)")
            }
            if (heapPct > 85) {
                DebugLogger.warn(TAG, "heap pressure: ${heapPct}% used ($heapUsedMb / $heapMaxMb MB)")
            }
            if (threadCount > 100) {
                DebugLogger.warn(TAG, "thread count high: $threadCount (peak=$peakThreads)")
            }
            if (heapUsedDelta > 100) {
                DebugLogger.warn(TAG, "heap growth: +${heapUsedDelta}MB in 30s (possible leak)")
            }
        }

        lastGcCount = gcCount
        lastGcTimeMs = gcTimeMs
        lastHeapUsedMb = heapUsedMb
    }
}
