package hr.sting.notifytelegram

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny in-process log bus so the UI and the notification listener share a
 * readable history of what happened.
 */
object LogBus {

    private const val TAG = "NotifyTelegram"
    private const val MAX_LINES = 300

    private val lines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var listener: ((String) -> Unit)? = null

    fun setListener(cb: ((String) -> Unit)?) {
        listener = cb
        if (cb != null) {
            synchronized(lines) { lines.toList() }.forEach(cb)
        }
    }

    fun log(message: String) {
        Log.i(TAG, message)
        val line = "${timeFormat.format(Date())}  $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        listener?.invoke(line)
    }

    fun warn(message: String, t: Throwable? = null) {
        Log.w(TAG, message, t)
        log("⚠ $message${t?.let { ": ${it.message}" } ?: ""}")
    }
}
