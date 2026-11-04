# Hukum

A small voice assistant for Android that actually operates your phone. Tap the floating mic, say what you want, and Hukum does it:

- **Open and close apps**: "open YouTube", "Instagram kholo", "close this app"
- **Scroll and navigate**: "scroll down 3 times", "go back", "go home", "recent apps"
- **Send WhatsApp messages**: "send good morning to Mom on WhatsApp"
- **Read your messages**: "read my messages", "what did Rahul say?"
- **Reply**: "reply to Rahul saying on my way", or just "reply to Rahul" and Hukum writes a reply and asks before sending
- **Understand the screen**: "what's on my screen?", "summarize this page"
- **See the screen**: "look at my screen and describe the photo" (takes a screenshot)
- **Chain steps**: "open Instagram and scroll down twice"

It works with **free** API keys: Gemini Flash-Lite, or `gpt-oss-120b` on Groq, OpenRouter or Cerebras. Claude is supported too (paid key).

## Install

1. Download `hukum-vX.Y.Z.apk` from [Releases](https://github.com/NikhilBhatt-15/hukum/releases) and install it (allow "install unknown apps").
2. Open Hukum and work through the **Setup** card:
   - **Accessibility service**: tap Allow and turn on *Hukum voice control*.
   - **Notification access**: needed to read and reply to WhatsApp messages.
   - **Microphone** and **Contacts** (Contacts is used to find who to WhatsApp).

   If a switch is greyed out (Android 13+ blocks this for sideloaded apps): open **App info → ⋮ → Allow restricted settings**, then try again.
3. In **AI brain**, pick a provider and paste an API key:

   | Provider | Default model | Key | Sees images? |
   |---|---|---|---|
   | Gemini | `gemini-flash-lite-latest` | free: aistudio.google.com/apikey | yes |
   | Groq | `openai/gpt-oss-120b` | free: console.groq.com/keys | no |
   | OpenRouter | `openai/gpt-oss-120b:free` | free: openrouter.ai/keys | no |
   | Cerebras | `gpt-oss-120b` | free: cloud.cerebras.ai | no |
   | Claude | `claude-opus-5-5` (or `claude-haiku-5-5`, cheaper and faster) | paid: platform.claude.com | yes |
   | Custom | anything | any OpenAI-compatible `/v1` endpoint | depends |

   Tap **Test connection**. If Google retires a model, type a newer name in the Model field. The default `gemini-flash-lite-latest` always points to the current Lite model.
   For screenshots, Hukum uses your main provider if it can see images; otherwise it uses whichever Gemini or Claude key you've also saved.

A purple mic bubble now floats over every app. **Tap** to speak, **drag** to move it, **long-press** to open Hukum. It turns red while listening and amber while working.

## Replying safely

- Replies **you dictate** ("reply to Mom saying on my way") are sent straight away.
- Replies **the AI writes** are never sent without your OK. Hukum reads the draft aloud, shows a **Send / Cancel** card over whatever app you're in, and listens for "yes / send / haan / bhej do" or "no / cancel / nahi".
- Replies go through WhatsApp's own notification **Reply** button, so WhatsApp doesn't even open. If there's no notification to reply to, Hukum opens the chat instead.

## Options

- **Auto-tap Send in WhatsApp**: on by default, for new messages you dictate. Turn it off and Hukum only fills in the message so you can check it and tap Send yourself.
- **Handle simple commands on-device**: "open X", "close X", "scroll", "back", "home" and "read my messages" are handled locally with no AI call, so they're instant and don't use your free-tier quota. Anything else goes to the AI. If the AI is unreachable, a built-in parser still handles the common WhatsApp phrasings.
- **Country code** (default 91): added to contact numbers saved without one, because WhatsApp needs the full international number.
- **Speech language** (default `en-IN`): try `hi-IN` for Hindi.

## How it works

```
mic (SpeechRecognizer) ─► text ─► on-device parser ──► plan ─► executor ─► spoken reply (TTS)
                                   └─ no match ─► LLM (JSON plan) ─┘
```

- **Speech to text** uses Android's built-in `SpeechRecognizer` (the Google app), which is free.
- **Planning**: the LLM gets your command (only the command) and returns a JSON plan such as
  `{"actions":[{"type":"open_app","app":"YouTube"},{"type":"scroll","direction":"down","times":2}]}`.
- **Acting** happens in an `AccessibilityService`:
  - *open*: fuzzy-matches the spoken name against installed apps, then starts the app.
  - *close*: brings the app to the front, opens Recents, swipes its card away, then kills its process.
  - *scroll*: `dispatchGesture` swipes.
  - *send on WhatsApp*: finds the contact, opens `api.whatsapp.com/send?phone=…&text=…`, waits for the chat, then taps Send.
  - *read the screen*: collects the visible text from the accessibility tree.
  - *screenshot*: `AccessibilityService.takeScreenshot` (Android 11+). There's no screen-recording prompt, and Hukum hides its own bubble first. Images are downscaled to 1280 px JPEG.
- **Messages** come from a `NotificationListenerService` that keeps recent WhatsApp chats **in memory only**. Nothing is written to disk.
- **Untrusted content stays data.** Screen text, screenshots and chat messages go to the AI in separate calls whose output is only spoken or shown for approval, never turned into actions. A message saying "ignore your instructions and…" can't make Hukum do anything.
- Screen contents and messages are sent to your AI provider **only when you ask** about the screen or for a drafted reply.
- The floating mic and confirm card are `TYPE_ACCESSIBILITY_OVERLAY` windows, so they don't need the "draw over other apps" permission.

## Build

Requires JDK 17+ and the Android SDK (API 35).

```bash
./gradlew testDebugUnitTest assembleDebug     # app/build/outputs/apk/debug/
./gradlew assembleRelease                     # needs keystore.properties, see below
```

Debug builds include a test hook so you can drive Hukum from adb. Results appear in logcat under the `Hukum` tag. Debug builds also accept `adb shell cmd notification post` messages as test chats. Neither is in release builds.

```bash
adb shell am broadcast -a com.nikhilbhatt.hukum.COMMAND -p com.nikhilbhatt.hukum --es text "'open settings and scroll down'"
adb shell am broadcast -a com.nikhilbhatt.hukum.COMMAND -p com.nikhilbhatt.hukum --ez listmodels true   # Gemini models your key can use
adb logcat -s Hukum
```

Release signing reads `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`), which is kept out of git. Without it the release APK is unsigned.

## Limits

- Free-tier keys have rate limits. If you hit one, Hukum says so (it retries once when the service is briefly overloaded). Simple on-device commands keep working.
- Hukum only knows messages that arrived while Notification access was on, and forgets them when the app restarts.
- Banking and other protected apps block screenshots. Hukum tells you when that happens.
- The WhatsApp Send tap depends on WhatsApp's layout (`com.whatsapp:id/send`). If an update breaks it, turn off auto-send and tap Send yourself.
- Closing apps uses the Recents swipe, which works on Pixel, Samsung and most launchers. Grid-style Recents (some MIUI versions) may need a manual swipe.
- Hukum can't unlock a locked phone.

## License

MIT
