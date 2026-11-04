package com.nikhilbhatt.hukum

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF8B7CF6),
                onPrimary = Color.White,
                background = Color(0xFF0F1115),
                surface = Color(0xFF181B22),
                surfaceVariant = Color(0xFF222632),
            )) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HomeScreen(resumeTick.intValue)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

private fun granted(ctx: Context, perm: String) =
    ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

@Composable
private fun HomeScreen(resumeTick: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val serviceOn by HukumService.running.collectAsState()
    val busy by Brain.busy.collectAsState()
    val log by Brain.log.collectAsState()

    var permTick by remember { mutableIntStateOf(0) }
    val micOk = remember(resumeTick, permTick) { granted(ctx, Manifest.permission.RECORD_AUDIO) }
    val contactsOk = remember(resumeTick, permTick) { granted(ctx, Manifest.permission.READ_CONTACTS) }
    val notifOk = remember(resumeTick) { NotifService.isEnabled(ctx) }
    val pending by Brain.pending.collectAsState()
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }

    var listening by remember { mutableStateOf(false) }
    val voice = remember {
        VoiceInput(ctx,
            onText = { Brain.handle(ctx, it) },
            onListening = { listening = it },
            onError = { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() })
    }
    DisposableEffect(Unit) { onDispose { voice.destroy() } }
    // Toasts from the floating mic need notification permission on Android 13+; ask once.
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var command by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Hukum", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Voice control for your phone. Tap the mic and say what you want.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        // ---- mic ----
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val micColor = when {
                listening -> Color(0xFFE5484D)
                busy -> Color(0xFFE8A317)
                else -> MaterialTheme.colorScheme.primary
            }
            Box(
                Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(micColor)
                    .clickable {
                        if (!micOk) permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        else voice.toggle(prefs.speechLang)
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Mic, "Speak", Modifier.size(44.dp), tint = Color.White) }
            Spacer(Modifier.height(8.dp))
            Text(when {
                listening -> "Listening…"
                busy -> "Working…"
                else -> "Tap to speak"
            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("…or type a command") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { Brain.handle(ctx, command); command = "" }),
            trailingIcon = {
                IconButton(onClick = { Brain.handle(ctx, command); command = "" }, enabled = command.isNotBlank() && !busy) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Run")
                }
            },
        )

        pending?.let { p ->
            Section(p.title) {
                Text(p.text, fontSize = 17.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    OutlinedButton(onClick = { Brain.cancel(ctx) }) { Text("Cancel") }
                    Button(onClick = { Brain.confirm(ctx) }) { Text("Send") }
                }
            }
        }

        // ---- setup ----
        Section("Setup") {
            SetupRow("Accessibility service", "Needed to scroll, close apps, tap Send and show the floating mic.", serviceOn) {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            if (!serviceOn) {
                Text("Switch greyed out? Android blocks this for sideloaded apps. Open App info, tap ⋮ (top right), " +
                    "choose \"Allow restricted settings\", then try again.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }) { Text("Open App info") }
            }
            SetupRow("Microphone", "For voice commands.", micOk) { permLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            SetupRow("Contacts", "To find who to WhatsApp.", contactsOk) { permLauncher.launch(Manifest.permission.READ_CONTACTS) }
            SetupRow("Notification access", "To read and reply to WhatsApp messages.", notifOk) {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        // ---- AI ----
        Section("AI brain") {
            var provider by remember { mutableStateOf(prefs.provider) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Provider.entries.forEach { p ->
                    FilterChip(selected = provider == p, onClick = { provider = p; prefs.provider = p }, label = { Text(p.label) })
                }
            }
            var key by remember(provider) { mutableStateOf(prefs.apiKey(provider)) }
            var model by remember(provider) { mutableStateOf(prefs.modelRaw(provider)) }
            var base by remember { mutableStateOf(prefs.customBaseUrl) }
            var showKey by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = key, onValueChange = { key = it; prefs.setApiKey(provider, it) },
                label = { Text("${provider.label} API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
            )
            Text(provider.keyHint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = model, onValueChange = { model = it; prefs.setModel(provider, it) },
                label = { Text("Model") }, placeholder = { Text(provider.defaultModel) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (provider == Provider.CUSTOM) {
                OutlinedTextField(
                    value = base, onValueChange = { base = it; prefs.customBaseUrl = it },
                    label = { Text("Base URL (…/v1)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            var testResult by remember(provider) { mutableStateOf<String?>(null) }
            var testing by remember { mutableStateOf(false) }
            OutlinedButton(enabled = !testing, onClick = {
                testing = true
                testResult = null
                scope.launch {
                    testResult = try {
                        val plan = Planner.parse(LlmClient.complete(prefs, LlmRequest(Planner.SYSTEM, "open youtube and scroll down twice", json = true)))
                        "Works: ${plan.describe()}"
                    } catch (e: Exception) {
                        "Failed: ${e.message}"
                    }
                    testing = false
                }
            }) { Text(if (testing) "Testing…" else "Test connection") }
            testResult?.let { Text(it, fontSize = 13.sp) }
            Text("Screenshots (\"look at my screen\") need a model that can see images: Gemini or Claude. " +
                "gpt-oss is text-only, so with Groq/Cerebras Hukum uses your Gemini or Claude key for screenshots if one is set. " +
                "Screen contents are only sent when you ask about the screen.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---- options ----
        Section("Options") {
            var bubble by remember { mutableStateOf(prefs.showBubble) }
            ToggleRow("Floating mic bubble", "Tap it from any app to speak. Long-press opens Hukum.", bubble) {
                bubble = it; prefs.showBubble = it; HukumService.instance?.setBubbleVisible(it)
            }
            var autoSend by remember { mutableStateOf(prefs.autoSend) }
            ToggleRow("Auto-tap Send in WhatsApp", "Off: Hukum fills in the message and you tap Send.", autoSend) {
                autoSend = it; prefs.autoSend = it
            }
            var speak by remember { mutableStateOf(prefs.speakReplies) }
            ToggleRow("Speak replies", null, speak) { speak = it; prefs.speakReplies = it }
            var local by remember { mutableStateOf(prefs.preferLocal) }
            ToggleRow("Handle simple commands on-device", "\"open X\", \"scroll down\", \"go back\" skip the AI: faster and saves free-tier quota.", local) {
                local = it; prefs.preferLocal = it
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                var cc by remember { mutableStateOf(prefs.countryCode) }
                OutlinedTextField(cc, { cc = it.filter(Char::isDigit); prefs.countryCode = cc },
                    label = { Text("Country code") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                var lang by remember { mutableStateOf(prefs.speechLang) }
                OutlinedTextField(lang, { lang = it; prefs.speechLang = it },
                    label = { Text("Speech language") }, singleLine = true, modifier = Modifier.weight(1f))
            }
        }

        Section("Try saying") {
            listOf(
                "Open YouTube",
                "Open Instagram and scroll down 3 times",
                "Close YouTube",
                "Go home",
                "Send good morning to Mom on WhatsApp",
                "Read my messages",
                "Reply to Rahul",
                "Reply to Mom saying on my way",
                "What's on my screen?",
                "Look at my screen and describe the photo",
            ).forEach { ex ->
                Text("“$ex”", Modifier.fillMaxWidth().clickable { command = ex }.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (log.isNotEmpty()) Section("Activity") {
            log.forEach { e ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(e.time, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(e.kind, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp),
                        color = when (e.kind) {
                            "Error" -> Color(0xFFFF7B7B)
                            "Done" -> Color(0xFF6EE7A0)
                            "Ask" -> Color(0xFFE8A317)
                            else -> MaterialTheme.colorScheme.primary
                        })
                    Text(e.text, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        content()
    }
}

@Composable
private fun SetupRow(title: String, subtitle: String, ok: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
            tint = if (ok) Color(0xFF6EE7A0) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title)
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ok) Button(onClick = onFix) { Text("Allow") }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            subtitle?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
