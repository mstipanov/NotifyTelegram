package hr.sting.notifytelegram

import android.app.Notification
import android.app.Person
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns notifications from the selected apps into Telegram messages.
 *
 * Messages are read-only: a readable headline plus, optionally, a
 * machine-parseable JSON block for an agent that reads the destination chat.
 * Notification updates that keep the same content are dropped, so a chatty
 * conversation notification does not spam the chat.
 */
object NotificationForwarder {

    private const val MAX_TEXT = 1500

    /** notification key -> content signature of the last forwarded update. */
    private val lastSent = ConcurrentHashMap<String, Int>()

    private val appLabels = ConcurrentHashMap<String, String>()

    fun forget(key: String) {
        lastSent.remove(key)
    }

    /**
     * Extracts, de-duplicates and forwards one notification. Call this on a
     * worker thread — it performs the network request.
     */
    fun maybeForward(context: Context, sbn: StatusBarNotification) {
        if (!Prefs.forwardingEnabled(context)) return
        if (sbn.packageName == context.packageName) return
        if (sbn.packageName !in Prefs.packages(context)) return

        val notification = sbn.notification ?: return
        // "N new messages" rollups have no content of their own.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val content = extract(context, sbn)
        if (content.title.isBlank() && content.text.isBlank()) return

        // Skip notification updates that did not change the content.
        val signature = content.signature()
        if (lastSent.put(sbn.key, signature) == signature) return

        val token = Prefs.botToken(context)
        val chatId = Prefs.chatId(context)
        if (token.isEmpty() || chatId.isEmpty()) {
            lastSent.remove(sbn.key)
            LogBus.warn("${content.app} notification seen but the bot token / chat id is not set")
            return
        }

        try {
            TelegramClient(token).sendMessage(
                chatId,
                content.toTelegramHtml(includeJson = Prefs.includeJson(context)),
            )
            LogBus.log("Forwarded ${content.app}: ${content.summary()}")
        } catch (e: Exception) {
            // Let a later update / repost retry the send.
            lastSent.remove(sbn.key)
            LogBus.warn("Failed to forward a ${content.app} notification", e)
        }
    }

    // ---- extraction --------------------------------------------------------

    private fun extract(context: Context, sbn: StatusBarNotification): Content {
        val notification = sbn.notification
        val extras = notification.extras

        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (text.isBlank()) {
            text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        }
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        var channel = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        var sender = ""

        // MessagingStyle (Slack, chats) keeps the sender and the actual message
        // text in EXTRA_MESSAGES; read the newest entry from there. The public
        // MessagingStyle.extractMessagingStyleFromNotification helper is a hidden
        // API, so the raw extras bundles are used instead.
        @Suppress("DEPRECATION") // getParcelableArray(String) is the only overload available at minSdk 26
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        (messages?.lastOrNull() as? Bundle)?.let { last ->
            last.getCharSequence("text")?.toString()?.let { if (it.isNotBlank()) text = it }
            val person = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                (last.getParcelable("sender_person") as? Person)
            } else {
                null
            }
            val name = person?.name?.toString()
                ?: last.getCharSequence("sender")?.toString()
            if (!name.isNullOrBlank()) sender = name
        }

        // InboxStyle collections (Outlook digest) fall back to the line list.
        if (text.isBlank()) {
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            if (lines != null && lines.isNotEmpty()) {
                text = lines.joinToString("\n") { it?.toString().orEmpty() }
            }
        }

        if (sender.isBlank()) sender = title

        return Content(
            source = sourceOf(sbn.packageName),
            packageName = sbn.packageName,
            app = appLabel(context, sbn.packageName),
            channel = channel,
            sender = sender,
            title = title,
            text = text,
            subText = subText,
            postedAt = Instant.ofEpochMilli(sbn.postTime).toString(),
        )
    }

    private fun appLabel(context: Context, packageName: String): String =
        appLabels.getOrPut(packageName) {
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName)
        }

    private fun sourceOf(packageName: String): String = when {
        packageName.equals("com.Slack", ignoreCase = true) -> "slack"
        packageName.contains("outlook", ignoreCase = true) -> "outlook"
        else -> packageName
    }

    private data class Content(
        val source: String,
        val packageName: String,
        val app: String,
        val channel: String,
        val sender: String,
        val title: String,
        val text: String,
        val subText: String,
        val postedAt: String,
    ) {
        /** Content identity used to skip unchanged notification updates. */
        fun signature(): Int =
            listOf(packageName, channel, sender, title, text, subText)
                .joinToString("\u0000")
                .hashCode()

        fun summary(): String =
            listOf(title, text).filter { it.isNotBlank() }.joinToString(" — ").take(140)

        fun toTelegramHtml(includeJson: Boolean): String {
            val clampedText = if (text.length > MAX_TEXT) text.take(MAX_TEXT) + "…" else text
            val header = buildString {
                append("\uD83D\uDCE8 <b>").append(escapeHtml(app)).append("</b>")
                if (channel.isNotBlank()) append(" — ").append(escapeHtml(channel))
            }
            val body = buildString {
                if (sender.isNotBlank() && sender != title) {
                    append("<b>").append(escapeHtml(sender)).append("</b>")
                    if (clampedText.isNotBlank()) append(": ").append(escapeHtml(clampedText))
                } else if (title.isNotBlank()) {
                    append("<b>").append(escapeHtml(title)).append("</b>")
                    if (clampedText.isNotBlank() && clampedText != title) {
                        append('\n').append(escapeHtml(clampedText))
                    }
                } else {
                    append(escapeHtml(clampedText))
                }
            }

            return buildString {
                append(header)
                if (body.isNotBlank()) append('\n').append(body)
                if (includeJson) {
                    val json = JSONObject()
                        .put("source", source)
                        .put("package", packageName)
                        .put("app", app)
                        .put("channel", channel)
                        .put("sender", sender)
                        .put("title", title)
                        .put("text", clampedText)
                        .put("subText", subText)
                        .put("postedAt", postedAt)
                        .toString(2)
                    append("\n\n<pre>").append(escapeHtml(json)).append("</pre>")
                }
            }
        }
    }
}
