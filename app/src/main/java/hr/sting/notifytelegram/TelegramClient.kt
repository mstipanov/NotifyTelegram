package hr.sting.notifytelegram

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal Telegram Bot API client built on [HttpURLConnection] + [JSONObject].
 * Only what a notification forwarder needs: send a message, verify the bot and
 * capture the destination chat id once.
 */
class TelegramClient(private val token: String) {

    data class ChatRef(val id: String, val title: String)

    init {
        require(token.isNotBlank()) { "Bot token is empty" }
    }

    private fun call(
        method: String,
        params: Map<String, String> = emptyMap(),
        readTimeoutMs: Int = 20_000,
    ): JSONObject {
        val conn = (URL("https://api.telegram.org/bot$token/$method").openConnection()) as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")

            val body = params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IOException("Telegram returned non-JSON response (HTTP $code)")
            }

            if (!json.optBoolean("ok", false)) {
                val desc = json.optString("description").ifBlank { "HTTP $code" }
                throw IOException(desc)
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Returns the bot's @username. */
    fun getMe(): String {
        val result = call("getMe").optJSONObject("result")
        return result?.optString("username").orEmpty()
    }

    fun sendMessage(chatId: String, text: String) {
        val params = mapOf(
            "chat_id" to chatId,
            "text" to text,
            "parse_mode" to "HTML",
            "disable_web_page_preview" to "true",
        )
        call("sendMessage", params)
    }

    fun getUpdates(timeoutSeconds: Int = 0): List<JSONObject> {
        val params = mapOf(
            "offset" to "0",
            "timeout" to timeoutSeconds.toString(),
        )
        val json = call("getUpdates", params, readTimeoutMs = (timeoutSeconds + 10) * 1000)
        val array = json.optJSONArray("result") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    /**
     * Reads the chat id from the newest message the bot has received. Used to
     * fill the chat id field after the user sends the bot `/start`.
     */
    fun latestChat(): ChatRef? {
        for (update in getUpdates().asReversed()) {
            val message = update.optJSONObject("message")
                ?: update.optJSONObject("channel_post")
                ?: update.optJSONObject("edited_message")
                ?: continue
            val chat = message.optJSONObject("chat")
                ?: update.optJSONObject("my_chat_member")?.optJSONObject("chat")
                ?: continue
            val id = chat.optLong("id", Long.MIN_VALUE)
            if (id == Long.MIN_VALUE) continue
            val title = chat.optString("title").ifBlank {
                chat.optString("username").ifBlank {
                    listOf(
                        chat.optString("first_name"),
                        chat.optString("last_name"),
                    ).filter { it.isNotBlank() }.joinToString(" ")
                }
            }.ifBlank { id.toString() }
            return ChatRef(id.toString(), title)
        }
        return null
    }
}
