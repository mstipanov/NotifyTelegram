package hr.sting.notifytelegram

import android.content.Context
import android.content.SharedPreferences

/**
 * Configuration: the Telegram destination and the set of apps whose
 * notifications are forwarded.
 *
 * The bot token is stored encrypted with [SecretStore]; the legacy plaintext key
 * from 1.0 is migrated and removed on first read.
 */
object Prefs {

    private const val FILE = "notifytelegram"
    private const val KEY_TOKEN = "bot_token" // legacy plaintext, migration only
    private const val KEY_TOKEN_ENCRYPTED = "bot_token_enc"
    private const val KEY_CHAT_ID = "chat_id"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_ENABLED = "forwarding_enabled"
    private const val KEY_INCLUDE_JSON = "include_json"

    /** Used until the user picks apps; Slack and Outlook are the common case. */
    val DEFAULT_PACKAGES: Set<String> = setOf(
        "com.microsoft.office.outlook",
        "com.Slack",
    )

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun botToken(ctx: Context): String {
        val prefs = sp(ctx)
        val encrypted = prefs.getString(KEY_TOKEN_ENCRYPTED, null)
        if (!encrypted.isNullOrBlank()) {
            return runCatching { SecretStore.decrypt(encrypted) }
                .getOrElse {
                    LogBus.warn("Stored bot token could not be decrypted; enter it again", it)
                    ""
                }
                .trim()
        }

        // Migrate a 1.0 plaintext token, then drop the plaintext copy.
        val legacy = prefs.getString(KEY_TOKEN, "").orEmpty().trim()
        if (legacy.isNotEmpty()) {
            setBotToken(ctx, legacy)
        }
        return legacy
    }

    fun setBotToken(ctx: Context, value: String) {
        val token = value.trim()
        val editor = sp(ctx).edit().remove(KEY_TOKEN)
        if (token.isEmpty()) {
            editor.remove(KEY_TOKEN_ENCRYPTED).apply()
            return
        }
        val encrypted = runCatching { SecretStore.encrypt(token) }
            .getOrElse {
                LogBus.warn("Could not encrypt the bot token; it was not saved", it)
                null
            }
        if (encrypted != null) {
            editor.putString(KEY_TOKEN_ENCRYPTED, encrypted).apply()
        } else {
            editor.apply()
        }
    }

    fun chatId(ctx: Context): String = sp(ctx).getString(KEY_CHAT_ID, "").orEmpty().trim()

    fun setChatId(ctx: Context, value: String) =
        sp(ctx).edit().putString(KEY_CHAT_ID, value.trim()).apply()

    /** Master switch. The notification listener stays bound either way. */
    fun forwardingEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_ENABLED, true)

    fun setForwardingEnabled(ctx: Context, value: Boolean) =
        sp(ctx).edit().putBoolean(KEY_ENABLED, value).apply()

    /** Appends the machine-readable JSON block to every Telegram message. */
    fun includeJson(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_INCLUDE_JSON, true)

    fun setIncludeJson(ctx: Context, value: Boolean) =
        sp(ctx).edit().putBoolean(KEY_INCLUDE_JSON, value).apply()

    /**
     * Package names whose notifications are forwarded. An explicitly empty set
     * (the user unchecked everything) is preserved; only an unset preference
     * falls back to [DEFAULT_PACKAGES].
     */
    fun packages(ctx: Context): Set<String> {
        val prefs = sp(ctx)
        if (!prefs.contains(KEY_PACKAGES)) return DEFAULT_PACKAGES
        return prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toSet()
    }

    fun setPackages(ctx: Context, value: Set<String>) =
        sp(ctx).edit().putStringSet(KEY_PACKAGES, value.toSet()).apply()
}
