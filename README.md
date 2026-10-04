# Offline PadhAI

Bina internet ke AI tutor — photo kheecho, samajh pao.

iQOO Hackathon 2026 Grand Finale · solo — Dev Prasoon

Student textbook ke question ki photo leta hai. Phone pe chal raha on-device AI use Hinglish me step-by-step samjhata hai. 100% offline — hostel, metro, gaon, jahan network fail, wahan bhi kaam karega.

## How it works

```
Camera (CameraX) → Photo
    → ML Kit OCR (Devanagari, fallback Latin) → Sawal ka text
    → Gemma 2B-IT via MediaPipe LLM Inference API (phone GPU)
    → Hinglish step-by-step jawab, streaming
    → Follow-up: pichli baatcheet yaad rehti hai
```

Koi server nahi, koi API key nahi, koi data phone se bahar nahi jata.

## Tech stack

| Layer | Tech |
|---|---|
| Language / UI | Kotlin, Material3 (custom dark theme) |
| Camera | CameraX 1.3.4 (Preview + ImageCapture) |
| OCR | ML Kit Text Recognition — Devanagari + Latin, on-device |
| LLM | MediaPipe tasks-genai 0.10.14 — `LlmInference`, GPU backend (CPU fallback) |
| Model | Gemma 2B-IT, 4-bit quantized (~1.5 GB, side-loaded) |
| Async | Kotlin coroutines, `lifecycleScope` |

## Setup

1. Android Studio (Hedgehog ya newer) me project kholo.
2. Gradle sync hone do. Pehla sync thoda waqt lega (MediaPipe native libs).
3. Neeche diye steps se model file phone me dalo.
4. App chalao — pehli screen pe camera permission maangegi.

Requirements: Android 8.0 (API 26)+, camera wala device. Emulator pe camera aur GPU inference dono slow/unstable hote hain — real device behtar hai.

## Model download

Model repo me nahi hai (`.gitignore` me `*.bin` / `*.task` blocked hai). Ek baar download karo:

1. Kaggle par `google/gemma-2` model page kholo (login + license accept zaroori).
2. Model variations me se MediaPipe wala chuno: `gemma-2b-it-gpu-int4.bin` (~1.5 GB).
   - Agar GPU variant device pe na chale to `gemma-2b-it-cpu-int8.bin` le lo — app me CPU fallback waise bhi hai.
3. File ko app ke external files folder me rakho:
   - Android Studio: View → Tool Windows → Device File Explorer → `/sdcard/Android/data/com.devprasoon.offlinepadhai/files/` me upload karo, naam bilkul `gemma-2b-it-gpu-int4.bin` rakho.
   - Ya adb se: `adb push gemma-2b-it-gpu-int4.bin /sdcard/Android/data/com.devprasoon.offlinepadhai/files/`
4. App me "Phir dekho" dabao — model load ho jayega.

Pehla load 10–20 second le sakta hai. Uske baad jawab streaming me aate hain.

## Demo script (judges ke saamne)

1. Phone ko **airplane mode** me dalo — yehi sabse bada proof hai.
2. Textbook ke question ki photo lo.
3. OCR text screen pe dikhega — galat ho to haath se sudhaar lo.
4. "Samjhao" dabao — Hinglish step-by-step jawab streaming me aayega.
5. Follow-up puchho ("aur easy batao") — model ko context yaad rahega.

## Design

Quiet luxury, restrained: dark-first charcoal surfaces (`#121212` / `#1A1A1A`), soft off-white text, ek hi warm amber accent (`#D4A853`) sirf primary actions pe. 16–20dp radii, 1dp hairline dividers, 150–250ms fade/slide micro-animations. Calm Hinglish microcopy.

## API assumptions

- Streaming: `LlmInferenceOptions.setResultListener { partialResult, done -> }` + `generateResponseAsync(prompt)` — MediaPipe ke Android sample wala stable pattern.
- Partial results version ke hisaab se cumulative ya delta ho sakte hain — `TutorEngine` dono ko adaptively handle karta hai.
- `LlmInferenceSession` wala conversation API version-specific hai, isliye follow-up context prompt me history jod ke diya jata hai (aakhri 4 turns).
- Ye code is VM pe compile nahi hua (Android SDK nahi hai) — Android Studio me sync + build zaroor verify karo.
