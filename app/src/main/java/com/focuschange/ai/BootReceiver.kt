package com.focuschange.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Try to reopen the tracking screen right after the phone powers on, so the student does
 * not have to find the app by themselves every morning.
 *
 * **This is an attempt, not a guarantee - and do not write on a slide that the app "runs
 * by itself when the machine starts".** Two reasons, both outside this code's control:
 * - Since Android 10, an app that receives a broadcast and then calls `startActivity()` from
 *   the background is blocked by the system (background activity launch). There is no official
 *   exception for `BOOT_COMPLETED`, so on many phones this launch silently fails - that is why
 *   it must be wrapped in `try/catch` and logged, while the app still opens normally by icon.
 * - Samsung's One UI (the S10+ used for the competition) has its own per-app "Autostart"
 *   switch, and Battery optimization can kill the app before the broadcast even arrives. That
 *   is a setting on the user's phone, not something the manifest can switch on.
 *
 * All the app can guarantee is this: while the Activity is in front and the screen is lit,
 * CameraX keeps the measurement session running (`FLAG_KEEP_SCREEN_ON` in MainActivity),
 * and when the screen is off there are NO numbers - Android forbids background camera use.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
        const val EXTRA_AUTO_START_SOURCE = "auto_start_source"
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_AUTO_START, true)
            putExtra(EXTRA_AUTO_START_SOURCE, intent.action)
        }
        try {
            context.startActivity(launch)
            Log.i(TAG, "Đã mở lại màn hình theo dõi sau ${intent.action}")
        } catch (e: Exception) {
            Log.w(TAG, "Hệ thống chặn không cho tự mở app sau ${intent.action}", e)
        }
    }
}
