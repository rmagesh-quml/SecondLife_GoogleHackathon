"""
ai-pipeline/audit_log/logger.py

Append-only JSON audit log for SecondLife.

Each inference call is logged as one JSON line in audit_log.json at the repo root.
The file is gitignored — it must never be committed (may contain patient-adjacent data).

Usage:
    from ai_pipeline.audit_log.logger import AuditLogger
    logger = AuditLogger()
    logger.log(query="chest pain protocol", response=result)
"""

from __future__ import annotations

import json
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Optional

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
AUDIT_LOG_PATH = REPO_ROOT / "audit_log.json"


@dataclass
class AuditEntry:
    timestamp_ms: int
    role: str
    query: str
    response_chars: int
    citation: str
    latency_ms: int
    model: str
    error: Optional[str] = None


class AuditLogger:
    def __init__(self, log_path: Path = AUDIT_LOG_PATH) -> None:
        self._path = log_path

    def log(
        self,
        query: str,
        role: str,
        citation: str,
        latency_ms: int,
        response_chars: int,
        model: str = "gemma-4-E4B",
        error: Optional[str] = None,
    ) -> None:
        entry = AuditEntry(
            timestamp_ms=int(time.time() * 1000),
            role=role,
            query=query,
            response_chars=response_chars,
            citation=citation,
            latency_ms=latency_ms,
            model=model,
            error=error,
        )
        with open(self._path, "a") as f:
            f.write(json.dumps(asdict(entry)) + "\n")

    def read_all(self) -> list[AuditEntry]:
        if not self._path.exists():
            return []
        entries = []
        with open(self._path) as f:
            for line in f:
                line = line.strip()
                if line:
                    entries.append(AuditEntry(**json.loads(line)))
        return entries
