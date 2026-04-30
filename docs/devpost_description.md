# SecondLife — Devpost Submission

## Inspiration

Every second counts in an emergency. But in the critical minutes before paramedics arrive, most people freeze — not from cowardice, but from not knowing what to do. We built SecondLife because we believe the knowledge to save a life should be in every pocket, even when there's no signal.

## What it does

SecondLife is a voice-activated emergency medical assistant that runs entirely on your Android phone — no internet required, no cloud, no latency from a round trip to a server.

Speak your emergency out loud. SecondLife hears you, retrieves the relevant clinical protocol from an on-device database, and generates a clear, numbered, role-appropriate response through an on-device Gemma 4 language model. The answer is read aloud through text-to-speech so you never have to look away from the patient.

Three modes adapt the response to who is asking:
- **Layperson** — plain English, numbered steps, no jargon
- **Paramedic** — clinical language, drug dosages, protocol references
- **Military Medic** — TCCC MARCH protocol, austere environment assumptions

Every query is logged in a SHA-256 hash-chained audit file on the device — tamper-evident, privacy-preserving, and never leaves the device.

## How we built it

**AI pipeline (Python / LiteRT-LM):** We run Gemma 4 E4B via Google's LiteRT-LM runtime directly on the device CPU/GPU. A FAISS vector index of clinical protocol documents enables retrieval-augmented generation — the model answers from grounded protocol text, not hallucination. Embeddings use BAAI/bge-small-en-v1.5 via fastembed, a 33 MB ONNX model with zero internet dependency at runtime.

**Android app (Kotlin / Jetpack Compose):** The UI is built in Compose with a single mic FAB. AudioCaptureManager streams PCM 16-bit 16kHz mono audio from the microphone. MediaPipe Tasks GenAI (0.10.35) runs the model. BM25 token-overlap retrieval provides fast on-device chunk lookup. Results flow through a ViewModel StateFlow into the UI and TTS engine.

**Performance:** On a GPU-enabled device we measured 36 tokens/second decode speed, 0.34 s time to first token, and a full response in approximately 16 seconds. Battery efficiency: approximately 17 queries per 1% battery on a Samsung S25 Ultra. Zero network calls.

## Challenges we ran into

Getting LiteRT-LM to run stably required discovering that `max_num_tokens` must be set to 4096 minimum — values below this cause a `DYNAMIC_UPDATE_SLICE` crash with no clear error message. We also found that fastembed was the only embedding library compatible with protobuf 6.x, after sentence-transformers caused silent dependency conflicts.

## Accomplishments that we're proud of

A fully offline medical AI that passes all integration tests, produces role-differentiated clinical responses, and runs on hardware anyone already owns — with a hash-chained audit log ensuring every query is accountable.

## What we learned

On-device LLMs are no longer a research toy. The gap between cloud-quality responses and on-device responses has essentially closed for structured, grounded tasks. The bottleneck is now UX, not capability.

## What's next

Push-to-talk wired to the real AudioCaptureManager, CameraX integration for wound assessment from a photo, and a compressed E2B model variant for older devices. We also want to publish the clinical protocol dataset and audit log format as open standards for offline medical AI.
