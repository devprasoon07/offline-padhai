# Offline PadhAI 📚

**Bina internet ke AI tutor — photo kheecho, samajh pao.**

iQOO Hackathon 2026 Grand Finale submission (solo — Dev Prasoon).

## Concept
Student textbook ke question ki photo kheenchte hai → phone pe chal raha on-device AI use Hinglish me step-by-step samjhata hai. 100% offline — hostel, metro, gaon, jahan network fail.

## Tech stack (all on-device)
| Layer | Tech |
|---|---|
| Photo capture | CameraX |
| Text extraction (OCR) | ML Kit Text Recognition (Devanagari + Latin) |
| AI tutor | Gemma 2B-IT 4-bit via MediaPipe LLM Inference API (phone GPU) |
| Persona | Hinglish step-by-step tutor system prompt |

## Demo flow
1. Question ki photo lo
2. OCR text nikalo (on-screen dikhao)
3. "Samjhao" → streaming Hinglish explanation
4. Follow-up puchho ("aur easy batao")
5. **Airplane mode me live demo** ✈️

## 48-hour build plan
- 0–8h: Android Studio setup, CameraX photo capture
- 8–16h: ML Kit OCR integration
- 16–28h: MediaPipe + Gemma integration (biggest risk)
- 28–36h: Hinglish tutor prompt tuning + UI polish
- 36–44h: Airplane-mode testing on real textbook photos
- 44–48h: Pitch + deck + demo rehearsal

## Notes
- Gemma 2B-IT Q4 model (~1.5GB) — finale se pehle download karke side-load karna
- Jawab chhote rakhne ke liye prompt design (mid-range phones pe speed)
- Backup: hybrid fallback ready, par pitch hamesha offline-first
