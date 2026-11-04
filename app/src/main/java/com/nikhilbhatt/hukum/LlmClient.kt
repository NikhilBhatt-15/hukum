package com.nikhilbhatt.hukum

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal client for Gemini and OpenAI-compatible chat APIs (Groq, OpenRouter, Cerebras…). */
object LlmClient {

    suspend fun complete(prefs: Prefs, system: String, user: String): String = withContext(Dispatchers.IO) {
        val p = prefs.provider
        val key = prefs.apiKey(p)
        if (key.isBlank()) throw IOException("No API key set for ${p.label}. Add one in the Hukum app.")
        val model = prefs.model(p)
        val base = prefs.baseUrl(p)
        if (base.isBlank()) throw IOException("Set a base URL for the custom provider.")
        if (p == Provider.GEMINI) gemini(base, key, model, system, user)
        else openAi(p, base, key, model, system, user)
    }

    private fun gemini(base: String, key: String, model: String, system: String, user: String): String {
        val gen = JSONObject()
            .put("temperature", 0)
            .put("responseMimeType", "application/json")
        // 2.5 Flash thinks by default; turning it off roughly halves latency for this simple task.
        if ("2.5-flash" in model) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(
                JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))
            ))
            .put("generationConfig", gen)
        val res = post("$base/models/$model:generateContent", mapOf("x-goog-api-key" to key), body)
        val parts = res.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IOException("Gemini returned no answer (maybe blocked).")
        return (0 until parts.length())
            .mapNotNull { parts.optJSONObject(it) }
            .filterNot { it.optBoolean("thought") }
            .joinToString("") { it.optString("text") }
    }

    private fun openAi(p: Provider, base: String, key: String, model: String, system: String, user: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        // gpt-oss is a reasoning model; low effort is plenty for command parsing and much faster.
        if ("gpt-oss" in model) when (p) {
            Provider.GROQ, Provider.CEREBRAS -> body.put("reasoning_effort", "low")
            Provider.OPENROUTER -> body.put("reasoning", JSONObject().put("effort", "low"))
            else -> Unit
        }
        val headers = buildMap {
            put("Authorization", "Bearer $key")
            if (p == Provider.OPENROUTER) put("X-Title", "Hukum")
        }
        val res = post("$base/chat/completions", headers, body)
        return res.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("${p.label} returned an empty answer.")
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException(friendlyError(code, text))
            return JSONObject(text)
        } catch (e: java.net.UnknownHostException) {
            throw IOException("No internet connection.")
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("The AI took too long to answer.")
        } finally {
            c.disconnect()
        }
    }

    private fun friendlyError(code: Int, body: String): String {
        val msg = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message") ?: o.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }?.take(160)
        return when (code) {
            400 -> "Request rejected: ${msg ?: "bad request"}"
            401, 403 -> "API key was rejected. Check it in the Hukum app."
            404 -> "Model not found. Check the model name. ${msg ?: ""}".trim()
            429 -> "Free-tier rate limit hit. Wait a minute and try again."
            in 500..599 -> "The AI service is having trouble (HTTP $code)."
            else -> "HTTP $code: ${msg ?: "error"}"
        }
    }
}
