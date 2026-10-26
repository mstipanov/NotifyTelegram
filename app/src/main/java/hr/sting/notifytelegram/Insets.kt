package hr.sting.notifytelegram

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Android 15+ draws app content edge-to-edge even when the app uses a platform
 * ActionBar, so the content sits underneath the status bar and the ActionBar.
 * Pad the root view by the system-bar insets. On older releases the decor
 * already fits the window, so nothing needs to change there.
 *
 * The insets delivered to the content view already include the ActionBar area,
 * which is why only the insets (not an extra ActionBar height) are applied.
 */
fun applyContentInsets(root: View) {
    if (Build.VERSION.SDK_INT < 35) return

    val baseLeft = root.paddingLeft
    val baseTop = root.paddingTop
    val baseRight = root.paddingRight
    val baseBottom = root.paddingBottom

    root.setOnApplyWindowInsetsListener { view, insets ->
        val bars = insets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        view.setPadding(
            baseLeft + bars.left,
            baseTop + bars.top,
            baseRight + bars.right,
            baseBottom + bars.bottom,
        )
        insets
    }
    root.requestApplyInsets()
}
