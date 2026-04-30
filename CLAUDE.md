# SecondLife — Claude Code Project Context

## Project

**SecondLife** is an on-device, voice-activated emergency medical assistant built for a hackathon.
A user speaks a question or describes an emergency. SecondLife:

1. Captures audio via `AudioCaptureManager` (PCM 16-bit 16kHz mono).
2. Optionally captures a camera frame via `CameraFrameCapture` (768×768 RGB Bitmap).
3. Runs a RAG pipeline against a local FAISS index of clinical protocol PDFs.
4. Sends the query + retrieved context to an on-device Gemma 4 E4B model via LiteRT-LM.
5. Emits the result as `StateFlow<SecondLifeResponse>` to the Android UI.
6. Speaks the response aloud via Android TTS.

**Pipeline flow:** voice → AudioCaptureManager → InferenceSession → StateFlow → TTS

Everything runs **fully offline on-device** — no internet required, no cloud API calls.

---

## Team

| Person | Role | Module |
|--------|------|--------|
| Shravan | AI Pipeline Lead | `ai-pipeline/` |
| Rohan | Sensors Lead | `vision-audio/` |
| Sid | Android UI & Data Lead | `android-app/`, `data/` |

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| On-device LLM | Gemma 4 E4B via LiteRT-LM |
| Vector store | FAISS (`faiss-cpu`) |
| Embeddings | fastembed (BAAI/bge-small-en-v1.5) |
| Document parsing | PyPDF2 |
| Image handling | Pillow |
| Android UI | Jetpack Compose + Kotlin |
| Audio capture | Android AudioRecord API |
| Camera | CameraX |
| TTS | Android TextToSpeech |
| Testing | pytest |

---

## Interface Contracts (see `shared/contracts.md` for full detail)

**Rohan → Shravan**
- `ByteArray` — PCM 16-bit signed LE, 16 000 Hz, mono
- `android.graphics.Bitmap` — 768×768 px, RGB

**Shravan → Sid**
```kotlin
data class SecondLifeResponse(
    val response:  String,   // answer text for display + TTS
    val citation:  String,   // e.g. "sepsis_bundle.pdf, p.4"
    val latencyMs: Long,     // end-to-end ms
    val role:      String    // "layperson" | "paramedic" | "military_medic"
)
// Delivered as: StateFlow<SecondLifeResponse>
```

---

## Critical Constraints

1. **Fully offline** — no HTTP calls anywhere in `ai-pipeline/`.
2. **Model files are gitignored** — never commit `*.litertlm`, `*.task`, `*.tflite`.
3. **Audit log is gitignored** — `audit_log.json` may contain patient data.
4. **Latency target** — end-to-end ≤ 3 s on a mid-range Android device.
5. **Citations are mandatory** — every response must include a citation string (`""` if none, never `None`).
6. **max_num_tokens=4096 minimum** — lower values cause a DYNAMIC_UPDATE_SLICE crash in LiteRT-LM.
