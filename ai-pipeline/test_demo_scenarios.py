"""
ai-pipeline/test_demo_scenarios.py

Pre-demo verification — run this 10 minutes before judges arrive.
Loads the engine once and fires all 5 demo scenarios.
Prints exactly what judges will see. Exits 0 if all pass, 1 if any fail.

Run from repo root:
    python ai-pipeline/test_demo_scenarios.py
"""

import os, sys, time
from pathlib import Path

os.environ["GLOG_minloglevel"] = "3"
os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"

sys.path.insert(0, str(Path(__file__).resolve().parent))

import litert_lm
litert_lm.set_min_log_severity(litert_lm.LogSeverity.ERROR)

from inference.secondlife_pipeline import SecondLifePipeline
from rag.pipeline import PROTOCOLS_PATH

REPO_ROOT = Path(__file__).resolve().parent.parent

DEMO_SCENARIOS = [
    {
        "id": 1,
        "label": "Choking child — Layperson",
        "query": "A 7-year-old child at this table just choked on a piece of food and cannot breathe or make a sound.",
        "role": "layperson",
        "must_contain": ["back blow", "thrust", "abdominal", "cough", "kneel"],
        "must_cite": "redcross_choking",
    },
    {
        "id": 2,
        "label": "Cardiac arrest — Layperson",
        "query": "Person next to me just collapsed, no pulse, lips turning blue.",
        "role": "layperson",
        "must_contain": ["compression", "chest", "cpr", "rescue breath", "30"],
        "must_cite": "aha_cpr",
    },
    {
        "id": 3,
        "label": "Femoral GSW — Military Medic",
        "query": "Soldier took a bullet to the upper thigh, femoral bleed, still conscious, I have a tourniquet.",
        "role": "military_medic",
        "must_contain": ["tourniquet", "bleed", "march", "airway", "hemorrhage"],
        "must_cite": None,  # either tccc or bleeding control is valid
    },
    {
        "id": 4,
        "label": "Stroke — Paramedic",
        "query": "Patient presenting with sudden onset facial droop, left arm drift, slurred speech, onset 8 minutes ago.",
        "role": "paramedic",
        "must_contain": ["stroke", "fast", "time", "911", "transport"],
        "must_cite": "stroke",
    },
    {
        "id": 5,
        "label": "Anaphylaxis — Layperson",
        "query": "Someone just injected peanut oil by accident — throat is swelling, they can barely breathe.",
        "role": "layperson",
        "must_contain": ["epinephrine", "epipen", "adrenaline", "911", "swelling"],
        "must_cite": "anaphylaxis",
    },
]


def main() -> None:
    model_path = REPO_ROOT / "shared" / "models" / "gemma-4-E4B-it.litertlm"
    if not model_path.exists():
        model_path = REPO_ROOT / "shared" / "models" / "gemma-4-E2B-it.litertlm"
    if not model_path.exists():
        print("[ERROR] No model found."); sys.exit(1)

    results = []
    total_start = time.perf_counter()

    print("\n" + "="*65)
    print("  SecondLife — Pre-Demo Scenario Verification")
    print("="*65)

    with SecondLifePipeline(
        model_path=model_path,
        protocols_path=str(PROTOCOLS_PATH),
    ) as pipeline:

        for s in DEMO_SCENARIOS:
            print(f"\n── Demo {s['id']}: {s['label']} ──")
            print(f"Q: {s['query']}")
            print()

            t0 = time.perf_counter()
            result = pipeline.respond(s["query"], role=s["role"])
            latency = int((time.perf_counter() - t0) * 1000)

            print(result["response"])
            print(f"\n   Citation  : {result['citation'] or '(none)'}")
            print(f"   Latency   : {latency:,} ms")

            # Keyword check
            rl = result["response"].lower()
            hit = next((kw for kw in s["must_contain"] if kw in rl), None)
            kw_pass = hit is not None

            # Citation check
            cite_pass = True
            if s["must_cite"]:
                cite_pass = s["must_cite"].replace("_", "") in result["citation"].replace("_", "").replace(".pdf", "")

            passed = kw_pass and cite_pass
            results.append(passed)

            kw_note = f"keyword '{hit}'" if hit else f"MISSING all of {s['must_contain'][:3]}"
            cite_note = f"citation OK" if cite_pass else f"citation MISS (got: {result['citation']})"
            print(f"\n   [{'PASS' if passed else 'FAIL'}] {kw_note}  |  {cite_note}")

    total_ms = int((time.perf_counter() - total_start) * 1000)

    print("\n" + "="*65)
    for s, ok in zip(DEMO_SCENARIOS, results):
        print(f"  [{'PASS' if ok else 'FAIL'}] Demo {s['id']}: {s['label']}")
    print("="*65)
    print(f"\n  {sum(results)}/5 scenarios verified  |  total: {total_ms // 1000}s\n")

    if all(results):
        print("  ✅ ALL CLEAR — ready for judges\n")
    else:
        print("  ❌ FIX FAILURES BEFORE DEMO\n")
        sys.exit(1)


if __name__ == "__main__":
    main()
