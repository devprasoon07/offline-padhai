# Offline PadhAI

**Bina internet ke AI tutor — photo kheecho, samajh pao.**

An on-device AI tutor for students. Snap a photo of any textbook question and get a
step-by-step explanation in Hinglish — with zero internet. Built for the
iQOO Hackathon 2026 Grand Finale (solo).

## Features

- **Photo → explanation** — CameraX se photo lo, ML Kit OCR sawal padhta hai,
  Gemma 2B-IT (MediaPipe, fully offline) Hinglish me step-by-step samjhata hai.
- **History** — har explanation auto-save hoti hai (device pe, `history.json`);
  purane Q&A dobara kholo, 200 entries tak.
- **Bookmarks** — star dabao, important jawab save karo; History me filter bhi hai.
- **Quiz mode** — topic likho, AI 5 multiple-choice questions banata hai;
  ek-ek karke jawab do, aakhir me score.
- **Voice input + TTS** — mic dabake sawal bolo (offline recognition),
  speaker dabake jawab suno (Hindi TTS, English fallback).
- **Share** — sawal+jawab text me share karo.
- **Follow-ups** — jawab ke baad aur puchho, context yaad rehta hai.
- **Airplane-mode ready** — sab kuch phone pe, internet ki zaroorat nahi.

---

## Architecture

Everything runs on the phone. No servers, no API calls, no data leaving the device.

```
┌──────────────────────────────────────────────────────────────┐
│                        Offline PadhAI                         │
│                  100% on-device · 0% internet                  │
└──────────────────────────────────────────────────────────────┘
                               │
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
     ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐
     │  CameraX     │  │  ML Kit OCR  │  │  MediaPipe LLM   │
     │              │  │              │  │                  │
     │ · Preview    │  │ · Devanagari │  │ · Gemma 2B-IT    │
     │ · ImageCapture│ │   (primary)  │  │   4-bit (Q4)     │
     │              │  │ · Latin      │  │ · GPU delegate   │
     │              │  │   (fallback) │  │ · CPU fallback   │
     └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘
            │ photo           │ question          │ Hinglish
            │ bitmap          │ text              │ explanation
            │                 │                   │ (streaming)
            └────────┬────────┴────────┬──────────┘
                     ▼                 ▼
            ┌─────────────────────────────────┐
            │           Bottom-sheet UI        │
            │  question card → answer stream   │
            │  → follow-up input → history     │
            └─────────────────────────────────┘
```

### Components

| File | Responsibility |
|---|---|
| `CameraManager.kt` | CameraX lifecycle binding, `PreviewView` feed, `ImageCapture` on a background executor. Captured photo is saved to the app cache dir and handed over as a `File`. |
| `OcrProcessor.kt` | ML Kit Text Recognition. Tries the **Devanagari recognizer first** (Hindi textbooks), falls back to **Latin**. Input bitmap is downscaled to 1600px for speed. Exposes a `suspend fun recognize(bitmap): OcrResult` where `OcrResult` is a sealed class: `Success(text)` / `Empty` / `Error(cause)`. |
| `TutorEngine.kt` | MediaPipe `LlmInference` wrapper. Loads the Gemma 2B-IT Q4 `.bin` from the app's external files dir (side-loaded, never committed). GPU backend preferred, CPU fallback. `explain(question)` streams tokens via `setResultListener { partial, done -> }`; `askFollowUp(question, history)` keeps the last 4 turns as context. A generation guard prevents overlapping requests. |
| `MainActivity.kt` | Orchestrator. Wires CameraManager → OcrProcessor → TutorEngine with coroutines, handles runtime `CAMERA` permission, shows a setup card when the model file is missing, and cleans up (`llmInference.close()`) in `onDestroy`. |
| `res/` | Premium quiet-luxury theme: always-dark charcoal (`#121212`/`#1A1A1A`), off-white text, single warm-amber accent (`#D4A853`) on primary actions only, 16–20dp radii, 1dp hairline dividers, calm Hinglish microcopy. |

### Runtime workflow

```
User taps capture
        │
        ▼
CameraManager.takePhoto() ──▶ photo File (cache dir)
        │
        ▼
Bitmap downscaled to 1600px
        │
        ▼
OcrProcessor.recognize() ──▶ OcrResult
        │                        │
        │ Success(text)          │ Empty / Error
        ▼                        ▼
Question card shows text   Inline guidance
(user can edit it)         ("dobara photo lo")
        │
        ▼  user taps "Samjhao"
TutorEngine.explain(question)
        │
        ▼
System prompt + question ──▶ LlmInference.generateResponseAsync()
        │
        ▼  streaming tokens
Answer TextView appends with fade-in
        │
        ▼
User asks follow-up ──▶ askFollowUp(q, last 4 turns) ──▶ streams again
```

The Hinglish system prompt instructs the model: step-by-step, simple words, one small
example, max ~120 words (keeps mid-range phones responsive).

---

## Building the project

Prerequisites:

- Android Studio Ladybug or newer
- JDK 17
- Android SDK 34 (compileSdk/targetSdk), minSdk 26

```bash
git clone https://github.com/devprasoon07/offline-padhai.git
# Open the folder in Android Studio and let Gradle sync.
# First sync downloads CameraX, ML Kit and MediaPipe native libs — it takes a while.
# Then: Build > Make Project (or ./gradlew assembleDebug if you have the SDK on PATH)
```

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Running it locally

The app needs the Gemma model file on the device (it is ~1.5 GB and intentionally
**not** committed to the repo — see `.gitignore`).

1. **Download the model** — from Kaggle (`google/gemma-2`, accept the license):
   `gemma-2b-it-gpu-int4.bin` (the MediaPipe-compatible GPU int4 variant).
2. **Push it to the device:**
   ```bash
   adb push gemma-2b-it-gpu-int4.bin \
     /sdcard/Android/data/com.devprasoon.offlinepadhai/files/
   ```
   (or drag-and-drop via Android Studio's Device File Explorer into the same folder)
3. **Install the APK** on a real device (`adb install app-debug.apk`). Emulators are not
   recommended — camera and GPU inference are unreliable there.
4. **Grant the camera permission** on first launch. If the model file was missing at
   startup, the in-app setup card appears — place the file, then tap "Phir dekho".
5. **Try it:** point at a textbook question → capture → edit the recognized text if
   needed → "Samjhao" → watch the Hinglish explanation stream in.
6. **Airplane-mode test:** turn on airplane mode and repeat. Everything still works —
   that is the whole point, and the demo judges will see.

---

## Project structure

```
offline-padhai/
├── app/
│   ├── build.gradle.kts            # CameraX, ML Kit, MediaPipe, coroutines
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/devprasoon/offlinepadhai/
│       │   ├── MainActivity.kt     # capture → OCR → tutor orchestration
│       │   ├── CameraManager.kt    # CameraX preview + ImageCapture
│       │   ├── OcrProcessor.kt     # ML Kit Devanagari + Latin OCR
│       │   └── TutorEngine.kt      # MediaPipe Gemma streaming tutor
│       └── res/                    # layouts, premium dark theme, strings
├── build.gradle.kts
├── settings.gradle.kts
├── LICENSE
└── README.md
```

## Tech stack

Android (Kotlin, coroutines) · CameraX 1.3.4 · ML Kit Text Recognition
(Devanagari + Latin) · MediaPipe Tasks GenAI 0.10.14 (`LlmInference`) ·
Gemma 2B-IT Q4 on-device · Material3 dark theme.

## Security

- **No `INTERNET` permission** — the manifest doesn't declare it, so user data
  mathematically cannot leave the device. No servers, no API calls, nothing to breach.
- **Auto-backup disabled** (`allowBackup=false`) — app data never lands in Google Drive backups.
- **Model integrity check** — `TutorEngine` verifies the side-loaded model's SHA-256
  before loading (`EXPECTED_MODEL_SHA256`; set it via `sha256sum` before release —
  a tampered model is refused).
- **R8 minification + obfuscation** in release builds, with conservative keep rules for
  MediaPipe / CameraX / ML Kit (`app/proguard-rules.pro`).
- **No user content in logs** — questions and answers are never logged; only
  error/status lines.
- **No hardcoded secrets** and the model binary is gitignored — nothing sensitive in
  the repo.

## Assumptions

- Streaming uses the stable `setResultListener` + `generateResponseAsync(prompt)` pattern;
  the version-varying `LlmInferenceSession` API was deliberately avoided.
- Only `setModelPath` / `setMaxTokens` / `setPreferredBackend` / `setResultListener`
  are used, to stay compatible across MediaPipe releases.

## License

MIT — see [LICENSE](LICENSE).
