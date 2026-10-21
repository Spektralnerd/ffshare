package com.caydey.ffshare

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * Keeps long conversions alive. While this foreground service runs, the app process has foreground
 * priority and holds a partial wake lock, so ffmpeg goes on with the screen off or another app in
 * front - for hours or days. The ffmpeg sessions themselves are started by MediaCompressor; the
 * service only owns the wake lock and the progress notification (with a two-step cancel button).
 */
class EncodeService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // arrives after the start command (same queue), so startForeground() has already run;
                // stopService() right after startForegroundService() would crash the app
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                // first tap only arms the button, a mistap must not throw away days of work
                cancelArmedUntil = System.currentTimeMillis() + CANCEL_ARM_MS
                update(this, lastText, lastPermille)
                return START_NOT_STICKY
            }
            ACTION_CANCEL_CONFIRMED -> {
                Timber.d("Cancel confirmed in the notification")
                cancelArmedUntil = 0
                val hook = cancelHook
                if (hook != null) hook() else stopSelf()
                return START_NOT_STICKY
            }
        }
        val notification = buildProgressNotification(this, intent?.getStringExtra(EXTRA_TEXT) ?: lastText, -1)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock()
        return START_NOT_STICKY
    }

    // No timeout: a batch of episodes can run for weeks. Released in onDestroy, and it dies with the process.
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FFShareDeint:encode").apply {
            setReferenceCounted(false)
            acquire()
        }
        Timber.d("Wake lock acquired")
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Timber.d("Encode service stopped, wake lock released")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "encode"
        private const val NOTIFICATION_ID = 4171
        private const val RESULT_NOTIFICATION_ID = 4172
        private const val ACTION_START = "com.caydey.ffshare.action.ENCODE_START"
        private const val ACTION_STOP = "com.caydey.ffshare.action.ENCODE_STOP"
        private const val ACTION_CANCEL = "com.caydey.ffshare.action.ENCODE_CANCEL"
        private const val ACTION_CANCEL_CONFIRMED = "com.caydey.ffshare.action.ENCODE_CANCEL_CONFIRMED"
        private const val EXTRA_TEXT = "text"
        private const val CANCEL_ARM_MS = 10_000L

        /** Set by MediaCompressor while a conversion runs; the confirmed cancel button calls it */
        @Volatile
        var cancelHook: (() -> Unit)? = null

        @Volatile private var lastText = ""
        @Volatile private var lastPermille = -1
        @Volatile private var cancelArmedUntil = 0L

        /** Starts the service; must be called while the app is in the foreground. False on failure. */
        fun start(context: Context, text: String): Boolean {
            lastText = text
            lastPermille = -1
            cancelArmedUntil = 0
            return try {
                val intent = Intent(context, EncodeService::class.java).setAction(ACTION_START).putExtra(EXTRA_TEXT, text)
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                Timber.w(e, "Could not start the foreground service")
                false
            }
        }

        /** New progress text; permille 0..1000, or -1 for an indeterminate bar */
        fun update(context: Context, text: String, permille: Int) {
            lastText = text
            lastPermille = permille
            notify(context, NOTIFICATION_ID, buildProgressNotification(context, text, permille))
        }

        fun stop(context: Context) {
            cancelArmedUntil = 0
            try {
                // allowed: the app counts as foreground while its foreground service runs
                context.startService(Intent(context, EncodeService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                Timber.w(e, "Stop command not delivered, stopping the service directly")
                context.stopService(Intent(context, EncodeService::class.java))
            }
        }

        /** Separate, dismissable notification for the end of a long conversion */
        fun notifyResult(context: Context, title: String, text: String) {
            ensureChannel(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            notify(context, RESULT_NOTIFICATION_ID, notification)
        }

        private fun notify(context: Context, id: Int, notification: Notification) {
            // Android 13+: without the permission the progress only shows in the task manager of the
            // quick settings; the service itself runs anyway
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            try {
                NotificationManagerCompat.from(context).notify(id, notification)
            } catch (e: SecurityException) {
                Timber.w(e, "Notification not allowed")
            }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_encode),
                        NotificationManager.IMPORTANCE_LOW)
                    channel.description = context.getString(R.string.notification_channel_encode_description)
                    manager.createNotificationChannel(channel)
                }
            }
        }

        private fun buildProgressNotification(context: Context, text: String, permille: Int): Notification {
            ensureChannel(context)
            val armed = System.currentTimeMillis() < cancelArmedUntil
            val cancelIntent = PendingIntent.getService(context, if (armed) 2 else 1,
                Intent(context, EncodeService::class.java).setAction(if (armed) ACTION_CANCEL_CONFIRMED else ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val body = if (armed) context.getString(R.string.notification_cancel_armed, text) else text
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.notification_encoding_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, context.getString(if (armed) R.string.notification_cancel_confirm else R.string.cancel_ffmpeg), cancelIntent)
            if (permille in 0..1000) {
                builder.setProgress(1000, permille, false)
            } else {
                builder.setProgress(0, 0, true)
            }
            return builder.build()
        }
    }
}
