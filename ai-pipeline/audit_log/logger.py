"""
ai-pipeline/audit_log/logger.py

SHA-256 hash-chained append-only audit log.
Each entry links to the previous via prev_hash, forming a tamper-evident chain.
File is gitignored — never commit (may contain patient-adjacent data).
"""

from __future__ import annotations

import hashlib
import json
import threading
import time
from pathlib import Path
from typing import Optional

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
DEFAULT_LOG_PATH = REPO_ROOT / "audit_log.json"

GENESIS_HASH = "GENESIS"


def _compute_hash(entry_without_hash: dict) -> str:
    serialised = json.dumps(entry_without_hash, sort_keys=True)
    return hashlib.sha256(serialised.encode()).hexdigest()


class AuditLog:
    def __init__(self, path: Optional[Path] = None) -> None:
        self._path = Path(path) if path else DEFAULT_LOG_PATH
        self._lock = threading.Lock()

    # ── Write ──────────────────────────────────────────────────────────────────

    def log(self, query: str, response: str, role: str) -> None:
        with self._lock:
            prev_hash = self._last_hash()
            entry = {
                "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "role": role,
                "query": query,
                "response": response,
                "prev_hash": prev_hash,
            }
            entry["hash"] = _compute_hash(entry)
            with open(self._path, "a") as f:
                f.write(json.dumps(entry) + "\n")

    # ── Read ───────────────────────────────────────────────────────────────────

    def get_all_entries(self) -> list[dict]:
        if not self._path.exists():
            return []
        entries = []
        with open(self._path) as f:
            for line in f:
                line = line.strip()
                if line:
                    entries.append(json.loads(line))
        return entries

    # ── Verify ─────────────────────────────────────────────────────────────────

    def verify_chain(self) -> bool:
        entries = self.get_all_entries()
        if not entries:
            return True

        expected_prev = GENESIS_HASH
        for entry in entries:
            if entry.get("prev_hash") != expected_prev:
                return False
            # Recompute hash without the "hash" field
            body = {k: v for k, v in entry.items() if k != "hash"}
            if _compute_hash(body) != entry.get("hash"):
                return False
            expected_prev = entry["hash"]

        return True

    # ── Internal ───────────────────────────────────────────────────────────────

    def _last_hash(self) -> str:
        entries = self.get_all_entries()
        return entries[-1]["hash"] if entries else GENESIS_HASH
