package com.nikhilbhatt.hukum

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(val time: String, val kind: String, val text: String)

/** A message Hukum drafted and is waiting for the user to approve. */
class PendingSend(val title: String, val text: String, val send: suspend () -> ExecResult)

/** Command pipeline: text → plan (on-device or LLM) → execute → speak the result. */
object Brain {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log
    private val _pending = MutableStateFlow<PendingSend?>(null)
    val pending: StateFlow<PendingSend?> = _pending
    private var speaker: Speaker? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val yesWords = setOf("yes", "yeah", "yep", "yup", "send", "send it", "ok", "okay", "sure", "haan", "han", "ha",
        "haa", "haan bhej do", "bhej do", "bhejo", "theek hai", "thik hai", "do it", "go ahead", "yes send it", "yes please")
    private val noWords = setOf("no", "nope", "cancel", "don't", "dont", "don't send", "stop", "nahi", "na", "nahin",
        "mat bhejo", "rehne do", "leave it", "no thanks")

    fun handle(ctx: Context, text: String) {
        val app = ctx.applicationContext
        if (_busy.value || text.isBlank()) return
        if (_pending.value != null) {
            val t = Fuzzy.norm(text)
            when {
                t in yesWords -> return confirm(app)
                t in noWords -> return cancel(app)
                else -> _pending.value = null // anything else: drop the draft, treat as a new command
            }
        }
        scope.launch {
            _busy.value = true
            val prefs = Prefs(app)
            try {
                add("You", text)
                val plan = plan(prefs, text)
                add("Plan", "${plan.describe()}  ·  ${plan.source}")
                val result = Executor.run(app, prefs, plan.actions)
                val reply = result.error ?: result.summary.ifBlank { plan.reply.orEmpty() }
                add(if (result.error != null) "Error" else if (result.ask) "Ask" else "Done", reply.ifBlank { "OK" })
                respond(app, prefs, reply, listenAfter = result.ask)
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                add("Error", msg)
                respond(app, prefs, msg)
            } finally {
                _busy.value = false
            }
        }
    }

    fun ask(p: PendingSend) {
        _pending.value = p
    }

    fun confirm(ctx: Context) {
        val p = _pending.value ?: return
        _pending.value = null
        val app = ctx.applicationContext
        scope.launch {
            _busy.value = true
            val prefs = Prefs(app)
            try {
                val r = p.send()
                val msg = r.error ?: r.summary
                add(if (r.error != null) "Error" else "Done", msg)
                respond(app, prefs, msg)
            } catch (e: Exception) {
                add("Error", e.message ?: "Couldn't send.")
                respond(app, prefs, e.message ?: "Couldn't send.")
            } finally {
                _busy.value = false
            }
        }
    }

    fun cancel(ctx: Context) {
        if (_pending.value == null) return
        _pending.value = null
        add("Done", "Cancelled")
        respond(ctx.applicationContext, Prefs(ctx), "Okay, not sending it.")
    }

    private suspend fun plan(prefs: Prefs, text: String): Plan {
        if (prefs.preferLocal) LocalParser.parseSimple(text)?.let { return it }
        return try {
            Planner.parse(LlmClient.complete(prefs, LlmRequest(Planner.SYSTEM, text, json = true)))
        } catch (e: Exception) {
            LocalParser.parseFallback(text)?.copy(source = "offline fallback (${e.message})") ?: throw e
        }
    }

    private fun respond(ctx: Context, prefs: Prefs, text: String, listenAfter: Boolean = false) {
        if (text.isBlank()) return
        Toast.makeText(ctx, text.take(300), Toast.LENGTH_LONG).show()
        if (prefs.speakReplies) {
            (speaker ?: Speaker(ctx).also { speaker = it }).say(text) {
                if (listenAfter && _pending.value != null) HukumService.instance?.listen()
            }
        }
    }

    fun add(kind: String, text: String) {
        android.util.Log.i("Hukum", "$kind: $text")
        _log.value = (listOf(LogEntry(fmt.format(Date()), kind, text)) + _log.value).take(40)
    }
}

data class ExecResult(val summary: String, val error: String? = null, val ask: Boolean = false)

object Executor {
    private const val NEED_SERVICE = "Turn on Hukum in Accessibility settings first."
    private val waPackages = setOf("com.whatsapp", "com.whatsapp.w4b")

    suspend fun run(ctx: Context, prefs: Prefs, actions: List<Action>): ExecResult {
        val done = mutableListOf<String>()
        for ((i, a) in actions.withIndex()) {
            val more = i < actions.lastIndex
            val svc = HukumService.instance
            when (a) {
                is Action.OpenApp -> {
                    val entry = withContext(Dispatchers.IO) { AppResolver.find(ctx, a.app) }
                        ?: return fail(done, "I couldn't find an app called ${a.app}.")
                    val launch = ctx.packageManager.getLaunchIntentForPackage(entry.pkg)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?: return fail(done, "${entry.label} can't be opened.")
                    (svc ?: ctx).startActivity(launch)
                    done += "Opened ${entry.label}"
                    if (more) delay(1800)
                }
                is Action.CloseApp -> {
                    svc ?: return fail(done, NEED_SERVICE)
                    val pkg = a.app?.let { name ->
                        withContext(Dispatchers.IO) { AppResolver.find(ctx, name) }?.pkg
                            ?: return fail(done, "I couldn't find an app called $name.")
                    }
                    val closed = svc.closeApp(pkg) ?: return fail(done, "There's no app open to close.")
                    done += "Closed ${AppResolver.labelFor(ctx, closed)}"
                    if (more) delay(500)
                }
                is Action.Scroll -> {
                    svc ?: return fail(done, NEED_SERVICE)
                    repeat(a.times) {
                        if (!svc.scroll(a.direction)) return fail(done, "Scrolling was blocked on this screen.")
                        delay(450)
                    }
                    done += "Scrolled ${a.direction}"
                }
                is Action.WhatsApp -> {
                    val r = whatsApp(ctx, prefs, svc, a)
                    if (r.error != null) return fail(done, r.error)
                    done += r.summary
                }
                is Action.ReadMessages -> {
                    if (!NotifService.isEnabled(ctx)) return fail(done, "Turn on Notification access for Hukum so I can read messages.")
                    done += readMessages(a.contact) ?: return fail(done,
                        a.contact?.let { "I haven't seen any messages from $it yet." } ?: "No new messages.")
                }
                is Action.Reply -> {
                    if (a.message.isNotBlank()) {
                        val r = deliver(ctx, prefs, svc, a.contact, NotificationStore.find(a.contact), a.message)
                        if (r.error != null) return fail(done, r.error)
                        done += r.summary
                    } else {
                        // Drafting needs approval, so it always ends the plan.
                        val r = draftReply(ctx, prefs, svc, a)
                        return if (r.error != null) fail(done, r.error) else ExecResult((done + r.summary).joinToString(". "), ask = true)
                    }
                }
                is Action.ReadScreen -> {
                    svc ?: return fail(done, NEED_SERVICE)
                    val text = svc.screenText()
                    if (text.isBlank()) return fail(done, "I can't read any text on this screen.")
                    val answer = LlmClient.complete(prefs, LlmRequest(Planner.SCREEN, "Question: ${a.question}\n<screen>\n$text\n</screen>"))
                    done += answer.trim()
                }
                is Action.LookScreen -> {
                    svc ?: return fail(done, NEED_SERVICE)
                    val vp = LlmClient.visionProvider(prefs)
                        ?: return fail(done, "Looking at the screen needs Gemini or Claude. Add one of those keys in Hukum.")
                    val jpeg = svc.screenshotJpeg()
                    val answer = LlmClient.complete(prefs, LlmRequest(Planner.VISION, "Question: ${a.question}", image = jpeg), vp)
                    done += answer.trim()
                }
                Action.Back -> svc?.global(AccessibilityService.GLOBAL_ACTION_BACK) ?: return fail(done, NEED_SERVICE)
                Action.Home -> svc?.global(AccessibilityService.GLOBAL_ACTION_HOME) ?: return fail(done, NEED_SERVICE)
                Action.Recents -> svc?.global(AccessibilityService.GLOBAL_ACTION_RECENTS) ?: return fail(done, NEED_SERVICE)
                is Action.Wait -> delay((a.seconds * 1000).toLong())
            }
            if (a is Action.Back || a is Action.Home || a is Action.Recents) delay(if (more) 600 else 0)
        }
        return ExecResult(done.joinToString(". "))
    }

    private fun fail(done: List<String>, msg: String) =
        ExecResult(done.joinToString(". "), if (done.isEmpty()) msg else done.joinToString(". ") + ". But: " + msg)

    /** Spoken summary of messages, or null if there's nothing to read. */
    private fun readMessages(contact: String?): String? {
        fun sentence(t: String) = t.trim().let { if (it.lastOrNull() in listOf('.', '!', '?')) it else "$it." }
        // "Rahul says: Are you coming? Bring the speaker." Consecutive messages from one sender are grouped.
        fun line(chat: WaChat, msgs: List<WaMessage>): String {
            val groups = mutableListOf<Pair<String, MutableList<String>>>()
            for (m in msgs) {
                if (groups.lastOrNull()?.first == m.sender) groups.last().second += sentence(m.text)
                else groups += m.sender to mutableListOf(sentence(m.text))
            }
            return groups.joinToString(" ") { (sender, texts) ->
                val who = if (sender == chat.title) "$sender says" else "$sender in ${chat.title} says"
                "$who: ${texts.joinToString(" ")}"
            }
        }
        if (contact != null) {
            val chat = NotificationStore.find(contact) ?: return null
            val msgs = chat.incoming(5).ifEmpty { return null }
            NotificationStore.markAnnounced(chat)
            return line(chat, msgs)
        }
        val chats = NotificationStore.withUnread().ifEmpty { return null }
        return chats.joinToString(" ") { chat ->
            line(chat, chat.unread().takeLast(5)).also { NotificationStore.markAnnounced(chat) }
        }
    }

    private suspend fun draftReply(ctx: Context, prefs: Prefs, svc: HukumService?, a: Action.Reply): ExecResult {
        val chat = NotificationStore.find(a.contact)
        // Prefer the notification history; fall back to the open WhatsApp chat on screen.
        val history = chat?.transcript(12)?.takeIf { chat.incoming(1).isNotEmpty() }
            ?: svc?.takeIf { it.frontPkg() in waPackages }?.screenText(4000)?.takeIf { it.isNotBlank() }
            ?: return ExecResult("", if (!NotifService.isEnabled(ctx))
                "Turn on Notification access for Hukum, or open the chat, so I can see what ${a.contact} said."
            else "I don't see any messages from ${a.contact} to reply to.")
        val name = chat?.title ?: a.contact
        val latest = chat?.incoming(1)?.firstOrNull()?.text
        val draft = LlmClient.complete(prefs, LlmRequest(
            Planner.DRAFT,
            "Reply to: $name\nUser's hint: ${a.hint.ifBlank { "none" }}\n<chat>\n$history\n</chat>" +
                (latest?.let { "\nTheir latest message (reply in exactly this language and script): <latest>$it</latest>" } ?: ""),
        )).trim().trim('"', '“', '”').trim()
        if (draft.isBlank()) return ExecResult("", "I couldn't come up with a reply.")
        Brain.ask(PendingSend("Reply to $name", draft) { deliver(ctx, prefs, HukumService.instance, a.contact, chat, draft) })
        val last = chat?.incoming(1)?.firstOrNull()?.text
        fun end(t: String) = if (t.trimEnd().lastOrNull() in listOf('.', '!', '?')) t.trimEnd() else "${t.trimEnd()}."
        return ExecResult((if (last != null) "$name said: ${end(last)} " else "") + "I'll reply: ${end(draft)} Send it?")
    }

    /** Sends [text] to a chat: through the notification's Reply action if we have one, else via WhatsApp. */
    private suspend fun deliver(ctx: Context, prefs: Prefs, svc: HukumService?, contact: String, chat: WaChat?, text: String): ExecResult {
        if (chat != null && NotificationStore.sendReply(ctx, chat, text)) return ExecResult("Replied to ${chat.title}")
        return whatsApp(ctx, prefs, svc, Action.WhatsApp(chat?.title ?: contact, text))
    }

    private suspend fun whatsApp(ctx: Context, prefs: Prefs, svc: HukumService?, a: Action.WhatsApp): ExecResult {
        val pm = ctx.packageManager
        val waPkg = listOf("com.whatsapp", "com.whatsapp.w4b")
            .firstOrNull { pm.getLaunchIntentForPackage(it) != null }
            ?: return ExecResult("", "WhatsApp isn't installed.")
        val hit = withContext(Dispatchers.IO) { ContactResolver.find(ctx, a.contact, prefs.countryCode) }
            ?: return ExecResult("",
                if (!ContactResolver.hasPermission(ctx)) "Allow Contacts permission in Hukum so I can find ${a.contact}."
                else "I couldn't find ${a.contact} in your contacts.")

        val uri = Uri.parse("https://api.whatsapp.com/send?phone=${hit.number}&text=${Uri.encode(a.message)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(waPkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (svc ?: ctx).startActivity(intent)

        if (a.message.isBlank()) return ExecResult("Opened chat with ${hit.name}")
        if (!prefs.autoSend) return ExecResult("Opened chat with ${hit.name}. Tap Send when ready")
        svc ?: return ExecResult("", "Opened the chat, but turn on Hukum in Accessibility settings so I can tap Send.")
        return if (svc.tapWhatsAppSend(waPkg)) ExecResult("Sent to ${hit.name}")
        else ExecResult("", "Opened the chat with ${hit.name} but couldn't find the Send button.")
    }
}
