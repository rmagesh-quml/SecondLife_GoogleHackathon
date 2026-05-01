"""
ai-pipeline/inference/session.py

LiteRT-LM inference session for SecondLife.
Supports panic mode (compact JSON, <120 tokens) and detail mode (full numbered steps).

  result = InferenceSession().run(query, role, mode="panic")
  # → SecondLifeResponse(response, citation, latency_ms, role, mode, steps, follow_up)
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

os.environ.setdefault("GLOG_minloglevel", "3")
os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "3")

import litert_lm

litert_lm.set_min_log_severity(litert_lm.LogSeverity.ERROR)

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
MODEL_E4B = REPO_ROOT / "shared" / "models" / "gemma-4-E4B-it.litertlm"
MODEL_E2B = REPO_ROOT / "shared" / "models" / "gemma-4-E2B-it.litertlm"

# Cached protocol chunks — skip FAISS for the 9 most common emergencies
PROTOCOL_CHUNK_CACHE: dict[str, str] = {
    "seizure": (
        "Seizure: Clear area of hazards. Do not restrain. Do not put anything in mouth. "
        "Turn onto side (recovery position) if possible. Time the seizure. "
        "Call EMS if >5 minutes, second seizure, or patient injured/pregnant."
    ),
    "choking_adult": (
        "Adult choking: If coughing forcefully — let them cough. If silent/unable to breathe: "
        "lean forward, 5 back blows (heel of hand between shoulder blades), "
        "then 5 abdominal thrusts (Heimlich). Alternate until clear. If unconscious: CPR."
    ),
    "choking_infant": (
        "Infant choking (<1yr): Face-down on forearm, head lower. 5 back blows. "
        "Flip face-up, 5 chest thrusts (2 fingers). Look in mouth — remove only if visible. "
        "Repeat. If unconscious: infant CPR."
    ),
    "severe_bleeding": (
        "Severe bleeding: Firm direct pressure immediately. Do not remove cloth — add more if soaked. "
        "Elevate above heart. Maintain continuous pressure. "
        "Tourniquet above wound for limb bleeding that won't stop."
    ),
    "cpr": (
        "CPR: 30 compressions (100-120/min, 5-6cm depth, full recoil) + 2 rescue breaths. "
        "Push hard and fast on lower sternum. AED as soon as available."
    ),
    "burn": (
        "Burns: Cool running water for 20 min — not ice. Remove jewelry/clothing unless stuck. "
        "Cover loosely with cling film. Do not burst blisters. "
        "Large/facial/airway burns: immediate EMS."
    ),
    "fracture": (
        "Fracture: Immobilize — do not straighten. Support above and below injury. "
        "Ice wrapped in cloth. Check CMS (circulation, movement, sensation). "
        "No weight-bearing. Open fracture: cover with clean dressing."
    ),
    "allergic_reaction": (
        "Anaphylaxis: EpiPen outer mid-thigh now. Lay flat, legs raised (unless breathing difficulty). "
        "Call EMS. Second EpiPen after 5-15 min if no improvement. "
        "Antihistamines are NOT first-line — epinephrine is."
    ),
    "poisoning": (
        "Poisoning: Do NOT induce vomiting unless Poison Control says so. "
        "Note: substance, quantity, time, age/weight. Call Poison Control (1-800-222-1222). "
        "Keep awake, monitor breathing. Recovery position if unconscious but breathing."
    ),
}

# Keyword → protocol_id lookup for cache hits
_KEYWORD_MAP: dict[str, str] = {
    "seizure": "seizure", "seizing": "seizure", "convuls": "seizure",
    "choking": "choking_adult", "heimlich": "choking_adult",
    "infant chok": "choking_infant", "baby chok": "choking_infant",
    "bleeding": "severe_bleeding", "hemorrhage": "severe_bleeding",
    "not breathing": "cpr", "cardiac arrest": "cpr", "no pulse": "cpr",
    "burn": "burn", "burned": "burn", "scalded": "burn",
    "fracture": "fracture", "broken bone": "fracture",
    "allergic": "allergic_reaction", "anaphylax": "allergic_reaction",
    "poison": "poisoning", "overdose": "poisoning",
}


@dataclass
class SecondLifeResponse:
    response: str
    citation: str
    latency_ms: int
    role: str
    mode: str = "panic"
    steps: list[str] = field(default_factory=list)
    follow_up: Optional[str] = None


def _resolve_model() -> Path:
    if MODEL_E4B.exists():
        return MODEL_E4B
    if MODEL_E2B.exists():
        return MODEL_E2B
    raise FileNotFoundError(
        f"No model found. Expected {MODEL_E4B} or {MODEL_E2B}. "
        "See shared/models/README.md for download instructions."
    )


def _classify_query(query: str) -> Optional[str]:
    """Returns a protocol_id from the cache if the query matches a known keyword."""
    lower = query.lower()
    for keyword, protocol_id in _KEYWORD_MAP.items():
        if keyword in lower:
            return protocol_id
    return None


def _parse_json_response(raw: str) -> tuple[str, list[str], Optional[str]]:
    """Extracts speak/steps/ask from Gemma's JSON output. Falls back to raw text on failure."""
    try:
        # Strip markdown code fences if present
        text = re.sub(r"```(?:json)?", "", raw).strip()
        # Find the first JSON object
        match = re.search(r"\{.*\}", text, re.DOTALL)
        if not match:
            return raw, [], None
        obj = json.loads(match.group())
        speak = obj.get("speak", "").strip() or raw
        steps = [str(s) for s in obj.get("steps", [])]
        ask = obj.get("ask", "").strip() or None
        return speak, steps, ask
    except Exception:
        return raw, [], None


def _summarize_context(query: str, chunks: list[str]) -> str:
    """Returns a compact context string for inclusion in prompts (target <300 chars)."""
    if not chunks:
        return ""
    combined = " ".join(chunks[0].split()[:80])  # ~300 chars worth
    return combined


class InferenceSession:
    """
    LiteRT-LM session with panic/detail mode support and protocol chunk cache.
    Engine is loaded once and reused across calls.
    """

    def __init__(self) -> None:
        from ai_pipeline.rag.pipeline import RagPipeline
        from ai_pipeline.function_calling.roles import build_prompt, build_panic_prompt

        self._rag = RagPipeline()
        self._build_prompt = build_prompt
        self._build_panic_prompt = build_panic_prompt
        self._engine: Optional[litert_lm.Engine] = None
        self._model_path = _resolve_model()

    def _get_engine(self) -> litert_lm.Engine:
        if self._engine is None:
            self._engine = litert_lm.Engine(
                model_path=str(self._model_path),
                backend=litert_lm.Backend.CPU,
                max_num_tokens=4096,
            )
        return self._engine

    def run(
        self,
        query: str,
        role: str = "layperson",
        mode: str = "panic",
        *,
        audio_bytes: Optional[bytes] = None,
        frame_bitmap=None,
    ) -> SecondLifeResponse:
        t0 = time.perf_counter()

        # Check protocol cache first — skip FAISS for known emergencies
        protocol_id = _classify_query(query)
        if protocol_id and protocol_id in PROTOCOL_CHUNK_CACHE:
            chunks = [PROTOCOL_CHUNK_CACHE[protocol_id]]
            citation = ""
        else:
            rag_hits = self._rag.retrieve(query)
            formatted = self._rag.format_context(rag_hits)
            # format_context may return (context_list, citation) or just a string depending on version
            if isinstance(formatted, tuple):
                chunks, citation = formatted
            else:
                chunks = [formatted] if formatted else []
                citation = rag_hits[0]["source"] if rag_hits else ""

        # Build mode-appropriate prompt
        if mode == "panic":
            system_msg, user_msg = self._build_panic_prompt(query, chunks, role)
        else:
            system_msg, user_msg = self._build_prompt(query, chunks, role)

        engine = self._get_engine()
        response_text = ""
        with engine.create_conversation(messages=[system_msg]) as conv:
            for msg in conv.send_message_async(user_msg):
                if isinstance(msg, dict):
                    for item in msg.get("content", []):
                        if item.get("type") == "text":
                            response_text += item.get("text", "")
                elif isinstance(msg, str):
                    response_text += msg

        latency_ms = int((time.perf_counter() - t0) * 1000)

        if mode == "panic":
            speak, steps, follow_up = _parse_json_response(response_text)
        else:
            speak, steps, follow_up = response_text or "[No response generated]", [], None

        return SecondLifeResponse(
            response=speak,
            citation=citation,
            latency_ms=latency_ms,
            role=role,
            mode=mode,
            steps=steps,
            follow_up=follow_up,
        )

    def close(self) -> None:
        if self._engine is not None:
            self._engine.__exit__(None, None, None)
            self._engine = None
