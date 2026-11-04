package com.nikhilbhatt.hukum

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

data class WaMessage(val sender: String, val text: String, val time: Long)

/** One WhatsApp chat as seen through its notifications. */
class WaChat(val title: String) {
    val messages = mutableListOf<WaMessage>()
    /** WhatsApp's "Reply" action, which lets us answer without opening the app. */
    var reply: Notification.Action? = null
    var announcedUpTo = 0L

    fun incoming(n: Int) = messages.filter { it.sender != WaChat.ME }.takeLast(n)
    fun unread() = messages.filter { it.sender != ME && it.time > announcedUpTo }

    /** Chat history for the reply-drafting prompt. */
    fun transcript(n: Int) = messages.takeLast(n).joinToString("\n") { "${it.sender}: ${it.text}" }

    companion object { const val ME = "You" }
}

/**
 * In-memory store of recent WhatsApp messages, fed by [NotifService]. Only messages that arrive
 * while Hukum's notification access is on are known. Nothing is written to disk.
 */
object NotificationStore {
    private val chats = LinkedHashMap<String, WaChat>()
    private val titleCount = Regex("\\s*\\(\\d+\\s+(?:new\\s+)?messages?\\)\\s*$", RegexOption.IGNORE_CASE)

    fun packages(): Set<String> =
        if (BuildConfig.DEBUG) setOf("com.whatsapp", "com.whatsapp.w4b", "com.android.shell") // shell = adb test notifications
        else setOf("com.whatsapp", "com.whatsapp.w4b")

    @Synchronized
    fun ingest(n: Notification, postTime: Long) {
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val extras = n.extras
        val rawTitle = style?.conversationTitle?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return
        val title = rawTitle.replace(titleCount, "").trim()
        if (title.isEmpty() || title.equals("WhatsApp", ignoreCase = true)) return

        val msgs = style?.messages?.mapNotNull { m ->
            val text = m.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            WaMessage(m.person?.name?.toString() ?: WaChat.ME, text, m.timestamp)
        } ?: run {
            if (n.category != Notification.CATEGORY_MESSAGE && n.category != null) return
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
            if (Regex("^\\d+ new messages?$").matches(text)) return
            listOf(WaMessage(title, text, postTime))
        }

        val chat = chats.getOrPut(title.lowercase()) { WaChat(title) }
        for (m in msgs) {
            if (chat.messages.none { it.text == m.text && it.sender == m.sender && it.time == m.time }) chat.messages += m
        }
        chat.messages.sortBy { it.time }
        while (chat.messages.size > 30) chat.messages.removeAt(0)
        replyAction(n)?.let { chat.reply = it }
        // Most recently active chat goes last.
        chats.remove(title.lowercase()); chats[title.lowercase()] = chat
    }

    private fun replyAction(n: Notification): Notification.Action? {
        fun ok(a: Notification.Action) = a.remoteInputs?.isNotEmpty() == true
        return n.actions?.firstOrNull(::ok) ?: Notification.WearableExtender(n).actions.firstOrNull(::ok)
    }

    @Synchronized
    fun find(name: String): WaChat? = chats.values
        .map { it to Fuzzy.score(name, it.title) }
        .filter { it.second >= 0.62 }
        .maxByOrNull { it.second }?.first

    /** Chats with messages not yet read out, oldest first. */
    @Synchronized
    fun withUnread(): List<WaChat> = chats.values.filter { it.unread().isNotEmpty() }

    @Synchronized
    fun markAnnounced(chat: WaChat) {
        chat.announcedUpTo = chat.messages.maxOfOrNull { it.time } ?: chat.announcedUpTo
    }

    /** Replies through the notification's Reply action. False if there's no usable action. */
    fun sendReply(ctx: Context, chat: WaChat, text: String): Boolean {
        val action = chat.reply ?: return false
        val intent = Intent()
        val results = Bundle()
        action.remoteInputs.forEach { results.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
        return try {
            action.actionIntent.send(ctx, 0, intent)
            synchronized(this) {
                chat.messages += WaMessage(WaChat.ME, text, System.currentTimeMillis())
                markAnnounced(chat)
            }
            true
        } catch (e: PendingIntent.CanceledException) {
            chat.reply = null
            false
        }
    }
}

class NotifService : NotificationListenerService() {
    override fun onListenerConnected() {
        runCatching { activeNotifications }.getOrNull()?.forEach(::ingest)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = ingest(sbn)

    private fun ingest(sbn: StatusBarNotification) {
        if (sbn.packageName in NotificationStore.packages()) NotificationStore.ingest(sbn.notification, sbn.postTime)
    }

    companion object {
        fun isEnabled(ctx: Context) = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)
    }
}
