package com.nikhilbhatt.hukum

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One prompt to an LLM: a system prompt, the user text, and optionally a JPEG screenshot. */
class LlmRequest(
    val system: String,
    val user: String,
    val image: ByteArray? = null,
    /** Ask for a JSON object (planner calls). Plain text otherwise. */
    val json: Boolean = false,
)

/** Minimal client for Gemini, Claude and OpenAI-compatible chat APIs (Groq, OpenRouter, Cerebras…). */
object LlmClient {

    // Claude models that accept the server-side refusal fallback ("default" routing).
    private val claudeFallbackModels = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")

    suspend fun complete(prefs: Prefs, req: LlmRequest, provider: Provider = prefs.provider): String =
        withContext(Dispatchers.IO) {
            val key = prefs.apiKey(provider)
            if (key.isBlank()) throw IOException("No API key set for ${provider.label}. Add one in the Hukum app.")
            val model = prefs.model(provider)
            val base = prefs.baseUrl(provider)
            if (base.isBlank()) throw IOException("Set a base URL for the custom provider.")
            if (req.image != null && !supportsVision(prefs, provider)) {
                throw IOException("$model can't see images. Add a Gemini or Claude key for screenshots.")
            }
            when (provider) {
                Provider.GEMINI -> gemini(base, key, model, req)
                Provider.CLAUDE -> claude(base, key, model, req)
                else -> openAi(provider, base, key, model, req)
            }
        }

    /** gpt-oss is text-only; Gemini and Claude see images. Custom/OpenRouter depend on the chosen model. */
    fun supportsVision(prefs: Prefs, p: Provider): Boolean = "gpt-oss" !in prefs.model(p)

    /** Provider to use for screenshots: the main one if it can see, else Gemini, else Claude. */
    fun visionProvider(prefs: Prefs): Provider? =
        listOf(prefs.provider, Provider.GEMINI, Provider.CLAUDE).firstOrNull {
            prefs.apiKey(it).isNotBlank() && prefs.baseUrl(it).isNotBlank() && supportsVision(prefs, it)
        }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun gemini(base: String, key: String, model: String, req: LlmRequest): String {
        val gen = JSONObject().put("temperature", 0)
        if (req.json) gen.put("responseMimeType", "application/json")
        // 2.5 Flash thinks by default; turning it off roughly halves latency for these short tasks.
        when {
            "2.5-flash" in model -> gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            model.startsWith("gemini-3") -> gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        }
        val parts = JSONArray()
        req.image?.let {
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64(it))))
        }
        parts.put(JSONObject().put("text", req.user))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", req.system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", gen)
        val res = post("$base/models/$model:generateContent", mapOf("x-goog-api-key" to key), body)
        val out = res.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IOException("Gemini returned no answer (maybe blocked).")
        return (0 until out.length())
            .mapNotNull { out.optJSONObject(it) }
            .filterNot { it.optBoolean("thought") }
            .joinToString("") { it.optString("text") }
    }

    private fun claude(base: String, key: String, model: String, req: LlmRequest): String {
        val content = JSONArray()
        req.image?.let {
            content.put(JSONObject().put("type", "image").put("source",
                JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64(it))))
        }
        content.put(JSONObject().put("type", "text").put("text", req.user))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 16000)
            .put("system", if (req.json) req.system + "\nReply with the JSON object only." else req.system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        // Short commands don't need deep reasoning; low effort keeps voice replies fast and cheap.
        if (!model.contains("4-5")) body.put("output_config", JSONObject().put("effort", "low"))
        val headers = mutableMapOf("x-api-key" to key, "anthropic-version" to "2026-10-21")
        if (model in claudeFallbackModels) {
            body.put("fallbacks", "default")
            headers["anthropic-beta"] = "server-side-fallback-2026-07-01"
        }
        val res = post("$base/messages", headers, body)
        if (res.optString("stop_reason") == "refusal") throw IOException("Claude declined this request.")
        val blocks = res.optJSONArray("content") ?: throw IOException("Claude returned an empty answer.")
        return (0 until blocks.length())
            .mapNotNull { blocks.optJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .joinToString("") { it.optString("text") }
            .ifBlank { throw IOException("Claude returned an empty answer.") }
    }

    private fun openAi(p: Provider, base: String, key: String, model: String, req: LlmRequest): String {
        val userContent: Any = if (req.image == null) req.user else JSONArray()
            .put(JSONObject().put("type", "text").put("text", req.user))
            .put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${b64(req.image)}")))
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", req.system))
                .put(JSONObject().put("role", "user").put("content", userContent)))
        // gpt-oss is a reasoning model; low effort is plenty here and much faster.
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

    private class ServerBusy(msg: String) : IOException(msg)

    /** POST with one retry when the service is briefly overloaded (free tiers return 503 often). */
    private fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject =
        try {
            postOnce(url, headers, body)
        } catch (e: ServerBusy) {
            Thread.sleep(1500)
            postOnce(url, headers, body)
        }

    private fun postOnce(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 60_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in setOf(500, 502, 503, 529)) throw ServerBusy(friendlyError(code, text))
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
            429 -> "Rate limit hit. Wait a minute and try again."
            529 -> "The AI service is overloaded. Try again shortly."
            in 500..599 -> "The AI service is having trouble (HTTP $code)."
            else -> "HTTP $code: ${msg ?: "error"}"
        }
    }
}
