"""
ai-pipeline/inference/secondlife_pipeline.py

Full SecondLife inference pipeline: RAG → role prompt → Gemma 4 → audit log.

Usage:
    with SecondLifePipeline(model_path, protocols_path) as pipeline:
        result = pipeline.respond("child choking", role="layperson")
        print(result["response"])
        print(pipeline.get_efficiency_report())
"""

from __future__ import annotations

import os
import sys
import time
from pathlib import Path
from typing import Optional

os.environ.setdefault("GLOG_minloglevel", "3")
os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "3")

import litert_lm
litert_lm.set_min_log_severity(litert_lm.LogSeverity.ERROR)

# Resolve sibling packages relative to repo root
_REPO_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(_REPO_ROOT / "ai-pipeline"))

from rag.pipeline import retrieve_with_metadata, build_index, build_rag_prompt, FAISS_INDEX_PATH
from function_calling.roles import get_system_prompt, build_prompt
from audit_log.logger import AuditLog

# Samsung S25 Ultra constants for battery estimation
_BATTERY_MAH = 5000
_BATTERY_V = 3.85
_NPU_TDP_W = 1.8


def _energy_mah(latency_s: float) -> float:
    return _NPU_TDP_W * latency_s / 3.6 / _BATTERY_V


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
        audio: Optional[bytes] = None,
        image=None,
    ) -> dict:
        if self._engine is None:
            raise RuntimeError("Call respond() inside a 'with SecondLifePipeline(...) as p:' block")

        active_role = role or self._default_role
        t0 = time.perf_counter()

        # RAG retrieval
        hits = retrieve_with_metadata(query, top_k=3)
        chunks = [h["text"] for h in hits]
        citation = f"{hits[0]['source']}, p.{hits[0]['page']}" if hits else ""

        # Prompt construction
        system_msg = {"role": "system", "content": get_system_prompt(active_role)}
        user_content = build_prompt(query, chunks, active_role)
        user_msg = {"role": "user", "content": user_content}

        # Inference
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

        # Audit
        self._audit.log(query=query, response=response_text, role=active_role)

        return {
            "response": response_text or "[No response generated]",
            "citation": citation,
            "latency_ms": latency_ms,
            "role": active_role,
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
