package com.nikhilbhatt.hukum

import android.content.Context

/** LLM backends. Everything except Gemini speaks the OpenAI chat-completions format. */
enum class Provider(
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val keyHint: String,
) {
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-flash-lite-latest", "Free key: aistudio.google.com/apikey"),
    GROQ("Groq", "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", "Free key: console.groq.com/keys"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-oss-120b:free", "Free key: openrouter.ai/keys"),
    CEREBRAS("Cerebras", "https://api.cerebras.ai/v1", "gpt-oss-120b", "Free key: cloud.cerebras.ai"),
    CLAUDE("Claude", "https://api.anthropic.com/v1", "claude-opus-5-5", "Paid key: platform.claude.com. claude-haiku-5-5 is cheaper and faster."),
    CUSTOM("Custom", "", "", "Any OpenAI-compatible endpoint"),
}

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("hukum", Context.MODE_PRIVATE)

    var provider: Provider
        get() = runCatching { Provider.valueOf(sp.getString("provider", null) ?: "GEMINI") }.getOrDefault(Provider.GEMINI)
        set(v) = sp.edit().putString("provider", v.name).apply()

    fun apiKey(p: Provider): String = sp.getString("key_${p.name}", "")!!.trim()
    fun setApiKey(p: Provider, v: String) = sp.edit().putString("key_${p.name}", v).apply()

    fun modelRaw(p: Provider): String = sp.getString("model_${p.name}", "")!!
    fun model(p: Provider): String = modelRaw(p).trim().ifBlank { p.defaultModel }
    fun setModel(p: Provider, v: String) = sp.edit().putString("model_${p.name}", v).apply()

    var customBaseUrl: String
        get() = sp.getString("custom_base", "")!!
        set(v) = sp.edit().putString("custom_base", v).apply()

    fun baseUrl(p: Provider): String = (if (p == Provider.CUSTOM) customBaseUrl else p.baseUrl).trim().trimEnd('/')

    var autoSend: Boolean
        get() = sp.getBoolean("auto_send", true)
        set(v) = sp.edit().putBoolean("auto_send", v).apply()

    var speakReplies: Boolean
        get() = sp.getBoolean("speak", true)
        set(v) = sp.edit().putBoolean("speak", v).apply()

    var showBubble: Boolean
        get() = sp.getBoolean("bubble", true)
        set(v) = sp.edit().putBoolean("bubble", v).apply()

    /** Simple commands ("open X", "scroll down") are handled on-device without calling the LLM. */
    var preferLocal: Boolean
        get() = sp.getBoolean("prefer_local", true)
        set(v) = sp.edit().putBoolean("prefer_local", v).apply()

    var countryCode: String
        get() = sp.getString("cc", "91")!!
        set(v) = sp.edit().putString("cc", v.filter(Char::isDigit)).apply()

    var speechLang: String
        get() = sp.getString("lang", "en-IN")!!
        set(v) = sp.edit().putString("lang", v.trim()).apply()
}
