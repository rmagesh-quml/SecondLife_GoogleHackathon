"""
ai-pipeline/test_full_pipeline.py

Full integration test: one engine, three roles, one scenario.
Checks responses, distinctness, audit log, and hash chain.

Run from repo root:
    python ai-pipeline/test_full_pipeline.py
"""

import os
import sys
import tempfile
import time
from pathlib import Path

os.environ["GLOG_minloglevel"] = "3"
os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"

# Package path setup
REPO_ROOT = Path(__file__).resolve().parent.parent
AI_PIPELINE = REPO_ROOT / "ai-pipeline"
sys.path.insert(0, str(AI_PIPELINE))

from inference.secondlife_pipeline import SecondLifePipeline

REPO_ROOT_STR = str(REPO_ROOT)
MODEL_E4B = REPO_ROOT / "shared" / "models" / "gemma-4-E4B-it.litertlm"
MODEL_E2B = REPO_ROOT / "shared" / "models" / "gemma-4-E2B-it.litertlm"
PROTOCOLS_PATH = REPO_ROOT / "data" / "chunks" / "protocols.json"

SCENARIO = "elderly woman fell down stairs, not responding, possible head injury"
ROLES = ["layperson", "paramedic", "military_medic"]

EXPECTED_KEYWORDS = [
    "head", "conscious", "breathing", "spine", "call",
    "recovery", "cpr", "airway", "pulse", "respond",
    "emergency", "move", "injury", "check",
]


def resolve_model() -> Path:
    if MODEL_E4B.exists():
        return MODEL_E4B
    if MODEL_E2B.exists():
        print(f"[INFO] Using fallback: {MODEL_E2B.name}")
        return MODEL_E2B
    print("[ERROR] No model found. See shared/models/README.md.")
    sys.exit(1)


def check(label: str, passed: bool, detail: str = "") -> bool:
    status = "PASS" if passed else "FAIL"
    suffix = f"  ({detail})" if detail else ""
    print(f"  [{status}] {label}{suffix}")
    return passed


def main() -> None:
    model_path = resolve_model()
    results: list[bool] = []
    responses: list[str] = []

    with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as tmp:
        audit_path = tmp.name

    print("=" * 60)
    print(f"SCENARIO: {SCENARIO}")
    print(f"ROLES:    {', '.join(ROLES)}")
    print("=" * 60)

    total_start = time.perf_counter()

    with SecondLifePipeline(
        model_path=model_path,
        protocols_path=str(PROTOCOLS_PATH),
        audit_path=audit_path,
    ) as pipeline:

        for role in ROLES:
            print(f"\n── Role: {role} ──")
            result = pipeline.respond(SCENARIO, role=role)
            print(result["response"])
            print(f"   latency: {result['latency_ms']:,} ms  |  energy: {result['energy_mah']} mAh")
            responses.append(result["response"])

        report = pipeline.get_efficiency_report()

    total_ms = int((time.perf_counter() - total_start) * 1000)

    # ── Checks ─────────────────────────────────────────────────────────────────
    print("\n" + "=" * 60)
    print("CHECKS")
    print("=" * 60)

    # 1-3: each response contains at least one actionable keyword
    for i, (role, resp) in enumerate(zip(ROLES, responses)):
        rl = resp.lower()
        hit = next((kw for kw in EXPECTED_KEYWORDS if kw in rl), None)
        results.append(check(
            f"Response [{role}] has actionable keyword",
            hit is not None,
            f"'{hit}'" if hit else f"missing all of {EXPECTED_KEYWORDS[:5]}...",
        ))

    # 4: all 3 responses are distinct (no two identical)
    distinct = len(set(r[:80] for r in responses)) == 3
    results.append(check("All 3 responses are distinct", distinct))

    # 5: audit log has exactly 3 entries
    from audit_log.logger import AuditLog
    audit = AuditLog(path=audit_path)
    entries = audit.get_all_entries()
    results.append(check("Audit log has 3 entries", len(entries) == 3, f"got {len(entries)}"))

    # 6: hash chain is valid
    valid_chain = audit.verify_chain()
    results.append(check("Hash chain is valid", valid_chain))

    # ── Summary ────────────────────────────────────────────────────────────────
    print("\n" + "=" * 60)
    print(f"  Total wall time : {total_ms:,} ms")
    print(f"  Avg latency     : {report.get('avg_latency_ms', '—')} ms")
    print(f"  Battery/query   : {report.get('estimated_battery_per_query_mah', '—')} mAh")
    print(f"  Queries per 1%  : {report.get('queries_per_percent_battery', '—')}")
    print("=" * 60)

    all_passed = all(results)
    if all_passed:
        print("\n  ✅ ALL TESTS PASSED\n")
    else:
        failed = sum(1 for r in results if not r)
        print(f"\n  ❌ {failed}/6 FAILED\n")
        sys.exit(1)


if __name__ == "__main__":
    main()
