package com.nikhilbhatt.hukum

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Debug-only test hook (protected by the DUMP permission, which only the shell holds):
 *   adb shell am broadcast -a com.nikhilbhatt.hukum.COMMAND -p com.nikhilbhatt.hukum --es text "open youtube"
 *   adb shell am broadcast -a com.nikhilbhatt.hukum.COMMAND -p com.nikhilbhatt.hukum --ez listmodels true
 * Results are logged under the "Hukum" logcat tag.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("listmodels", false)) {
            val prefs = Prefs(context)
            val pending = goAsync()
            Thread {
                try {
                    val c = URL("${prefs.baseUrl(Provider.GEMINI)}/models?pageSize=200").openConnection() as HttpURLConnection
                    c.setRequestProperty("x-goog-api-key", prefs.apiKey(Provider.GEMINI))
                    val body = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
                    Regex("\"name\": \"models/([^\"]+)\"").findAll(body).forEach { Log.i("Hukum", "model: ${it.groupValues[1]}") }
                    if (c.responseCode !in 200..299) Log.i("Hukum", "listmodels HTTP ${c.responseCode}")
                } catch (e: Exception) {
                    Log.i("Hukum", "listmodels failed: ${e.message}")
                } finally {
                    pending.finish()
                }
            }.start()
            return
        }
        intent.getStringExtra("text")?.let { Brain.handle(context, it) }
    }
}
