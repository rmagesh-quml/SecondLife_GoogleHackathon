"""
ai-pipeline/rag/test_rag.py

End-to-end RAG + Gemma 4 integration test.
Loads the engine ONCE and reuses it across all 3 queries.

Run from repo root:
    python ai-pipeline/rag/test_rag.py
"""

import os
import sys
import time
from pathlib import Path

os.environ["GLOG_minloglevel"] = "3"
os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"

import litert_lm
litert_lm.set_min_log_severity(litert_lm.LogSeverity.ERROR)

# ai-pipeline has a hyphen so it can't be imported as a package directly.
# Add the rag directory itself to sys.path so pipeline.py is importable by name.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from pipeline import build_index, retrieve, build_rag_prompt

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
MODEL_E4B = REPO_ROOT / "shared" / "models" / "gemma-4-E4B-it.litertlm"
MODEL_E2B = REPO_ROOT / "shared" / "models" / "gemma-4-E2B-it.litertlm"

TESTS = [
    {
        "query": "child choking",
        "keywords": ["back blow", "thrust", "abdominal", "heimlich", "cough"],
        "description": "Child choking response",
    },
    {
        "query": "person collapsed no pulse",
        "keywords": ["cpr", "compression", "chest", "rescue breath", "aed", "defibrillator"],
        "description": "CPR / cardiac arrest",
    },
    {
        "query": "severe bleeding from arm",
        "keywords": ["pressure", "tourniquet", "bandage", "elevate", "bleeding", "wound"],
        "description": "Limb bleeding control",
    },
]


def resolve_model() -> Path:
    if MODEL_E4B.exists():
        return MODEL_E4B
    if MODEL_E2B.exists():
        print(f"[INFO] Using fallback model: {MODEL_E2B.name}")
        return MODEL_E2B
    print("[ERROR] No model found. Run: python ai-pipeline/inference/test_local.py for download instructions.")
    sys.exit(1)


def run_query(engine, query: str) -> str:
    chunks = retrieve(query, top_k=3)
    prompt = build_rag_prompt(query, chunks)
    response = ""

    with engine.create_conversation(
        messages=[{
            "role": "system",
            "content": (
                "You are a calm emergency first-aid assistant. "
                "Give concise numbered steps. No disclaimers."
            ),
        }]
    ) as conv:
        for msg in conv.send_message_async({"role": "user", "content": prompt}):
            if isinstance(msg, dict):
                for item in msg.get("content", []):
                    if item.get("type") == "text":
                        response += item.get("text", "")
            elif isinstance(msg, str):
                response += msg

    return response


def main() -> None:
    # ── Build index ────────────────────────────────────────────────────────────
    print("=" * 60)
    print("STEP 1: Building RAG index")
    print("=" * 60)
    t0 = time.perf_counter()
    build_index()
    print(f"[INFO] Index ready in {(time.perf_counter() - t0) * 1000:,.0f} ms\n")

    # ── Load engine once ───────────────────────────────────────────────────────
    model_path = resolve_model()
    print("=" * 60)
    print(f"STEP 2: Loading {model_path.name}")
    print("=" * 60)
    t1 = time.perf_counter()
    engine = litert_lm.Engine(
        model_path=str(model_path),
        backend=litert_lm.Backend.CPU,
        max_num_tokens=4096,
    )
    print(f"[INFO] Engine loaded in {(time.perf_counter() - t1) * 1000:,.0f} ms\n")

    # ── Run tests ──────────────────────────────────────────────────────────────
    results = []
    with engine:
        for i, test in enumerate(TESTS, 1):
            print("=" * 60)
            print(f"TEST {i}/3: {test['description']}")
            print(f"Query   : {test['query']}")
            print("=" * 60)

            t2 = time.perf_counter()
            response = run_query(engine, test["query"])
            latency_ms = int((time.perf_counter() - t2) * 1000)

            print(response)
            print()

            rl = response.lower()
            hit = next((kw for kw in test["keywords"] if kw in rl), None)
            passed = hit is not None
            results.append(passed)

            status = f"[PASS] keyword: '{hit}'" if passed else f"[FAIL] missing all of: {test['keywords']}"
            print(f"{status}  |  latency: {latency_ms:,} ms\n")

    # ── Summary ───────────────────────────────────────────────────────────────
    print("=" * 60)
    passed_count = sum(results)
    for i, (test, ok) in enumerate(zip(TESTS, results), 1):
        mark = "PASS" if ok else "FAIL"
        print(f"  [{mark}] Test {i}: {test['description']}")
    print("=" * 60)
    print(f"\n  {passed_count}/3 tests passed\n")

    if passed_count < 3:
        sys.exit(1)


if __name__ == "__main__":
    main()
