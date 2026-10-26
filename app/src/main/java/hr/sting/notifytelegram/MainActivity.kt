package hr.sting.notifytelegram

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var listenerStatus: TextView
    private lateinit var appsStatus: TextView
    private lateinit var editToken: EditText
    private lateinit var editChatId: EditText
    private lateinit var logView: TextView

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "main-io").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyContentInsets(findViewById(R.id.root))

        listenerStatus = findViewById(R.id.listenerStatus)
        appsStatus = findViewById(R.id.appsStatus)
        editToken = findViewById(R.id.editToken)
        editChatId = findViewById(R.id.editChatId)
        logView = findViewById(R.id.logView)

        editToken.setText(Prefs.botToken(this))
        editChatId.setText(Prefs.chatId(this))

        findViewById<Button>(R.id.btnListener).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { test() }
        findViewById<Button>(R.id.btnApps).setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        findViewById<Button>(R.id.btnActiveDump).setOnClickListener { sendActiveNotifications() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestIgnoreBatteryOptimizations() }
        findViewById<Button>(R.id.support_coffee).setOnClickListener {
            openUrl(getString(R.string.support_coffee_url))
        }
        findViewById<Button>(R.id.support_paypal).setOnClickListener {
            openUrl(getString(R.string.support_paypal_url))
        }

        findViewById<CheckBox>(R.id.cbEnabled).apply {
            isChecked = Prefs.forwardingEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setForwardingEnabled(this@MainActivity, checked)
                LogBus.log(if (checked) "Forwarding enabled" else "Forwarding disabled")
            }
        }
        findViewById<CheckBox>(R.id.cbJson).apply {
            isChecked = Prefs.includeJson(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setIncludeJson(this@MainActivity, checked)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LogBus.setListener { line -> runOnUiThread { appendLog(line) } }
        refreshStatus()
    }

    override fun onPause() {
        LogBus.setListener(null)
        super.onPause()
    }

    // ---- status ------------------------------------------------------------

    private fun refreshStatus() {
        val enabled = isNotificationAccessGranted()
        listenerStatus.text = if (enabled) {
            "Granted"
        } else {
            "Not granted — tap the button below"
        }
        listenerStatus.setTextColor(if (enabled) 0xFF1B7F3B.toInt() else 0xFFB00020.toInt())

        val selected = Prefs.packages(this)
        if (selected.isEmpty()) {
            appsStatus.text = "No apps selected — nothing will be forwarded"
            appsStatus.setTextColor(0xFFB00020.toInt())
        } else {
            val labels = selected
                .map { packageLabel(it) }
                .sortedBy { it.lowercase() }
            val shown = labels.take(6).joinToString(", ")
            val suffix = if (labels.size > 6) " …" else ""
            appsStatus.text = "${labels.size} app${if (labels.size == 1) "" else "s"} selected: $shown$suffix"
            appsStatus.setTextColor(0xFF1B7F3B.toInt())
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        return flat.split(':').any {
            ComponentName.unflattenFromString(it)?.packageName == packageName
        }
    }

    private fun packageLabel(packageName: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun appendLog(line: String) {
        val current = logView.text?.toString().orEmpty()
        val combined = if (current.isEmpty()) line else "$current\n$line"
        val trimmed = combined.lines().takeLast(120).joinToString("\n")
        logView.text = trimmed
    }

    // ---- actions -----------------------------------------------------------

    private fun save() {
        Prefs.setBotToken(this, editToken.text.toString())
        Prefs.setChatId(this, editChatId.text.toString())
        LogBus.log("Settings saved")
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    /**
     * Verifies the bot, captures the chat id from the newest message the bot
     * received when the field is empty, and sends a test message.
     */
    private fun test() {
        save()
        val token = Prefs.botToken(this)
        if (token.isEmpty()) {
            Toast.makeText(this, "Enter a bot token first", Toast.LENGTH_SHORT).show()
            return
        }
        executor.execute {
            try {
                val client = TelegramClient(token)
                val username = client.getMe()
                runOnUiThread { LogBus.log("Bot verified: @$username") }

                var chatId = Prefs.chatId(this)
                if (chatId.isEmpty()) {
                    val chat = client.latestChat()
                    if (chat == null) {
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "Bot OK. Send the bot a message (e.g. /start), then tap Test again.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        return@execute
                    }
                    chatId = chat.id
                    Prefs.setChatId(this, chatId)
                    runOnUiThread {
                        editChatId.setText(chatId)
                        LogBus.log("Captured chat id $chatId (${chat.title})")
                    }
                }

                client.sendMessage(
                    chatId,
                    "\u2705 <b>Notify → Telegram</b>\nTest message. Forwarding is wired up."
                )
                runOnUiThread { LogBus.log("Test message sent to chat $chatId") }
            } catch (e: Exception) {
                runOnUiThread {
                    LogBus.warn("Test failed", e)
                    Toast.makeText(this, "Test failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Sends a snapshot of every notification the listener can currently see. */
    private fun sendActiveNotifications() {
        val listener = NotifyNotificationListener.instance
        if (listener == null) {
            Toast.makeText(
                this,
                "Notification listener is not connected — open notification access and re-grant it",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val active = try {
            listener.activeNotifications
        } catch (e: Exception) {
            LogBus.warn("Could not read active notifications", e)
            Toast.makeText(this, "Could not read active notifications", Toast.LENGTH_LONG).show()
            return
        } ?: emptyArray()

        val token = Prefs.botToken(this)
        val chatId = Prefs.chatId(this)
        if (token.isEmpty() || chatId.isEmpty()) {
            Toast.makeText(this, "Set the bot token and chat id first", Toast.LENGTH_SHORT).show()
            return
        }

        val selected = Prefs.packages(this)
        val header = "\uD83D\uDD0E <b>Active notifications (${active.size})</b>"
        val blocks = active
            .sortedWith(compareBy({ it.packageName }, { it.postTime }))
            .mapIndexed { index, sbn -> describeActive(index + 1, sbn, selected) }

        executor.execute {
            try {
                val client = TelegramClient(token)
                packMessages(header, blocks, MAX_TELEGRAM_CHARS).forEach {
                    client.sendMessage(chatId, it)
                }
                runOnUiThread { LogBus.log("Sent ${active.size} active notifications to Telegram") }
            } catch (e: Exception) {
                runOnUiThread { LogBus.warn("Could not send active notifications", e) }
            }
        }
    }

    private fun describeActive(
        position: Int,
        sbn: StatusBarNotification,
        selected: Set<String>,
    ): String {
        val notification = sbn.notification
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val groupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        return buildString {
            append("<b>").append(position).append(". ").append(escapeHtml(sbn.packageName)).append("</b>")
            if (sbn.packageName in selected) append(" \u2713forward")
            if (groupSummary) append(" [summary]")
            append('\n')
            if (title.isNotBlank()) append("title: ").append(escapeHtml(title)).append('\n')
            if (text.isNotBlank()) append("text: ").append(escapeHtml(text)).append('\n')
            append("key: ").append(escapeHtml(sbn.key))
        }
    }

    /** Greedily packs [blocks] into messages no longer than [limit] characters. */
    private fun packMessages(header: String, blocks: List<String>, limit: Int): List<String> {
        if (blocks.isEmpty()) return listOf(header)
        val messages = ArrayList<String>()
        var current = StringBuilder(header)
        for (block in blocks) {
            val addition = "\n\n$block"
            if (current.length > header.length && current.length + addition.length > limit) {
                messages.add(current.toString())
                current = StringBuilder(block)
            } else {
                current.append(addition)
            }
        }
        messages.add(current.toString())
        return messages
    }

    @SuppressLint("BatteryLife") // personal, sideloaded tool
    private fun requestIgnoreBatteryOptimizations() {
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            startActivity(intent)
        }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val MAX_TELEGRAM_CHARS = 3500
    }
}
