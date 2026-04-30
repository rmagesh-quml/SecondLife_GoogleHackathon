"""
ai-pipeline/rag/pipeline.py

FAISS-backed RAG pipeline for SecondLife.

Public API:
    build_index(protocols_path)      → builds and saves FAISS index
    retrieve(query, top_k)           → list[str]  (chunk texts)
    retrieve_with_metadata(query, k) → list[{text, source, page, score}]
    build_rag_prompt(query, chunks)  → str

Index artifacts saved to ai-pipeline/rag/index/:
    protocols.faiss   — FAISS IndexFlatL2
    chunks.json       — ordered list of chunk dicts
    metadata.json     — build metadata (model, dim, count, timestamp)
"""

from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Optional

import faiss
import numpy as np
from fastembed import TextEmbedding

# ── Paths ──────────────────────────────────────────────────────────────────────
REPO_ROOT = Path(__file__).resolve().parent.parent.parent
PROTOCOLS_PATH = REPO_ROOT / "data" / "chunks" / "protocols.json"
FAISS_INDEX_PATH = Path(__file__).resolve().parent / "index" / "protocols.faiss"
_INDEX_DIR = FAISS_INDEX_PATH.parent
_CHUNKS_PATH = _INDEX_DIR / "chunks.json"
_META_PATH = _INDEX_DIR / "metadata.json"

EMBED_MODEL = "BAAI/bge-small-en-v1.5"
EMBED_DIM = 384

# ── Mock fallback chunks ───────────────────────────────────────────────────────
_MOCK_CHUNKS: list[dict] = [
    {
        "id": "mock_001", "source": "first_aid_cpr.pdf", "page": 1,
        "text": (
            "CPR for adults: If the person is unresponsive and not breathing normally, "
            "call emergency services immediately. Place heel of hand on centre of chest, "
            "interlock fingers and press down 5-6 cm at 100-120 compressions per minute. "
            "Give 2 rescue breaths after every 30 compressions. Continue until help arrives. "
            "Use an AED as soon as available — turn it on and follow voice prompts."
        ),
    },
    {
        "id": "mock_002", "source": "first_aid_choking_adult.pdf", "page": 1,
        "text": (
            "Choking in adults: If the adult is coughing, encourage them to keep coughing. "
            "If they cannot cough, speak, or breathe, stand behind them and give 5 sharp back blows "
            "between shoulder blades with heel of hand. If back blows fail, perform abdominal thrusts: "
            "make a fist above the navel, grasp with other hand, give 5 quick inward-upward thrusts. "
            "Alternate 5 back blows and 5 abdominal thrusts until object expelled or person collapses. "
            "If they become unconscious start CPR."
        ),
    },
    {
        "id": "mock_003", "source": "first_aid_choking_child.pdf", "page": 1,
        "text": (
            "Choking in children (1 year to puberty): Encourage coughing if child can cough. "
            "If unable to breathe or cough, kneel or stand behind child, give 5 back blows between "
            "shoulder blades. Then perform abdominal thrusts with fist above navel, smaller force than adult. "
            "Alternate 5 back blows and 5 abdominal thrusts. For infants under 1 year: hold face-down on forearm, "
            "give 5 back blows then 5 chest thrusts — do NOT perform abdominal thrusts on infants. "
            "If child loses consciousness begin CPR and call emergency services."
        ),
    },
    {
        "id": "mock_004", "source": "first_aid_bleeding_limb.pdf", "page": 1,
        "text": (
            "Severe bleeding from a limb (arm or leg): Apply direct pressure with a clean cloth or bandage. "
            "Press firmly and do not remove cloth if soaked — add more on top. "
            "If limb bleeding is life-threatening and direct pressure fails, apply a tourniquet 5-7 cm "
            "above the wound. Tighten until bleeding stops. Note the time the tourniquet was applied. "
            "Do not remove a tourniquet once applied — leave for emergency services. Elevate the limb "
            "above heart level if possible while maintaining pressure."
        ),
    },
    {
        "id": "mock_005", "source": "first_aid_bleeding_torso.pdf", "page": 1,
        "text": (
            "Severe bleeding from torso or chest: Do NOT apply a tourniquet to the torso. "
            "Pack the wound tightly with clean cloth and apply firm continuous pressure. "
            "If an object is impaled, do not remove it — pack around it and stabilise. "
            "For chest wounds: if air is sucked into wound on breathing, cover with a gloved hand "
            "or chest seal. Keep the person still, monitor for shock (pale, cold, rapid breathing). "
            "Call emergency services immediately — torso bleeding is life-threatening."
        ),
    },
    {
        "id": "mock_006", "source": "first_aid_stroke.pdf", "page": 1,
        "text": (
            "Stroke recognition and response using FAST: "
            "Face — ask person to smile, check for drooping on one side. "
            "Arms — ask person to raise both arms, check if one drifts down. "
            "Speech — ask person to repeat a simple phrase, check for slurred or strange speech. "
            "Time — if any FAST sign is present, call emergency services immediately. "
            "Note the exact time symptoms started. Do not give food or water. "
            "If unconscious, place in recovery position. Time to treatment is critical — "
            "every minute without treatment costs brain cells."
        ),
    },
    {
        "id": "mock_007", "source": "first_aid_seizure.pdf", "page": 1,
        "text": (
            "Seizure (convulsion) first aid: Protect the person from injury — clear hard objects away, "
            "cushion the head. Do NOT restrain the person, do NOT put anything in their mouth. "
            "Time the seizure. After convulsions stop, place in recovery position on their side "
            "to keep airway clear. Stay with them until fully conscious — they may be confused. "
            "Call emergency services if: seizure lasts more than 5 minutes, person doesn't recover, "
            "second seizure follows, person is pregnant, injured, or has no known seizure history."
        ),
    },
    {
        "id": "mock_008", "source": "first_aid_burns.pdf", "page": 1,
        "text": (
            "Burns first aid: Cool the burn under cool (not ice cold) running water for 20 minutes. "
            "Remove jewellery and clothing near the burn unless stuck to skin. "
            "Cover with a clean non-fluffy material such as cling film or a clean plastic bag. "
            "Do NOT use butter, toothpaste, or ice. Do NOT burst blisters. "
            "Seek medical attention for: burns larger than the casualty's hand, burns on face/hands/feet/"
            "genitals/joints, deep burns, chemical or electrical burns, any burn in a child under 5 or "
            "adult over 60. Treat for shock if needed — keep warm and calm."
        ),
    },
    {
        "id": "mock_009", "source": "first_aid_anaphylaxis.pdf", "page": 1,
        "text": (
            "Anaphylaxis (severe allergic reaction): Signs include swollen face/throat, difficulty "
            "breathing, wheezing, hives, rapid weak pulse, dizziness, collapse. "
            "Use an adrenaline auto-injector (EpiPen) if available — inject into outer mid-thigh, "
            "hold for 10 seconds. Call emergency services immediately even after EpiPen. "
            "Lay person flat with legs raised unless breathing is difficult — then sit them up. "
            "A second dose of adrenaline can be given after 5 minutes if no improvement. "
            "Do NOT give antihistamines as a substitute for adrenaline in anaphylaxis."
        ),
    },
    {
        "id": "mock_010", "source": "first_aid_head_injury.pdf", "page": 1,
        "text": (
            "Head injury first aid: For minor bumps apply a cold compress for up to 20 minutes. "
            "Watch for concussion signs: headache, dizziness, confusion, nausea, memory loss, "
            "sensitivity to light or noise. Do NOT return to sport or strenuous activity same day. "
            "Call emergency services for: loss of consciousness, seizure, repeated vomiting, "
            "clear fluid from nose or ears, unequal pupils, weakness in limbs, severe headache. "
            "If unconscious but breathing, place in recovery position. If not breathing, start CPR. "
            "Assume possible spinal injury with any serious head injury — minimise movement."
        ),
    },
]


# ── Embedding helper ───────────────────────────────────────────────────────────

_embedder: Optional[TextEmbedding] = None


def _get_embedder() -> TextEmbedding:
    global _embedder
    if _embedder is None:
        _embedder = TextEmbedding(model_name=EMBED_MODEL)
    return _embedder


def _embed(texts: list[str]) -> np.ndarray:
    vecs = list(_get_embedder().embed(texts))
    return np.array(vecs, dtype=np.float32)


# ── Chunk loading ──────────────────────────────────────────────────────────────

def _load_chunks(protocols_path: str) -> list[dict]:
    p = Path(protocols_path)

    if p.is_dir():
        return _chunks_from_pdfs(p)

    if p.exists():
        with open(p) as f:
            data = json.load(f)
        # Accept both {"chunks":[...]} wrapper and bare array
        chunks = data.get("chunks", data) if isinstance(data, dict) else data
        if chunks:
            return chunks

    # Fallback: auto-generate mock first-aid chunks
    print("[INFO] protocols.json not found — using built-in mock first-aid chunks")
    return _MOCK_CHUNKS


def _chunks_from_pdfs(directory: Path) -> list[dict]:
    try:
        from PyPDF2 import PdfReader
    except ImportError:
        raise ImportError("PyPDF2 required for PDF ingestion: pip install PyPDF2")

    chunks = []
    chunk_id = 0
    for pdf_path in sorted(directory.glob("*.pdf")):
        reader = PdfReader(str(pdf_path))
        for page_num, page in enumerate(reader.pages):
            text = (page.extract_text() or "").strip()
            if not text:
                continue
            words = text.split()
            step = 448  # 512 - 64 overlap
            for i in range(0, max(1, len(words) - 64), step):
                window = words[i: i + 512]
                if len(window) < 20:
                    continue
                chunks.append({
                    "id": f"chunk_{chunk_id:04d}",
                    "source": pdf_path.name,
                    "page": page_num + 1,
                    "text": " ".join(window),
                })
                chunk_id += 1
    return chunks


# ── Build index ────────────────────────────────────────────────────────────────

def build_index(protocols_path: str = str(PROTOCOLS_PATH)) -> None:
    """
    Build a FAISS IndexFlatL2 from protocols_path and save to FAISS_INDEX_PATH.

    protocols_path can be:
      - a JSON file: array of {id, source, page, text} OR {"chunks":[...]} wrapper
      - a directory: extracts text from all *.pdf files within it
      - missing: auto-generates 10 mock first-aid chunks
    """
    chunks = _load_chunks(protocols_path)
    if not chunks:
        raise RuntimeError("No chunks found — check protocols_path")

    print(f"[INFO] Embedding {len(chunks)} chunks with {EMBED_MODEL}...")
    texts = [c["text"] for c in chunks]
    embeddings = _embed(texts)

    index = faiss.IndexFlatL2(EMBED_DIM)
    index.add(embeddings)

    _INDEX_DIR.mkdir(parents=True, exist_ok=True)
    faiss.write_index(index, str(FAISS_INDEX_PATH))

    with open(_CHUNKS_PATH, "w") as f:
        json.dump(chunks, f, indent=2)

    with open(_META_PATH, "w") as f:
        json.dump({
            "model": EMBED_MODEL,
            "dim": EMBED_DIM,
            "chunk_count": len(chunks),
            "built_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }, f, indent=2)

    print(f"[INFO] Index built: {len(chunks)} chunks → {FAISS_INDEX_PATH}")


# ── Load index (lazy) ──────────────────────────────────────────────────────────

_index: Optional[faiss.Index] = None
_chunks: Optional[list[dict]] = None


def _ensure_loaded() -> tuple[faiss.Index, list[dict]]:
    global _index, _chunks
    if _index is None:
        if not FAISS_INDEX_PATH.exists():
            print("[INFO] Index not found — building now...")
            build_index()
        _index = faiss.read_index(str(FAISS_INDEX_PATH))
        with open(_CHUNKS_PATH) as f:
            _chunks = json.load(f)
    return _index, _chunks


# ── Retrieval ──────────────────────────────────────────────────────────────────

def retrieve(query: str, top_k: int = 3) -> list[str]:
    """Return top_k chunk texts for the query."""
    results = retrieve_with_metadata(query, top_k)
    return [r["text"] for r in results]


def retrieve_with_metadata(query: str, top_k: int = 3) -> list[dict]:
    """Return list of {text, source, page, score} dicts."""
    index, chunks = _ensure_loaded()
    q_emb = _embed([query])
    distances, indices = index.search(q_emb, min(top_k, len(chunks)))

    results = []
    for dist, idx in zip(distances[0], indices[0]):
        if idx < 0 or idx >= len(chunks):
            continue
        c = chunks[idx]
        results.append({
            "text": c["text"],
            "source": c.get("source", "unknown"),
            "page": c.get("page", 0),
            "score": float(dist),
        })
    return results


# ── Prompt builder ─────────────────────────────────────────────────────────────

def build_rag_prompt(query: str, chunks: list[str]) -> str:
    """Format retrieved context + query into a prompt string for the LLM."""
    if not chunks:
        return (
            "You are an emergency first-aid assistant. "
            "No protocol context was retrieved — answer from general knowledge.\n\n"
            f"Question: {query}"
        )

    context_blocks = "\n\n".join(
        f"[Context {i + 1}]\n{text}" for i, text in enumerate(chunks)
    )
    return (
        "You are an emergency first-aid assistant. "
        "Use the protocol context below to answer the question.\n\n"
        f"{context_blocks}\n\n"
        f"Question: {query}\n"
        "Answer with clear, numbered steps."
    )
