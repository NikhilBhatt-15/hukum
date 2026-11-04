# Hukum

A small voice assistant for Android that actually operates your phone. Tap the floating mic, say what you want, and Hukum does it:

- **Open apps**: "open YouTube", "Instagram kholo"
- **Close apps**: "close YouTube", "close this app"
- **Scroll**: "scroll down", "scroll up 3 times", "scroll right"
- **Navigate**: "go back", "go home", "recent apps"
- **Send WhatsApp messages**: "send good morning to Mom on WhatsApp", "WhatsApp Rahul saying I'll be late"
- **Chain steps**: "open Instagram and scroll down twice"

It works with **free** LLM API keys: Gemini Flash, or `gpt-oss-120b` on Groq, OpenRouter or Cerebras.

## Install

1. Download `hukum-vX.Y.Z.apk` from [Releases](https://github.com/NikhilBhatt-15/hukum/releases) and install it (allow "install unknown apps").
2. Open Hukum and work through the **Setup** card:
   - **Accessibility service**: tap Allow and turn on *Hukum voice control*.
     If the switch is greyed out (Android 13+ blocks this for sideloaded apps): open **App info → ⋮ → Allow restricted settings**, then try again.
   - **Microphone** and **Contacts** (Contacts is only needed for WhatsApp).
3. In **AI brain**, pick a provider and paste a free API key:

   | Provider | Default model | Get a key |
   |---|---|---|
   | Gemini | `gemini-2.5-flash` | aistudio.google.com/apikey |
   | Groq | `openai/gpt-oss-120b` | console.groq.com/keys |
   | OpenRouter | `openai/gpt-oss-120b:free` | openrouter.ai/keys |
   | Cerebras | `gpt-oss-120b` | cloud.cerebras.ai |
   | Custom | anything | any OpenAI-compatible `/v1` endpoint |

   Tap **Test connection**. If a model name is retired, type the new one in the Model field.

A purple mic bubble now floats over every app. **Tap** to speak, **drag** to move it, **long-press** to open Hukum. It turns red while listening and amber while working.

## Options

- **Auto-tap Send in WhatsApp**: on by default. Turn it off and Hukum only fills in the message so you can check it and tap Send yourself.
- **Handle simple commands on-device**: "open X", "close X", "scroll", "back", "home" are parsed locally with no AI call, so they're instant and don't use your free-tier quota. Anything else goes to the LLM. If the LLM is unreachable, a built-in parser still handles the common WhatsApp phrasings.
- **Country code** (default 91): added to contact numbers saved without one, because WhatsApp needs the full international number.
- **Speech language** (default `en-IN`): try `hi-IN` for Hindi.

## How it works

```
mic (SpeechRecognizer) ─► text ─► on-device parser ──► plan ─► executor ─► spoken reply (TTS)
                                   └─ no match ─► LLM (JSON plan) ─┘
```

- **Speech to text** uses Android's built-in `SpeechRecognizer` (the Google app), which is free.
- **Planning**: the LLM gets one system prompt listing the available actions and returns JSON such as
  `{"actions":[{"type":"open_app","app":"YouTube"},{"type":"scroll","direction":"down","times":2}],"reply":"..."}`.
  Only your command text is sent to the LLM. Your screen contents are never sent.
- **Acting** happens in an `AccessibilityService`:
  - *open*: fuzzy-matches the spoken name against installed launcher apps, then starts the app.
  - *close*: goes Home, then `killBackgroundProcesses` on that app (or on the last app you had open).
  - *scroll*: `dispatchGesture` swipes.
  - *WhatsApp*: looks up the contact (fuzzy name match), opens `api.whatsapp.com/send?phone=…&text=…` in WhatsApp, waits for the chat screen, then taps the Send button.
- The floating mic is a `TYPE_ACCESSIBILITY_OVERLAY` window, so it doesn't need the "draw over other apps" permission.

## Build

Requires JDK 17+ and the Android SDK (API 35).

```bash
./gradlew testDebugUnitTest assembleDebug     # app/build/outputs/apk/debug/
./gradlew assembleRelease                     # needs keystore.properties, see below
```

Release signing reads `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`), which is kept out of git. Without it the release APK is unsigned.

## Limits

- Free-tier keys have rate limits. If you hit one, Hukum says so. Simple on-device commands keep working.
- "Close" stops the app's process and leaves it in Recents. Android doesn't let apps swipe each other out of Recents.
- The WhatsApp Send tap depends on WhatsApp's layout (`com.whatsapp:id/send`). If an update breaks it, turn off auto-send and tap Send yourself.
- Hukum can't unlock a locked phone.

## License

MIT
