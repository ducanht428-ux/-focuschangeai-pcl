package com.focuschange.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Cố gắng mở lại màn hình theo dõi sau khi điện thoại vừa bật lên, để học sinh không phải tự tìm
 * app mỗi sáng.
 *
 * **Đây là cố gắng, không phải bảo đảm - và đừng viết lên slide rằng app "tự chạy khi bật máy".**
 * Hai lý do, cả hai đều nằm ngoài tầm kiểm soát của code này:
 * - Từ Android 10, một app nhận broadcast rồi gọi `startActivity()` từ nền bị hệ thống chặn
 *   (background activity launch). Không có ngoại lệ chính thức nào cho `BOOT_COMPLETED`, nên trên
 *   nhiều máy cú mở này âm thầm thất bại - vì vậy phải bọc `try/catch` và ghi log, còn app thì vẫn
 *   mở bình thường bằng icon.
 * - One UI của Samsung (chiếc S10+ dùng để đi thi) có công tắc "Autostart" riêng cho từng app, và
 *   Battery optimization có thể giết app trước cả khi broadcast tới nơi. Cái đó là thao tác trên
 *   máy của người dùng, không phải thứ manifest bật được.
 *
 * Điều app bảo đảm được chỉ là: khi Activity đã ở trước và màn hình đang sáng, CameraX giữ phiên
 * đo chạy (`FLAG_KEEP_SCREEN_ON` trong MainActivity), và khi màn hình tắt thì KHÔNG có số liệu -
 * Android cấm app nền dùng camera.
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
