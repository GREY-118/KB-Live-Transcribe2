# Caption Overlay

A floating, always-on-top live-captioning overlay for Android. It listens through
the microphone and shows a draggable subtitle bar over **any app**, plus a
draggable bubble menu (like a chat head) that expands into quick controls.

## What it does

- **Foreground service + microphone** — captures ambient audio continuously,
  including whatever's playing out loud from another app, a video call, a
  video, etc. (This is mic pickup, the same approach Android's own Live
  Caption/Live Transcribe use — not internal audio tapping.)
- **Continuous on-device speech recognition** — built on Android's
  `SpeechRecognizer`, preferring the on-device model (`createOnDeviceSpeechRecognizer`)
  when the phone supports it, so it works **free, with no API key, and often with
  no internet connection**. It restarts itself after every utterance so
  captioning never really "stops."
- **Draggable subtitle bar** — floats over whatever app is in front, shows live
  partial + final text.
- **Expandable bubble menu** — tap the floating bubble to expand a small radial
  menu: pause/resume mic, cycle font size, cycle language, cycle opacity, close.
  Drag the bubble anywhere on screen.

## About "Google's Live Transcribe open-source code"

Worth knowing before you rely on it: Google only open-sourced the **client
library** ([`google/live-transcribe-speech-engine`](https://github.com/google/live-transcribe-speech-engine)).
It's not a standalone offline engine — it's the plumbing that streams your mic
audio to Google's **paid Cloud Speech-to-Text API**, and it needs a Google
Cloud project, a billing-enabled API key, and an internet connection.

This project uses Android's free, built-in `SpeechRecognizer` instead, so it
works out of the box. If you want Live Transcribe's actual cloud engine
(better accuracy on noisy/long-form audio, 70+ languages, resilient
reconnection), you can swap it in:

1. Add the `live-transcribe-speech-engine` library as a module dependency
   (follow that repo's own build instructions — it includes native Opus code).
2. Get a Speech-to-Text API key from Google Cloud Console.
3. In `OverlayService.kt`, replace `setupRecognizer()` / `startRecognitionLoop()`
   with a `CloudSpeechSessionFactory`-based session, feeding it audio from an
   `AudioRecord` instead of `SpeechRecognizer`.

Everything else (overlay windows, drag, expandable menu, service lifecycle)
stays the same either way.

## Project layout

```
app/src/main/java/com/captionoverlay/app/
  MainActivity.kt      – permission flow (mic + "draw over other apps") and start/stop button
  OverlayService.kt     – the floating windows, drag handling, recognition loop
  Prefs.kt              – tiny persisted settings (language, font size, opacity, mic on/off)
app/src/main/res/       – layouts, vector icons, strings, colors, adaptive launcher icon
```

## Permissions it needs (and why)

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | listen to ambient audio |
| `SYSTEM_ALERT_WINDOW` | draw the floating bar/menu over other apps |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` | keep listening while you use other apps |
| `POST_NOTIFICATIONS` | show the required "still listening" notification (Android 13+) |

The app requests the first and shows a system settings screen for the second —
neither can be silently auto-granted, that's an Android platform restriction
for anything this powerful (mic + drawing over other apps), not a limitation
of this app.

## Building the APK

I wrote and organized all the source, but I can't compile a `.apk` binary in
the sandbox this conversation runs in — it has no Android SDK and no network
access to fetch one. Pick either path below; both take a few minutes.

### Option A — GitHub Actions (no Android Studio needed)
1. Create a new GitHub repo and push this whole folder to it.
2. GitHub Actions will automatically run `.github/workflows/build-apk.yml`.
3. Open the **Actions** tab → the latest run → download the
   `caption-overlay-debug-apk` artifact. That's your installable APK.
4. On your phone: allow "install unknown apps" for your browser/file manager,
   then open the APK to install.

### Option B — Android Studio (fastest if you already have it)
1. Open the `LiveCaptionOverlay` folder as a project in Android Studio
   (Hedgehog/2023.1 or newer).
2. Let it sync Gradle (needs internet the first time, to fetch dependencies).
3. Click **Run ▶** with a device/emulator connected, or
   **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. Find the APK in `app/build/outputs/apk/debug/`.

Either way produces a debug-signed APK — fine for installing on your own
device, not for Play Store distribution (that needs a release signing key).

## Known limitations / good next steps

- Android's `SpeechRecognizer` processes speech in short utterances, not a
  true infinite stream — there's a brief (usually sub-second) gap between
  sentences while it restarts. That's the tradeoff for "free, no API key."
- Recognition quality depends on the phone's on-device model. Older/budget
  phones may fall back to needing internet.
- This captures audio the **mic can hear**, not internal app audio directly.
  For direct internal audio capture (no mic in the loop) you'd need
  `MediaProjection` + `AudioPlaybackCapture`, which only works for apps that
  opt in to being captured and needs a fresh user consent prompt each session
  — a reasonable v2 feature if you want it.
