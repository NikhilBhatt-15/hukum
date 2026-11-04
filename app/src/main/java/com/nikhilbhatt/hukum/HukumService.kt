package com.nikhilbhatt.hukum

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * The part of Hukum that can act on other apps: gestures, global actions, tapping WhatsApp's
 * Send button, and the floating mic bubble (drawn as an accessibility overlay, so no extra
 * "draw over apps" permission is needed).
 */
class HukumService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: HukumService? = null
            private set
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var voice: VoiceInput
    private var bubble: Bubble? = null

    /** Last app the user had in front (not Hukum, the launcher, the keyboard or System UI). */
    @Volatile
    var lastAppPkg: String? = null
        private set

    private val launcherPkg by lazy {
        // Settings ships a fallback HOME activity; it must never count as "the launcher".
        packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName
            ?.takeIf { it != "com.android.settings" && it != "android" }
    }

    override fun onServiceConnected() {
        instance = this
        _running.value = true
        voice = VoiceInput(
            this,
            onText = { Brain.handle(this, it) },
            onListening = { bubble?.setState(if (it) Bubble.State.LISTENING else Bubble.State.IDLE) },
            onError = { toast(it) },
        )
        setBubbleVisible(Prefs(this).showBubble)
        scope.launch {
            Brain.busy.collect { busy ->
                if (!voice.listening) bubble?.setState(if (busy) Bubble.State.THINKING else Bubble.State.IDLE)
            }
        }
        scope.launch {
            Brain.pending.collect { p ->
                confirmCard?.remove()
                confirmCard = p?.let { ConfirmCard(this@HukumService, it.title, it.text,
                    onSend = { Brain.confirm(this@HukumService) },
                    onCancel = { Brain.cancel(this@HukumService) }) }
            }
        }
    }

    private var confirmCard: ConfirmCard? = null

    /** Start listening, e.g. right after Hukum asks "Send it?". */
    fun listen() {
        if (::voice.isInitialized && !voice.listening) voice.start(Prefs(this).speechLang)
    }

    // ---- reading the screen ----

    private fun activeAppRoot(): AccessibilityNodeInfo? = windows
        .firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
        ?.root ?: rootInActiveWindow

    fun frontPkg(): String? = activeAppRoot()?.packageName?.toString()

    /** All visible text on the current screen, top to bottom, de-duplicated. */
    fun screenText(maxChars: Int = 6000): String {
        val root = activeAppRoot() ?: return ""
        val sb = StringBuilder()
        val seen = HashSet<String>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 60 || sb.length > maxChars) return
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty() && seen.add(t)) sb.append(t).append('\n')
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return sb.toString().take(maxChars)
    }

    /** Screenshot of the current screen (without Hukum's own overlays) as a downscaled JPEG. */
    suspend fun screenshotJpeg(): ByteArray {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            throw java.io.IOException("Screenshots need Android 11 or newer.")
        }
        bubble?.setHidden(true)
        confirmCard?.setHidden(true)
        try {
            delay(250)
            val bmp = suspendCancellableCoroutine { cont ->
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(r: ScreenshotResult) {
                        val hw = android.graphics.Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                        val soft = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        r.hardwareBuffer.close()
                        if (cont.isActive) {
                            if (soft != null) cont.resume(soft)
                            else cont.resumeWith(Result.failure(java.io.IOException("Couldn't read the screenshot.")))
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val msg = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "This screen is protected, so it can't be captured (banking and private apps block screenshots)."
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Too many screenshots at once, try again."
                            else -> "Couldn't take a screenshot (error $errorCode)."
                        }
                        if (cont.isActive) cont.resumeWith(Result.failure(java.io.IOException(msg)))
                    }
                })
            }
            return kotlinx.coroutines.withContext(Dispatchers.Default) {
                val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
                val small = if (scale < 1f)
                    android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                else bmp
                java.io.ByteArrayOutputStream().also {
                    small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, it)
                }.toByteArray()
            }
        } finally {
            bubble?.setHidden(false)
            confirmCard?.setHidden(false)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == launcherPkg || pkg == currentImePkg()) {
            if (pkg == launcherPkg) lastAppPkg = null
            return
        }
        lastAppPkg = pkg
    }

    /** The app currently in front, unless that's Hukum, the launcher or System UI. */
    private fun frontAppPkg(): String? = windows
        .firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
        ?.root?.packageName?.toString()
        ?.takeIf { it != packageName && it != launcherPkg && it != "com.android.systemui" }

    private fun currentImePkg(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        _running.value = false
        bubble?.remove()
        bubble = null
        confirmCard?.remove()
        confirmCard = null
        if (::voice.isInitialized) voice.destroy()
        scope.cancel()
        super.onDestroy()
    }

    fun setBubbleVisible(show: Boolean) {
        if (show && bubble == null) {
            bubble = Bubble(this,
                onTap = { voice.toggle(Prefs(this).speechLang) },
                onLongPress = {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
        } else if (!show) {
            bubble?.remove()
            bubble = null
        }
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---- actions ----

    fun global(action: Int) = performGlobalAction(action)

    suspend fun scroll(direction: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        // Finger moves opposite to where the user wants to go.
        return when (direction) {
            "up" -> swipe(w / 2, h * 0.30f, w / 2, h * 0.72f)
            "left" -> swipe(w * 0.15f, h * 0.5f, w * 0.85f, h * 0.5f)
            "right" -> swipe(w * 0.85f, h * 0.5f, w * 0.15f, h * 0.5f)
            else -> swipe(w / 2, h * 0.72f, w / 2, h * 0.30f)
        }
    }

    private suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 280): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
                .build()
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!accepted && cont.isActive) cont.resume(false)
        }

    /**
     * Closes an app the way a person would: bring it to the front, open Recents, swipe its card
     * away, then go home. killBackgroundProcesses alone ignores an app you've only just left.
     * Returns the package closed, or null if there was nothing to close.
     */
    suspend fun closeApp(pkg: String?): String? {
        val target = pkg ?: frontAppPkg() ?: lastAppPkg ?: return null
        if (target == packageName) return null
        if (rootInActiveWindow?.packageName?.toString() != target) {
            val launch = packageManager.getLaunchIntentForPackage(target)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: return null
            startActivity(launch)
            delay(1300)
        }
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        delay(1000)
        val dm = resources.displayMetrics
        swipe(dm.widthPixels / 2f, dm.heightPixels * 0.5f, dm.widthPixels / 2f, dm.heightPixels * 0.04f, 180)
        delay(600)
        performGlobalAction(GLOBAL_ACTION_HOME)
        delay(400)
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(target)
        if (target == lastAppPkg) lastAppPkg = null
        return target
    }

    /** Waits for WhatsApp's chat screen and taps Send. */
    suspend fun tapWhatsAppSend(waPkg: String, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(400)
            val root = rootInActiveWindow ?: continue
            if (root.packageName?.toString() != waPkg) continue
            val send = root.findAccessibilityNodeInfosByViewId("$waPkg:id/send").firstOrNull()
                ?: findByDescription(root, setOf("send", "भेजें"))
            if (send != null && click(send)) return true
        }
        return false
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun findByDescription(node: AccessibilityNodeInfo, names: Set<String>, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 40) return null
        if (node.contentDescription?.toString()?.lowercase() in names) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findByDescription(child, names, depth + 1)?.let { return it }
        }
        return null
    }
}

/** Draggable floating mic button. Tap = listen, long-press = open Hukum. */
@SuppressLint("ClickableViewAccessibility")
class Bubble(
    private val svc: AccessibilityService,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    enum class State(val color: Int) {
        IDLE(Color.parseColor("#5B4BDB")),
        LISTENING(Color.parseColor("#E5484D")),
        THINKING(Color.parseColor("#E8A317")),
    }

    private val wm = svc.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private val density = svc.resources.displayMetrics.density
    private val size = (56 * density).toInt()
    private val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(State.IDLE.color) }

    private val view = ImageView(svc).apply {
        setImageResource(R.drawable.ic_launcher_foreground)
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = bg
        elevation = 8 * density
        contentDescription = "Hukum: tap to speak"
    }

    private val params = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = svc.resources.displayMetrics.widthPixels - size - (8 * density).toInt()
        y = (svc.resources.displayMetrics.heightPixels * 0.45f).toInt()
    }

    init {
        val slop = ViewConfiguration.get(svc).scaledTouchSlop
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var downAt = 0L
        var dragging = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    downAt = System.currentTimeMillis()
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) {
                    if (System.currentTimeMillis() - downAt >= longPressMs) onLongPress() else onTap()
                }
            }
            true
        }
        wm.addView(view, params)
    }

    fun setState(s: State) = bg.setColor(s.color)

    fun setHidden(hidden: Boolean) {
        view.visibility = if (hidden) android.view.View.INVISIBLE else android.view.View.VISIBLE
    }

    fun remove() = runCatching { wm.removeView(view) }
}

/** Floating "Send this?" card shown over any app while a drafted message waits for approval. */
class ConfirmCard(
    private val svc: AccessibilityService,
    title: String,
    text: String,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    private val wm = svc.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private val d = svc.resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = android.widget.TextView(svc).apply {
        this.text = label
        textSize = 16f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(px(18), px(10), px(18), px(10))
        background = GradientDrawable().apply {
            cornerRadius = 20 * d
            if (filled) setColor(State.SEND) else setStroke(px(1), Color.parseColor("#66FFFFFF"))
        }
        setOnClickListener { onClick() }
    }

    private object State { val SEND = Color.parseColor("#5B4BDB") }

    private val view = android.widget.LinearLayout(svc).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(px(18), px(16), px(18), px(14))
        background = GradientDrawable().apply { cornerRadius = 18 * d; setColor(Color.parseColor("#F21B1E27")) }
        elevation = 10 * d
        addView(android.widget.TextView(svc).apply {
            this.text = title
            textSize = 13f
            setTextColor(Color.parseColor("#B3FFFFFF"))
        })
        addView(android.widget.TextView(svc).apply {
            this.text = text
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(0, px(6), 0, px(12))
        })
        addView(android.widget.LinearLayout(svc).apply {
            gravity = Gravity.END
            addView(button("Cancel", false, onCancel))
            addView(android.widget.Space(svc), android.widget.LinearLayout.LayoutParams(px(10), 1))
            addView(button("Send", true, onSend))
        })
    }

    init {
        val params = WindowManager.LayoutParams(
            svc.resources.displayMetrics.widthPixels - px(32), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = px(96)
        }
        wm.addView(view, params)
    }

    fun setHidden(hidden: Boolean) {
        view.visibility = if (hidden) android.view.View.INVISIBLE else android.view.View.VISIBLE
    }

    fun remove() = runCatching { wm.removeView(view) }
}
