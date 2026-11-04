package com.nikhilbhatt.hukum

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import kotlin.math.max
import kotlin.math.min

object Fuzzy {
    fun norm(s: String) = s.lowercase()
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** 0..1 similarity between what the user said and a candidate name. */
    fun score(query: String, target: String): Double {
        val a = norm(query)
        val b = norm(target)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val an = a.replace(" ", "")
        val bn = b.replace(" ", "")
        if (an == bn) return 0.98
        val ratio = min(an.length, bn.length).toDouble() / max(an.length, bn.length)
        if (bn.startsWith(an) || an.startsWith(bn)) return 0.85 + 0.1 * ratio
        if (bn.contains(an) || an.contains(bn)) return 0.75 + 0.1 * ratio
        val at = a.split(" ").toSet()
        val bt = b.split(" ").toSet()
        val common = at.intersect(bt).size
        val tok = if (common > 0) 0.6 + 0.3 * common / max(at.size, bt.size) else 0.0
        val lev = 1.0 - levenshtein(an, bn).toDouble() / max(an.length, bn.length)
        return max(tok, lev * 0.9)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }
}

data class AppEntry(val label: String, val pkg: String)

object AppResolver {
    private var cache: List<AppEntry> = emptyList()
    private var cachedAt = 0L

    // Common spoken names that don't fuzzy-match their launcher label.
    private val aliases = mapOf(
        "insta" to "instagram", "ig" to "instagram", "yt" to "youtube", "fb" to "facebook",
        "gpay" to "google pay", "browser" to "chrome", "dialer" to "phone", "phone app" to "phone",
        "sms" to "messages", "text messages" to "messages", "mail" to "gmail", "email" to "gmail",
        "store" to "play store", "google play" to "play store", "map" to "maps", "google maps" to "maps",
        "x" to "twitter", "camera app" to "camera", "setting" to "settings",
    )

    fun list(ctx: Context): List<AppEntry> {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - cachedAt < 60_000) return cache
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        cache = pm.queryIntentActivities(intent, 0)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
        cachedAt = now
        return cache
    }

    fun find(ctx: Context, spoken: String): AppEntry? {
        val q = Fuzzy.norm(spoken).let { aliases[it] ?: it }
        if (q.isEmpty()) return null
        return list(ctx)
            .map { e -> e to max(Fuzzy.score(q, e.label), 0.9 * Fuzzy.score(q, e.pkg.substringAfterLast('.'))) }
            .filter { it.second >= 0.62 }
            .maxByOrNull { it.second }?.first
    }

    fun labelFor(ctx: Context, pkg: String): String =
        list(ctx).firstOrNull { it.pkg == pkg }?.label ?: pkg.substringAfterLast('.')
}

data class ContactHit(val name: String, val number: String)

object ContactResolver {
    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun find(ctx: Context, spoken: String, countryCode: String): ContactHit? {
        // The user said a number rather than a name.
        val digits = spoken.filter(Char::isDigit)
        if (digits.length >= 7 && spoken.none(Char::isLetter)) return ContactHit(spoken, normalize(spoken, countryCode))
        if (!hasPermission(ctx)) return null

        var best: ContactHit? = null
        var bestScore = 0.0
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                val s = Fuzzy.score(spoken, name)
                if (s > bestScore) {
                    bestScore = s
                    best = ContactHit(name, number)
                }
            }
        }
        return best?.takeIf { bestScore >= 0.62 }?.let { it.copy(number = normalize(it.number, countryCode)) }
    }

    /** WhatsApp wants the full international number with no "+" or spaces. */
    fun normalize(raw: String, countryCode: String): String {
        val hasPlus = raw.trim().startsWith("+")
        var d = raw.filter(Char::isDigit)
        if (hasPlus) return d
        if (d.startsWith("00")) return d.drop(2)
        d = d.trimStart('0')
        if (d.length <= 10) d = countryCode + d
        return d
    }
}
