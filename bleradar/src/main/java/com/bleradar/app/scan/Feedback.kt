package com.bleradar.app.scan

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.bleradar.app.detect.Signal
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Geiger-counter style feedback for tracking a device down: beeps and buzzes that get faster
 * (and beeps higher) as you get closer. Runs on its own thread so timing stays steady.
 */
class HuntFeedback(context: Context) {
    private val vibrator = vibratorOf(context)

    /** 0 (barely heard) to 1 (right on top of it). */
    @Volatile var closeness: Float = 0f
    /** False while the signal is lost: goes quiet. */
    @Volatile var active: Boolean = false
    @Volatile var sound: Boolean = true
    @Volatile var vibrate: Boolean = true

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "hunt-feedback").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
        vibrator?.cancel()
    }

    private fun loop() {
        val track = try {
            val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // Media volume, so it's audible even with the ringer on silent.
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                // Small buffer keeps the beep in step with the buzz.
                .setBufferSizeInBytes(minBuf.coerceAtLeast(RATE / 25 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.play() }
        } catch (e: Exception) {
            null // No audio; vibration still works.
        }

        val silence = ShortArray(RATE * CHUNK_MS / 1000)
        try {
            while (running) {
                if (!active) {
                    pause(track, silence, 200)
                    continue
                }
                val c = closeness
                val beepMs = if (c > 0.85f) 35 else BEEP_MS
                if (vibrate) buzz((25 + 45 * c).toLong(), (70 + 185 * c).toInt())
                if (sound && track != null) {
                    track.write(tone(Signal.beepFrequencyHz(c), beepMs), 0, RATE * beepMs / 1000)
                } else {
                    pause(track, silence, beepMs.toLong())
                }
                // Wait out the gap in small chunks, so moving closer speeds up the next beep at once.
                val started = SystemClock.elapsedRealtime()
                while (running && active) {
                    val gap = Signal.beepIntervalMs(closeness) - beepMs
                    if (SystemClock.elapsedRealtime() - started >= gap) break
                    pause(track, silence, CHUNK_MS.toLong())
                }
            }
        } finally {
            try {
                track?.stop()
                track?.release()
            } catch (e: Exception) {
                // Already released.
            }
        }
    }

    /** Write silence to the audio track (which paces us), or just sleep when there's no audio. */
    private fun pause(track: AudioTrack?, silence: ShortArray, ms: Long) {
        if (track != null) {
            var left = (RATE * ms / 1000).toInt()
            while (left > 0 && running) {
                val n = min(left, silence.size)
                if (track.write(silence, 0, n) <= 0) {
                    Thread.sleep(ms)
                    return
                }
                left -= n
            }
        } else {
            Thread.sleep(ms)
        }
    }

    private val toneCache = HashMap<Long, ShortArray>()

    private fun tone(freq: Int, ms: Int): ShortArray {
        val roundedFreq = freq / 20 * 20
        return toneCache.getOrPut(roundedFreq.toLong() * 1000 + ms) {
            val n = RATE * ms / 1000
            val fade = RATE * 4 / 1000
            ShortArray(n) { i ->
                val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
                (sin(2 * PI * roundedFreq * i / RATE) * env * 0.7 * Short.MAX_VALUE).toInt().toShort()
            }
        }
    }

    private fun buzz(ms: Long, amplitude: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val amp = if (v.hasAmplitudeControl()) amplitude.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
        v.vibrate(VibrationEffect.createOneShot(ms, amp))
    }

    companion object {
        private const val RATE = 44_100
        private const val BEEP_MS = 50
        private const val CHUNK_MS = 20

        private val handler = Handler(Looper.getMainLooper())

        /** Short two-tone chime + double buzz: a new tracker or possible camera has appeared. */
        fun chime(context: Context, sound: Boolean, vibrate: Boolean) {
            if (sound) {
                try {
                    val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
                    handler.postDelayed({ tone.release() }, 600)
                } catch (e: RuntimeException) {
                    // Audio unavailable (e.g. during a call).
                }
            }
            if (vibrate) {
                vibratorOf(context)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180), -1))
            }
        }

        fun vibratorOf(context: Context): Vibrator? =
            if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
    }
}
