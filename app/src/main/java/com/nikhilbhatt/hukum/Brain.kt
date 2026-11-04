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

/** Command pipeline: text → plan (on-device or LLM) → execute → speak the result. */
object Brain {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log
    private var speaker: Speaker? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun handle(ctx: Context, text: String) {
        val app = ctx.applicationContext
        if (_busy.value || text.isBlank()) return
        scope.launch {
            _busy.value = true
            val prefs = Prefs(app)
            try {
                add("You", text)
                val plan = plan(prefs, text)
                add("Plan", "${plan.describe()}  ·  ${plan.source}")
                val result = Executor.run(app, prefs, plan.actions)
                val reply = result.error ?: result.summary.ifBlank { plan.reply.orEmpty() }
                add(if (result.error != null) "Error" else "Done", reply.ifBlank { "OK" })
                respond(app, prefs, reply)
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                add("Error", msg)
                respond(app, prefs, msg)
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun plan(prefs: Prefs, text: String): Plan {
        if (prefs.preferLocal) LocalParser.parseSimple(text)?.let { return it }
        return try {
            Planner.parse(LlmClient.complete(prefs, Planner.SYSTEM, text))
        } catch (e: Exception) {
            LocalParser.parseFallback(text)?.copy(source = "offline fallback (${e.message})") ?: throw e
        }
    }

    private fun respond(ctx: Context, prefs: Prefs, text: String) {
        if (text.isBlank()) return
        Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
        if (prefs.speakReplies) (speaker ?: Speaker(ctx).also { speaker = it }).say(text)
    }

    fun add(kind: String, text: String) {
        _log.value = (listOf(LogEntry(fmt.format(Date()), kind, text)) + _log.value).take(40)
    }
}

data class ExecResult(val summary: String, val error: String? = null)

object Executor {
    private const val NEED_SERVICE = "Turn on Hukum in Accessibility settings first."

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
                Action.Back -> svc?.global(AccessibilityService.GLOBAL_ACTION_BACK) ?: return fail(done, NEED_SERVICE)
                Action.Home -> svc?.global(AccessibilityService.GLOBAL_ACTION_HOME) ?: return fail(done, NEED_SERVICE)
                Action.Recents -> svc?.global(AccessibilityService.GLOBAL_ACTION_RECENTS) ?: return fail(done, NEED_SERVICE)
                is Action.Wait -> delay((a.seconds * 1000).toLong())
            }
            if (a is Action.Back || a is Action.Home || a is Action.Recents) delay(if (more) 600 else 0)
        }
        // Only speak back the steps worth confirming; "back"/"home" alone need no announcement.
        return ExecResult(done.joinToString(". "))
    }

    private fun fail(done: List<String>, msg: String) =
        ExecResult(done.joinToString(". "), if (done.isEmpty()) msg else done.joinToString(". ") + ". But: " + msg)

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
