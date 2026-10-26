package hr.sting.notifytelegram

/** Escapes text for Telegram's HTML parse mode. */
fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
