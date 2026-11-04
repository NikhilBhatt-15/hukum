package com.nikhilbhatt.hukum

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech

/** Wraps Android's SpeechRecognizer for one-shot "tap, speak, done" input. Main thread only. */
class VoiceInput(
    private val ctx: Context,
    private val onText: (String) -> Unit,
    private val onListening: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    var listening = false
        private set

    fun toggle(lang: String) = if (listening) stop() else start(lang)

    fun start(lang: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onError("No speech recognizer on this phone. Install or enable the Google app.")
            return
        }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("Microphone permission isn't granted. Open Hukum to allow it.")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(ctx).apply { setRecognitionListener(listener) }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        setListening(true)
        recognizer?.startListening(intent)
    }

    fun stop() {
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        listening = false
    }

    private fun setListening(v: Boolean) {
        listening = v
        onListening(v)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            setListening(false)
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) onError("Didn't catch that.") else onText(text)
        }

        override fun onError(error: Int) {
            setListening(false)
            onError(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission isn't granted."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs internet."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy, try again."
                    else -> "Speech error ($error)."
                }
            )
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}

/** Text-to-speech for short replies, with an optional callback when speaking finishes. */
class Speaker(ctx: Context) : TextToSpeech.OnInitListener {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val callbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private var counter = 0
    private var state = 0 // 0 = starting, 1 = ready, -1 = failed
    private var pending: Pair<String, (() -> Unit)?>? = null

    private val tts = TextToSpeech(ctx.applicationContext, this).apply {
        setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
        })
    }

    private fun finish(id: String?) {
        id?.let { callbacks.remove(it) }?.let { main.post(it) }
    }

    override fun onInit(status: Int) {
        state = if (status == TextToSpeech.SUCCESS) 1 else -1
        pending?.let { (t, cb) -> say(t, cb) }
        pending = null
    }

    fun say(text: String, onDone: (() -> Unit)? = null) {
        when {
            text.isBlank() || state == -1 -> onDone?.let { main.post(it) }
            state == 0 -> pending = text to onDone
            else -> {
                val id = "hukum-${counter++}"
                onDone?.let { callbacks[id] = it }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            }
        }
    }
}
