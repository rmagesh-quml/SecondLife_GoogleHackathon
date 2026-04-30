"""
ai-pipeline/inference/session.py

LiteRT-LM inference session for SecondLife.

Wraps litert_lm.Engine + RagPipeline + roles into a single callable:
  result = InferenceSession().run(query, role, audio_bytes, frame_bitmap)
  # → SecondLifeResponse(response, citation, latencyMs, role)
"""

from __future__ import annotations

import os
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

os.environ.setdefault("GLOG_minloglevel", "3")
os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "3")

import litert_lm

litert_lm.set_min_log_severity(litert_lm.LogSeverity.ERROR)

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
MODEL_E4B = REPO_ROOT / "shared" / "models" / "gemma-4-E4B-it.litertlm"
MODEL_E2B = REPO_ROOT / "shared" / "models" / "gemma-4-E2B-it.litertlm"


@dataclass
class SecondLifeResponse:
    response: str
    citation: str
    latency_ms: int
    role: str


def _resolve_model() -> Path:
    if MODEL_E4B.exists():
        return MODEL_E4B
    if MODEL_E2B.exists():
        return MODEL_E2B
    raise FileNotFoundError(
        f"No model found. Expected {MODEL_E4B} or {MODEL_E2B}. "
        "See shared/models/README.md for download instructions."
    )


class InferenceSession:
    """
    Lazy-loading LiteRT-LM session.
    The Engine is created on the first call to run() and reused for subsequent calls.
    """

    def __init__(self) -> None:
        from ai_pipeline.rag.pipeline import RagPipeline
        from ai_pipeline.function_calling.roles import Role, build_prompt

        self._rag = RagPipeline()
        self._build_prompt = build_prompt
        self._Role = Role
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
        *,
        audio_bytes: Optional[bytes] = None,
        frame_bitmap=None,
    ) -> SecondLifeResponse:
        t0 = time.perf_counter()

        chunks = self._rag.retrieve(query)
        context, citation = self._rag.format_context(chunks)
        system_msg, user_msg = self._build_prompt(query, context, role)

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

        return SecondLifeResponse(
            response=response_text or "[No response generated]",
            citation=citation,
            latency_ms=latency_ms,
            role=role,
        )

    def close(self) -> None:
        if self._engine is not None:
            self._engine.__exit__(None, None, None)
            self._engine = None
