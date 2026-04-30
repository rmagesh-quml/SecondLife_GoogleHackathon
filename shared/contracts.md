# SecondLife Interface Contracts

Single source of truth for data shapes passed between teammates.
**Do not change a contract without notifying the receiving teammate.**

---

## Rohan → Shravan (Audio)

**Source:** `vision-audio/audio/AudioCaptureManager.kt`
**Destination:** `ai-pipeline/inference/session.py`

| Field | Type | Value |
|-------|------|-------|
| `audioData` | `ByteArray` | Raw PCM audio bytes |
| Encoding | PCM 16-bit signed little-endian | — |
| Sample rate | 16 000 Hz | — |
| Channels | Mono (1) | — |
| Delivery | Kotlin `Flow<ByteArray>` | Chunk size TBD by Rohan |

- No compression, no WAV headers — raw PCM only.
- Silence trimming is Rohan's responsibility before delivery.

---

## Rohan → Shravan (Camera)

**Source:** `vision-audio/camera/CameraFrameCapture.kt`
**Destination:** `ai-pipeline/inference/session.py`

| Field | Type | Value |
|-------|------|-------|
| `frame` | `android.graphics.Bitmap` | Captured image |
| Width | 768 px | — |
| Height | 768 px | — |
| Color space | RGB | `ARGB_8888` stripped to RGB |

- Rotation/orientation correction is Rohan's responsibility.

---

## Shravan → Sid (AI Response)

**Source:** `ai-pipeline/inference/session.py`
**Destination:** `android-app/src/main/java/com/secondlife/ui/MainScreen.kt`

```kotlin
data class SecondLifeResponse(
    val response:  String,   // Full answer, ready for display and TTS
    val citation:  String,   // e.g. "sepsis_bundle.pdf, p.4" — "" if no RAG hit
    val latencyMs: Long,     // End-to-end ms from audio receipt to first token
    val role:      String,   // "layperson" | "paramedic" | "military_medic"
    val timestamp: Long = System.currentTimeMillis()
)

// ViewModel exposes:
val response: StateFlow<SecondLifeResponse>
```

- `response` is never null or empty; on error it contains a user-safe message.
- TTS speaks only `response`, never `citation` or `latencyMs`.

---

## Sid → Shravan (Protocol Documents)

**Source:** `data/protocols/`
**Destination:** `ai-pipeline/rag/pipeline.py`

```json
{
  "version": "1.0",
  "documents": [
    {
      "id": "proto_001",
      "title": "Sepsis Bundle Protocol",
      "filename": "sepsis_bundle.pdf",
      "department": "ICU",
      "last_updated": "2025-01-15",
      "tags": ["sepsis", "antibiotics", "critical-care"]
    }
  ]
}
```

---

## Sid → Shravan (Test Scenarios)

**Source:** `data/test_scenarios/test_scenarios.json`
**Destination:** `ai-pipeline/` pytest suite

```json
{
  "version": "1.0",
  "scenarios": [
    {
      "id": "ts_001",
      "role": "Nurse",
      "query": "What is the max dose of vancomycin for a 70kg adult?",
      "expected_citation": "sepsis_bundle.pdf",
      "expected_keywords": ["vancomycin", "15-20 mg/kg", "renal function"]
    }
  ]
}
```
