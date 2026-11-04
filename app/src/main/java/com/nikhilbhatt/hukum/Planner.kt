package com.nikhilbhatt.hukum

import org.json.JSONArray
import org.json.JSONObject

sealed interface Action {
    data class OpenApp(val app: String) : Action
    /** [app] null means "whatever app is in front". */
    data class CloseApp(val app: String?) : Action
    /** [direction] is where the user wants to move: "down" shows content further down the page. */
    data class Scroll(val direction: String, val times: Int) : Action
    data class WhatsApp(val contact: String, val message: String) : Action
    data object Back : Action
    data object Home : Action
    data object Recents : Action
    data class Wait(val seconds: Double) : Action
}

fun Action.describe(): String = when (this) {
    is Action.OpenApp -> "open $app"
    is Action.CloseApp -> "close ${app ?: "current app"}"
    is Action.Scroll -> "scroll $direction" + if (times > 1) " x$times" else ""
    is Action.WhatsApp -> "WhatsApp $contact: \"$message\""
    Action.Back -> "back"
    Action.Home -> "home"
    Action.Recents -> "recents"
    is Action.Wait -> "wait ${seconds}s"
}

data class Plan(val actions: List<Action>, val reply: String?, val source: String) {
    fun describe(): String = if (actions.isEmpty()) "(nothing to do)" else actions.joinToString(", ") { it.describe() }
}

/** Turns the LLM's JSON reply into a [Plan]. */
object Planner {
    val SYSTEM = """
You are Hukum, a voice assistant that controls an Android phone.
Convert the user's spoken command into a JSON plan. Reply with ONLY a JSON object, no prose, no code fences:
{"actions":[ ... ],"reply":"very short spoken confirmation"}

Available actions:
{"type":"open_app","app":"<app name, e.g. YouTube>"}
{"type":"close_app","app":"<app name, or empty string for the app currently open>"}
{"type":"scroll","direction":"down|up|left|right","times":1}
{"type":"whatsapp","contact":"<contact name or phone number>","message":"<the message to send>"}
{"type":"back"}
{"type":"home"}
{"type":"recents"}
{"type":"wait","seconds":1}

Rules:
- "scroll down" means the user wants to see content further down. "scroll right" means next item/page.
- For whatsapp, put only the message body in "message" (no "tell him", no contact name unless it belongs in the text). Fix obvious speech-to-text mistakes, keep the user's language (English, Hindi or Hinglish).
- Use several actions in order for multi-step commands, e.g. open an app, then scroll.
- If nothing above can do it, return {"actions":[],"reply":"<one short sentence saying you can't do that yet>"}.
""".trim()

    fun parse(raw: String): Plan {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "AI reply wasn't JSON: ${raw.take(120)}" }
        val o = JSONObject(raw.substring(start, end + 1))
        val arr = o.optJSONArray("actions") ?: JSONArray()
        val actions = (0 until arr.length()).mapNotNull { toAction(arr.optJSONObject(it)) }
        return Plan(actions, str(o, "reply").ifBlank { null }, "AI")
    }

    private fun str(j: JSONObject, key: String): String =
        if (!j.has(key) || j.isNull(key)) "" else j.optString(key).trim()

    private fun toAction(j: JSONObject?): Action? {
        j ?: return null
        return when (str(j, "type").lowercase()) {
            "open_app" -> str(j, "app").ifBlank { null }?.let { Action.OpenApp(it) }
            "close_app" -> Action.CloseApp(
                str(j, "app").takeIf { it.isNotBlank() && it.lowercase() !in setOf("current", "this", "it", "null") }
            )
            "scroll" -> Action.Scroll(normDirection(str(j, "direction")), j.optInt("times", 1).coerceIn(1, 10))
            "whatsapp" -> str(j, "contact").ifBlank { null }?.let { Action.WhatsApp(it, str(j, "message")) }
            "back" -> Action.Back
            "home" -> Action.Home
            "recents" -> Action.Recents
            "wait" -> Action.Wait(j.optDouble("seconds", 1.0).coerceIn(0.0, 10.0))
            else -> null
        }
    }

    fun normDirection(d: String): String = when (d.lowercase().trim()) {
        "up", "upward", "upwards", "top", "upar" -> "up"
        "left", "previous", "back" -> "left"
        "right", "next", "forward" -> "right"
        else -> "down"
    }
}

/**
 * On-device parser for simple commands. Used as the fast path (no LLM round trip) and as a
 * fallback when the LLM can't be reached.
 */
object LocalParser {
    private val fillers = Regex("^(?:(?:hey|ok|okay)\\s+hukum\\s*|please\\s+|can you\\s+|could you\\s+|hukum\\s+)+")
    private val splitter = Regex("\\s+(?:and then|and|then|phir|aur)\\s+")

    private val openRx = Regex("^(?:open|launch|start|run|khol(?:o|do)?)\\s+(?:the\\s+|up\\s+)?(.+?)(?:\\s+app)?$")
    private val openHiRx = Regex("^(.+?)\\s+(?:kholo|khol do|open karo|open kar do|chalao)$")
    private val closeRx = Regex("^(?:close|exit|quit|kill)(?:\\s+(?:the\\s+)?(.+?)(?:\\s+app)?)?$")
    private val closeHiRx = Regex("^(.+?)\\s+(?:band karo|band kar do|band)$")
    private val scrollRx = Regex("^(?:scroll|swipe)(?:\\s+(up|down|left|right))?(?:\\s+(\\d+|once|twice|thrice|two|three|four|five)(?:\\s+times?)?)?$")
    private val backRx = Regex("^(?:go\\s+)?back$|^peeche(?: jao)?$")
    private val homeRx = Regex("^(?:go\\s+)?(?:to\\s+)?(?:the\\s+)?home(?:\\s+screen)?$")
    private val recentsRx = Regex("^(?:show\\s+)?(?:the\\s+)?recents?(?:\\s+apps)?$")

    private fun clean(s: String) = s.lowercase().replace(Regex("[.,!?]"), " ")
        .replace(Regex("\\s+"), " ").trim().replace(fillers, "").trim()

    /** Only open/close/scroll/back/home/recents, possibly chained with "and"/"then". */
    fun parseSimple(raw: String): Plan? {
        val text = clean(raw)
        if (text.isEmpty() || "whatsapp" in text && !openRx.matches(text)) return null
        val actions = text.split(splitter).map { one(it.trim()) ?: return null }
        return Plan(actions, null, "on-device")
    }

    /** [parseSimple] plus a few WhatsApp phrasings, for when the LLM is unavailable. */
    fun parseFallback(raw: String): Plan? = parseSimple(raw) ?: parseWhatsApp(raw)

    private fun one(p: String): Action? {
        openRx.matchEntire(p)?.let { return Action.OpenApp(it.groupValues[1]) }
        openHiRx.matchEntire(p)?.let { return Action.OpenApp(it.groupValues[1]) }
        closeRx.matchEntire(p)?.let {
            val app = it.groupValues[1].trim()
            return Action.CloseApp(app.takeIf { a -> a.isNotEmpty() && a !in setOf("this", "it", "app", "current app", "this app") })
        }
        closeHiRx.matchEntire(p)?.let { return Action.CloseApp(it.groupValues[1].takeIf { a -> a !in setOf("ye", "yeh", "app") }) }
        scrollRx.matchEntire(p)?.let {
            val times = when (val t = it.groupValues[2]) {
                "", "once" -> 1
                "twice", "two" -> 2
                "thrice", "three" -> 3
                "four" -> 4
                "five" -> 5
                else -> t.toIntOrNull() ?: 1
            }
            return Action.Scroll(Planner.normDirection(it.groupValues[1]), times.coerceIn(1, 10))
        }
        if (backRx.matches(p)) return Action.Back
        if (homeRx.matches(p)) return Action.Home
        if (recentsRx.matches(p)) return Action.Recents
        return null
    }

    private val ic = setOf(RegexOption.IGNORE_CASE)
    // (contact, message) ordered patterns
    private val waContactFirst = listOf(
        Regex("^(?:send\\s+)?(?:a\\s+)?(?:whatsapp\\s+)?(?:message|msg|text)\\s+(?:to\\s+)?(.+?)\\s+(?:on|via|in)\\s+whatsapp\\s*(?:saying|that|:)?\\s+(.+)$", ic),
        Regex("^whatsapp\\s+(.+?)\\s*(?:saying|that|message|:)\\s*(.+)$", ic),
    )
    // (message, contact)
    private val waMessageFirst = listOf(
        Regex("^(?:send|tell|say)\\s+(.+?)\\s+to\\s+(.+?)\\s+(?:on|via|in)\\s+whatsapp$", ic),
    )

    private fun parseWhatsApp(raw: String): Plan? {
        val text = raw.trim().trimEnd('.', '!', '?').replace(fillers, "")
        for (rx in waContactFirst) rx.matchEntire(text)?.let {
            return Plan(listOf(Action.WhatsApp(it.groupValues[1].trim(), it.groupValues[2].trim())), null, "on-device")
        }
        for (rx in waMessageFirst) rx.matchEntire(text)?.let {
            return Plan(listOf(Action.WhatsApp(it.groupValues[2].trim(), it.groupValues[1].trim())), null, "on-device")
        }
        return null
    }
}
