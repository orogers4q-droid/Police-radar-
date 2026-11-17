package com.policeradar.app.scan

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.policeradar.app.MainActivity
import com.policeradar.app.R
import com.policeradar.app.data.AppSettings
import com.policeradar.app.detect.Observation
import com.policeradar.app.detect.Proximity
import com.policeradar.app.detect.Signature
import java.util.Locale

/** Shows alert notifications and plays the sound, vibration and spoken warning. */
class AlertNotifier(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Emergency vehicle alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Pops up when police, fire, ambulance equipment or a Flock camera is detected nearby"
                // Sound and vibration are played by the app so they follow the in-app settings.
                setSound(null, null)
                enableVibration(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SCANNER, "Scanner running", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the app is scanning"
            },
        )
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }
    }

    fun scanningNotification(): android.app.Notification {
        val stop = PendingIntent.getService(
            context, 1,
            Intent(context, ScannerService::class.java).setAction(ScannerService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CH_SCANNER)
            .setSmallIcon(R.drawable.ic_radar)
            .setContentTitle("Police Radar is scanning")
            .setContentText("Listening for police, fire, ambulances and Flock cameras")
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Stop", stop)
            .build()
    }

    fun alert(obs: Observation, signature: Signature, settings: AppSettings) {
        val proximity = Proximity.fromRssi(obs.rssi)
        val headline = alertHeadline(signature)
        val detail = buildString {
            append(proximity.label).append(" · ").append(signature.label)
            obs.name?.let { append(" · \"").append(it).append('"') }
        }

        if (canNotify()) {
            val n = NotificationCompat.Builder(context, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_radar)
                .setContentTitle("${signature.category.emoji} $headline")
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$detail\n${signature.description}"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
            try {
                NotificationManagerCompat.from(context).notify(obs.key.hashCode(), n)
            } catch (e: SecurityException) {
                // Notification permission was revoked; the sound and voice still work.
            }
        }
        if (settings.sound) playSiren()
        if (settings.vibrate) vibrate()
        if (settings.voice && ttsReady) {
            tts?.speak(
                "$headline, ${proximity.label.lowercase()}.",
                TextToSpeech.QUEUE_FLUSH, null, "alert",
            )
        }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun playSiren() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            tone.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500)
            handler.postDelayed({ tone.release() }, 1700)
        } catch (e: RuntimeException) {
            // Audio unavailable (e.g. during a call).
        }
    }

    private fun vibrate() {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 150, 400, 150, 400), -1))
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        /** "Police nearby", or "Possible ambulance nearby" for weaker matches. */
        fun alertHeadline(signature: Signature): String {
            val c = signature.category
            val what = if (c == com.policeradar.app.detect.Category.CAMERA) c.inSentence else c.label
            return if (signature.confidence == com.policeradar.app.detect.Confidence.HIGH) "$what nearby"
            else "Possible ${c.inSentence} nearby"
        }

        const val CH_ALERTS = "alerts"
        const val CH_SCANNER = "scanner"
    }
}
