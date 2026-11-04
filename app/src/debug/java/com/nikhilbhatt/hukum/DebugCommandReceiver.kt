package com.nikhilbhatt.hukum

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug-only test hook (protected by the DUMP permission, which only the shell holds):
 *   adb shell am broadcast -a com.nikhilbhatt.hukum.COMMAND -p com.nikhilbhatt.hukum --es text "open youtube"
 * Results are logged under the "Hukum" logcat tag.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("text")?.let { Brain.handle(context, it) }
    }
}
