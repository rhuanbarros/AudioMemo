package com.example.audiomemo.features.transcript.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.audiomemo.MainActivity
import com.example.audiomemo.R

object NotificationHelper {

    /** Silent channel for the ongoing "recording in progress" notification (unchanged). */
    const val CHANNEL_ID = "audio_recording_channel"

    /**
     * (am-hotfix, never-stop-recording): high-priority, distinct-sound channel for the handful of
     * stop events that are still real Android restrictions (low storage, permission revoked,
     * hardware error unrecoverable even after immediate-recovery attempts) — deliberately NEVER
     * the silent [CHANNEL_ID] used for the ongoing recording notification, so the owner actually
     * notices when recording genuinely stopped instead of it blending into the silent, always-on
     * notification they've learned to ignore.
     */
    const val ALERT_CHANNEL_ID = "audio_recording_alert_channel"

    const val NOTIFICATION_ID = 1001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val recordingChannel = NotificationChannel(
                CHANNEL_ID,
                "Audio Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while ${context.getString(R.string.app_name)} is recording audio"
            }
            notificationManager(context).createNotificationChannel(recordingChannel)

            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Recording Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when recording genuinely stops (low storage, permission " +
                    "revoked, or an unrecoverable hardware error)"
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            notificationManager(context).createNotificationChannel(alertChannel)
        }
    }

    // ── Recording states ───────────────────────────────────────────────────────

    fun buildForegroundNotification(context: Context, stopIntent: PendingIntent): Notification =
        buildOngoing(context, "Recording in progress...", stopIntent = stopIntent)

    fun buildPausedMediaButtonNotification(
        context: Context,
        resumeIntent: PendingIntent,
        stopIntent: PendingIntent
    ): Notification = buildPaused(context, "Paused – Headset button", resumeIntent, stopIntent)

    fun buildMicSourceChangedNotification(
        context: Context,
        sourceName: String,
        stopIntent: PendingIntent
    ): Notification = buildOngoing(
        context,
        "Microphone switched to $sourceName",
        stopIntent = stopIntent
    )

    fun buildSilenceWarningNotification(
        context: Context,
        stopIntent: PendingIntent
    ): Notification = buildOngoing(
        context,
        "No audio detected – Check microphone",
        stopIntent = stopIntent
    )

    // ── Alert states (am-hotfix, never-stop-recording) — real Android restrictions only ────────

    fun buildPermissionRevokedNotification(context: Context): Notification =
        buildAlert(context, "Recording stopped – Microphone permission was revoked")

    fun buildHardwareErrorNotification(context: Context): Notification =
        buildAlert(context, "Recording stopped – Microphone error (recovery attempts exhausted)")

    fun buildLowStorageNotification(context: Context): Notification =
        buildAlert(context, "Recording stopped – Low storage")

    // ── Helpers ────────────────────────────────────────────────────────────────

    fun updateNotification(context: Context, notification: Notification) {
        notificationManager(context).notify(NOTIFICATION_ID, notification)
    }

    private fun buildOngoing(
        context: Context,
        text: String,
        stopIntent: PendingIntent
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(contentPendingIntent(context))
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .build()
    }

    private fun buildPaused(
        context: Context,
        text: String,
        resumeIntent: PendingIntent,
        stopIntent: PendingIntent
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(contentPendingIntent(context))
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_play, "Resume", resumeIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .build()
    }

    /**
     * (am-hotfix, never-stop-recording): builder shared by every real-stop alert. Uses
     * [ALERT_CHANNEL_ID] (IMPORTANCE_HIGH, distinct sound) — never [CHANNEL_ID] — and also sets
     * priority/sound directly on the builder so the alert is audible even on pre-Android-8
     * devices, where notification channels don't exist and `NotificationCompat` falls back to
     * these builder-level fields instead.
     */
    private fun buildAlert(context: Context, text: String): Notification {
        val contentIntent = contentPendingIntent(context)
        return NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(contentIntent)
            .setOngoing(false)
            .setAutoCancel(true)
            .setSilent(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .build()
    }

    private fun contentPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun notificationManager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
