# SecondLife

**On-device, voice-activated emergency medical assistant — fully offline, no internet required.**

Speak your emergency. Get clear, numbered, life-saving steps — read aloud — powered by Gemma 4 E4B running entirely on your Android phone.

---

## Demo

| Metric | Number |
|--------|--------|
| Decode speed | **36 tokens/sec** on-device (GPU) |
| Time to first token | **0.34 s** |
| Full response | **~16 s** end-to-end |
| Model load | **4.2 s** (cached after first run) |
| Battery efficiency | **~17 queries per 1% battery** |
| Network calls | **0 — works in airplane mode** |
| Model size | **3.65 GB** (fits on any modern phone) |

> *"We are not demonstrating that Gemma 4 can run on a device. We are demonstrating what it enables when it does."*

---

## Team

| Name | Role | Email |
|------|------|-------|
| Shravan Athikinasetti | AI Pipeline | sathikinasetti@vt.edu |
| Rohan Magesh | Sensors (Audio + Camera) | mrohan@vt.edu |
| Sid Ravi | Android UI & Data | siddravi@vt.edu |

---

## How It Works

```
Voice → AudioCaptureManager (PCM 16-bit 16kHz)
      → BM25Retriever (on-device protocol lookup)
      → Gemma 4 E4B via LiteRT-LM (on-device inference)
      → StateFlow<SecondLifeResponse>
      → Jetpack Compose UI + TextToSpeech
```

Every query is logged in a SHA-256 hash-chained audit file — tamper-evident and never leaves the device.

---

## Three Modes

| Mode | Who it's for |
|------|-------------|
| **Layperson** | Plain English, numbered steps, no jargon |
| **Paramedic** | Clinical language, drug names, protocol references |
| **Military Medic** | TCCC MARCH order, austere environment |

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| On-device LLM | Gemma 4 E4B via LiteRT-LM |
| Retrieval | FAISS + fastembed (BAAI/bge-small-en-v1.5) |
| Android AI | MediaPipe Tasks GenAI 0.10.35 |
| Android UI | Jetpack Compose + Kotlin |
| Audio | Android AudioRecord (PCM 16-bit 16kHz mono) |
| Camera | CameraX (768×768 RGB) |
| TTS | Android TextToSpeech |
| Audit log | SHA-256 hash-chained JSON |

---

## Setup

### Python pipeline

```bash
pip install -r requirements.txt

# Download model
python -c "
from huggingface_hub import hf_hub_download
hf_hub_download(
    repo_id='litert-community/gemma-4-E4B-it-litert-lm',
    filename='gemma-4-E4B-it.litertlm',
    local_dir='shared/models'
)
"

# Smoke test
python ai-pipeline/inference/test_local.py

# RAG test (3/3 pass)
python ai-pipeline/rag/test_rag.py

# Full integration test (6/6 pass)
python ai-pipeline/test_full_pipeline.py

# Benchmark
python ai-pipeline/inference/benchmark.py
```

### Android app

```bash
# Push model and protocols to device
adb push shared/models/gemma-4-E4B-it-web.task /data/local/tmp/gemma-4-E4B-it-web.task
adb push data/chunks/protocols.json /data/data/com.secondlife/files/protocols.json

# Build and install
cd android-app && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Project Structure

```
SecondLife/
├── ai-pipeline/          Shravan — RAG, Gemma 4, roles, audit, benchmark
├── android-app/          Sid — Compose UI, ViewModel, MediaPipe, TTS
├── vision-audio/         Rohan — AudioRecord, CameraX
├── data/
│   ├── chunks/protocols.json     Pre-chunked clinical protocols (FAISS source)
│   └── test_scenarios/           10 clinical test scenarios
├── shared/
│   ├── contracts.md              Team interface agreements
│   └── models/                   Model download instructions
├── benchmark_results.json        Latest benchmark numbers
└── requirements.txt
```

---

## Known Issues & Fixes

| Issue | Fix |
|-------|-----|
| `DYNAMIC_UPDATE_SLICE` crash | `max_num_tokens` must be ≥ 4096 |
| `ModuleNotFoundError: litert.lm` | Use `import litert_lm` (underscore) |
| Empty response from model | Use `msg['content'][0]['text']` not `msg['text']` |
| Stale citations | Delete `ai-pipeline/rag/index/` and rebuild |
| Model not found on Android | Check both `/data/local/tmp/` and `filesDir` |

---

## License

MIT — see [LICENSE](LICENSE)
