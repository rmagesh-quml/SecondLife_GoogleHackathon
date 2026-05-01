"""
ai-pipeline/inference/secondlife_pipeline.py

Full SecondLife inference pipeline: router → protocol cache → role prompt → Gemma 4 → audit log.

Optimized flow:
  - Keyword router bypasses FAISS for 9 common protocols
  - Panic mode: compact JSON prompt, ~60-120 token output
  - Detail mode: full context + numbered steps
  - Context summarization keeps prompts under 4K tokens

Usage:
    with SecondLifePipeline(model_path, protocols_path) as pipeline:
        result = pipeline.respond("child choking", role="layperson", mode="panic")
        print(result["response"])
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
from pathlib import Path
from typing import Optional

os.environ.setdefault("GLOG_minloglevel", "3")
os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "3")

import litert_lm
litert_lm.set_min_log_severity(litert_lm.LogSeverity.ERROR)

_REPO_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(_REPO_ROOT / "ai-pipeline"))

from rag.pipeline import retrieve_with_metadata, build_index, FAISS_INDEX_PATH
from function_calling.roles import get_system_prompt, build_prompt, build_panic_prompt
from audit_log.logger import AuditLog
from inference.session import PROTOCOL_CHUNK_CACHE, _classify_query, _parse_json_response

# Samsung S25 Ultra battery constants
_BATTERY_MAH = 5000
_BATTERY_V = 3.85
_NPU_TDP_W = 1.8


def _energy_mah(latency_s: float) -> float:
    return _NPU_TDP_W * latency_s / 3.6 / _BATTERY_V


def _compact_context(chunks: list[str], max_chars: int = 800) -> list[str]:
    """Truncate context chunks to keep the combined prompt under target size."""
    result = []
    total = 0
    for chunk in chunks:
        remaining = max_chars - total
        if remaining <= 0:
            break
        result.append(chunk[:remaining])
        total += min(len(chunk), remaining)
    return result


class SecondLifePipeline:
    def __init__(
        self,
        model_path: str | Path,
        protocols_path: str | Path,
        role: str = "layperson",
        audit_path: Optional[str | Path] = None,
    ) -> None:
        self._model_path = Path(model_path)
        self._protocols_path = Path(protocols_path)
        self._default_role = role
        self._audit = AuditLog(path=audit_path)
        self._engine_obj: Optional[litert_lm.Engine] = None
        self._engine = None
        self._energy_log: list[float] = []

        if not FAISS_INDEX_PATH.exists():
            build_index(str(self._protocols_path))

    # ── Context manager ────────────────────────────────────────────────────────

    def __enter__(self) -> "SecondLifePipeline":
        self._engine_obj = litert_lm.Engine(
            model_path=str(self._model_path),
            backend=litert_lm.Backend.CPU,
            max_num_tokens=4096,
        )
        self._engine = self._engine_obj.__enter__()
        return self

    def __exit__(self, *args) -> None:
        if self._engine_obj is not None:
            self._engine_obj.__exit__(*args)
            self._engine_obj = None
            self._engine = None

    # ── Inference ──────────────────────────────────────────────────────────────

    def respond(
        self,
        query: str,
        role: Optional[str] = None,
        mode: str = "panic",
        audio: Optional[bytes] = None,
        image=None,
    ) -> dict:
        if self._engine is None:
            raise RuntimeError("Call respond() inside a 'with SecondLifePipeline(...) as p:' block")

        active_role = role or self._default_role
        t0 = time.perf_counter()

        # 1. Router: try protocol cache first to skip FAISS
        protocol_id = _classify_query(query)
        if protocol_id and protocol_id in PROTOCOL_CHUNK_CACHE:
            chunks = [PROTOCOL_CHUNK_CACHE[protocol_id]]
            citation = ""
        else:
            hits = retrieve_with_metadata(query, top_k=3)
            chunks = [h["text"] for h in hits]
            citation = f"{hits[0]['source']}, p.{hits[0]['page']}" if hits else ""

        # 2. Compact context to keep prompts small (panic: 300 chars, detail: 800 chars)
        max_ctx = 300 if mode == "panic" else 800
        chunks = _compact_context(chunks, max_chars=max_ctx)

        # 3. Build mode-appropriate prompt
        if mode == "panic":
            system_msg, user_msg = build_panic_prompt(query, chunks, active_role)
        else:
            system_msg, user_msg = build_prompt(query, chunks, active_role)

        # 4. Inference
        response_text = ""
        with self._engine.create_conversation(messages=[system_msg]) as conv:
            for msg in conv.send_message_async(user_msg):
                if isinstance(msg, dict):
                    for item in msg.get("content", []):
                        if item.get("type") == "text":
                            response_text += item.get("text", "")
                elif isinstance(msg, str):
                    response_text += msg

        latency_s = time.perf_counter() - t0
        latency_ms = int(latency_s * 1000)
        emah = _energy_mah(latency_s)
        self._energy_log.append(emah)

        # 5. Parse JSON in panic mode
        if mode == "panic":
            speak, steps, follow_up = _parse_json_response(response_text)
        else:
            speak = response_text or "[No response generated]"
            steps = []
            follow_up = None

        # 6. Audit (logged locally — not in emergency path output)
        self._audit.log(query=query, response=speak, role=active_role)

        return {
            "response": speak,
            "citation": citation,
            "latency_ms": latency_ms,
            "role": active_role,
            "mode": mode,
            "steps": steps,
            "follow_up": follow_up,
            "energy_mah": round(emah, 4),
        }

    # ── Efficiency report ──────────────────────────────────────────────────────

    def get_efficiency_report(self) -> dict:
        if not self._energy_log:
            return {}
        latencies = [e * 3.6 * _BATTERY_V / _NPU_TDP_W * 1000 for e in self._energy_log]
        avg_e = sum(self._energy_log) / len(self._energy_log)
        return {
            "avg_latency_ms": round(sum(latencies) / len(latencies)),
            "min_latency_ms": round(min(latencies)),
            "max_latency_ms": round(max(latencies)),
            "estimated_battery_per_query_mah": round(avg_e, 4),
            "queries_per_percent_battery": round(50 / avg_e, 1),
        }
