package com.example.otlhelper.core.feedback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * §TZ-2.3.9 — SF-2026 sound theme. Процедурно сгенерированные PCM buffer'ы
 * без внешних .ogg-ассетов. Играются через AudioTrack (STREAM mode).
 *
 * Дизайн вдохновлён высококлассными 2026-ые iOS/macOS UI-звуками:
 *  • tap      — 35мс, 1000Hz sine с быстрым decay envelope. Короткий pixel-click.
 *  • confirm  — 110мс, мажорная 3-я (C5→E5, 523→659Hz) glissando up. Успех.
 *  • receive  — 90мс, A5→C6 (880→1047Hz) — дружелюбный ping.
 *  • warn     — 90мс, 300Hz + немного 450Hz шум. Тёмный muted tone.
 *
 * Громкость консервативная (0.12-0.18 от max) — юзеру с включённым
 * «Звук интерфейса» не будет громко в тишине. Наушники тоже терпимо.
 *
 * Alloc policy: PCM буфера лазят один раз (by lazy), AudioTrack создаётся
 * и освобождается per-play. Это ~15мс overhead на play, но избегает
 * complications с state-sharing при multi-threaded playback (user может
 * triggerнуть 3 action'а подряд → 3 одновременных AudioTrack OK).
 */
internal object FeedbackSounds {

    private const val SAMPLE_RATE = 44_100

    // ── PCM generators ────────────────────────────────────────────────────

    val tapPcm: ShortArray by lazy { buildTap() }
    val confirmPcm: ShortArray by lazy { buildConfirm() }
    val receivePcm: ShortArray by lazy { buildReceive() }
    val warnPcm: ShortArray by lazy { buildWarn() }

    private fun buildTap(): ShortArray {
        // 35мс, 1000Hz sine, экспоненциальный decay — «тик» клавиатурного типа.
        val durMs = 35
        val samples = SAMPLE_RATE * durMs / 1000
        val out = ShortArray(samples)
        val freq = 1000.0
        for (i in 0 until samples) {
            val t = i / SAMPLE_RATE.toDouble()
            val env = (1.0 - i.toDouble() / samples).pow(2.0)
            val v = sin(2 * PI * freq * t) * env * 0.14
            out[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        return applyFadeEdges(out, fadeInSamples = 32, fadeOutSamples = 200)
    }

    private fun buildConfirm(): ShortArray {
        // 110мс, C5 → E5 glissando вверх (мажорная 3-я) — «успех».
        val durMs = 110
        val samples = SAMPLE_RATE * durMs / 1000
        val out = ShortArray(samples)
        val f0 = 523.25  // C5
        val f1 = 659.25  // E5
        for (i in 0 until samples) {
            val t = i / SAMPLE_RATE.toDouble()
            val progress = i.toDouble() / samples
            val freq = f0 + (f1 - f0) * progress
            // Envelope: attack быстрый, decay медленный — bell-like.
            val attack = (i.toDouble() / (SAMPLE_RATE * 0.008)).coerceAtMost(1.0)
            val decay = (1.0 - progress).pow(1.5)
            val env = attack * decay
            // Добавим октаву выше для brightness (12db тише).
            val fundamental = sin(2 * PI * freq * t)
            val harmonic = sin(2 * PI * freq * 2 * t) * 0.25
            val v = (fundamental + harmonic) * env * 0.15
            out[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        return applyFadeEdges(out, fadeInSamples = 64, fadeOutSamples = 600)
    }

    private fun buildReceive(): ShortArray {
        // 90мс, A5 → C6 glissando (малая 3-я) — «пришло сообщение».
        // Звук дружественный, не навязчивый.
        val durMs = 90
        val samples = SAMPLE_RATE * durMs / 1000
        val out = ShortArray(samples)
        val f0 = 880.0   // A5
        val f1 = 1046.5  // C6
        for (i in 0 until samples) {
            val t = i / SAMPLE_RATE.toDouble()
            val progress = i.toDouble() / samples
            val freq = f0 + (f1 - f0) * progress
            val attack = (i.toDouble() / (SAMPLE_RATE * 0.005)).coerceAtMost(1.0)
            val decay = (1.0 - progress).pow(1.8)
            val env = attack * decay
            val v = sin(2 * PI * freq * t) * env * 0.13
            out[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        return applyFadeEdges(out, fadeInSamples = 48, fadeOutSamples = 400)
    }

    private fun buildWarn(): ShortArray {
        // 90мс, 300Hz + 450Hz combo — тёмный «down» тон. Используется для
        // validation-errors / rejection.
        val durMs = 90
        val samples = SAMPLE_RATE * durMs / 1000
        val out = ShortArray(samples)
        val f1 = 300.0
        val f2 = 450.0
        for (i in 0 until samples) {
            val t = i / SAMPLE_RATE.toDouble()
            val progress = i.toDouble() / samples
            val env = (1.0 - progress).pow(1.2)
            val v = (sin(2 * PI * f1 * t) * 0.6 + sin(2 * PI * f2 * t) * 0.3) * env * 0.14
            out[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        return applyFadeEdges(out, fadeInSamples = 48, fadeOutSamples = 400)
    }

    /**
     * Fade-in/out на краях буфера — убирает click-артефакт от резкого старта
     * и резкого конца (ненулевой sample на границе → DC step → слышится
     * «щёлк»).
     */
    private fun applyFadeEdges(
        pcm: ShortArray,
        fadeInSamples: Int,
        fadeOutSamples: Int,
    ): ShortArray {
        val fi = fadeInSamples.coerceAtMost(pcm.size / 4)
        val fo = fadeOutSamples.coerceAtMost(pcm.size / 4)
        for (i in 0 until fi) {
            val k = i.toDouble() / fi
            pcm[i] = (pcm[i] * k).toInt().toShort()
        }
        for (i in 0 until fo) {
            val idx = pcm.size - 1 - i
            val k = i.toDouble() / fo
            pcm[idx] = (pcm[idx] * k).toInt().toShort()
        }
        return pcm
    }

    // ── Playback ──────────────────────────────────────────────────────────

    /**
     * Один AudioTrack на воспроизведение. Освобождается в фоне через простой
     * таймер (thread pool не нужен — UI-звуки редкие).
     */
    fun play(pcm: ShortArray) {
        try {
            val track = buildTrack(pcm.size)
            val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
            if (written <= 0) { track.release(); return }
            track.play()
            // Release вне main-thread — duration + small buffer.
            val durationMs = pcm.size * 1000L / SAMPLE_RATE + 120L
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { track.stop() }
                runCatching { track.release() }
            }, durationMs)
        } catch (_: Throwable) {
            // Audio API может бросить IllegalStateException на OEM'ах (редко).
            // UI-звук опциональный — тихо проглатываем.
        }
    }

    private fun buildTrack(bufferShorts: Int): AudioTrack {
        val bytesBuffer = bufferShorts * 2
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bytesBuffer.coerceAtLeast(AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                android.media.AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bytesBuffer.coerceAtLeast(AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )),
                AudioTrack.MODE_STREAM,
            )
        }
    }
}
