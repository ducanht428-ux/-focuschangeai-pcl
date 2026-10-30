package com.focuschange.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Holds one study session open: keeps this process at foreground importance for as long as a
 * measurement is running, and leaves a notification the student can tap to come back.
 *
 * What this deliberately does NOT claim:
 * - It does not keep measuring in the background. CameraX is bound to the activity, so leaving the
 *   screen already stopped the frames; `MainActivity.onStop` treats that as the end of the session.
 * - It cannot pull the app back to the front by itself. Android 10+ blocks activity starts from the
 *   background, so the honest version of "tu keo app ve truoc" is this notification, not a relaunch.
 * - It is not a hiding place. The notification is visible in the shade while a session is open.
 */
class MonitorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_NOT_STICKY: a session has a start and an end. If the system recreates this service
        // with no intent there is no session behind it, and a "dang do" badge measuring nothing
        // would be a false claim on stage.
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Đang đo",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Một phiên đo của FocusChangeAI đang mở"
                setSound(null, null)
                // Nothing about a student's measurement belongs on a lock screen.
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            manager.createNotificationChannel(channel)
        }

        val backToMeasurement = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle("Đang đo mức tập trung")
            .setContentText("Chạm để quay lại màn hình đo")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(backToMeasurement)
            .setOnlyAlertOnce(true)
            .build()

        // 1073741824 == ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE (API 34). Inlined as a
        // number so the call site stays one line on every API level; below 34 the type is ignored.
        val type = if (Build.VERSION.SDK_INT >= 34) 1073741824 else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            // Android 12+ throws if this races with the app leaving the foreground, and Android 14+
            // refuses specialUse when the subtype property is missing. Losing the badge must never
            // take the measurement down with it.
            Log.w(TAG, "Khong mo duoc dich vu phien, bo qua", e)
            stopSelf()
        }
    }

    companion object {
        private const val TAG = "FocusChangeAI"
        private const val CHANNEL_ID = "monitor_session"
        private const val NOTIFICATION_ID = 4711

        /** Called when calibration opens a session. Safe from any thread. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MonitorService::class.java)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Khong khoi dong duoc dich vu phien", e)
            }
        }

        /** Called when the session ends or the screen stops. Safe from any thread. */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, MonitorService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Khong dong duoc dich vu phien", e)
            }
        }
    }
}
