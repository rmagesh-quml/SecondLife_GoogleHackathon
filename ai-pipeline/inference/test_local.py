"""
ai-pipeline/inference/test_local.py

Smoke test — loads Gemma 4 E4B (or E2B fallback) locally and runs one inference call.

Run from anywhere inside the repo:
    python ai-pipeline/inference/test_local.py

Requirements: pip install litert-lm
"""

import os
import sys
import time
from pathlib import Path

# Must be set BEFORE importing litert_lm so the C++ backend reads them at init.
os.environ["GLOG_minloglevel"] = "3"
os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"

import litert_lm  # noqa: E402

litert_lm.set_min_log_severity(litert_lm.LogSeverity.ERROR)

# ── Model path resolution ──────────────────────────────────────────────────────
# This file: <repo>/ai-pipeline/inference/test_local.py
# Model:     <repo>/shared/models/gemma-4-E4B-it.litertlm
REPO_ROOT = Path(__file__).resolve().parent.parent.parent
MODEL_E4B = REPO_ROOT / "shared" / "models" / "gemma-4-E4B-it.litertlm"
MODEL_E2B = REPO_ROOT / "shared" / "models" / "gemma-4-E2B-it.litertlm"

TEST_PROMPT = "A child is choking. What do I do right now?"

PASS_KEYWORDS = ["thrust", "back blow", "abdominal", "heimlich"]


def resolve_model() -> Path:
    if MODEL_E4B.exists():
        return MODEL_E4B
    if MODEL_E2B.exists():
        print(f"[INFO] E4B not found — using fallback: {MODEL_E2B.name}")
        return MODEL_E2B

    print("\n" + "=" * 70)
    print("  MODEL FILE NOT FOUND")
    print("=" * 70)
    print(f"\n  Expected:\n    {MODEL_E4B}\n")
    print("  Download with Python:\n")
    print("    from huggingface_hub import hf_hub_download")
    print("    hf_hub_download(")
    print("        repo_id='litert-community/gemma-4-E4B-it-litert-lm',")
    print("        filename='gemma-4-E4B-it.litertlm',")
    print(f"        local_dir='{MODEL_E4B.parent}'")
    print("    )")
    print("\n  See shared/models/README.md for full instructions.")
    print("=" * 70 + "\n")
    sys.exit(1)


def main() -> None:
    model_path = resolve_model()

    print(f"\n[INFO] Loading model : {model_path.name}")
    print(f"[INFO] Size on disk  : {model_path.stat().st_size / 1e9:.2f} GB")
    print("[INFO] First load may take 10–60 s (model compilation)...\n")

    load_start = time.perf_counter()

    with litert_lm.Engine(
        model_path=str(model_path),
        backend=litert_lm.Backend.CPU,
        max_num_tokens=4096,  # minimum 4096 — lower values cause DYNAMIC_UPDATE_SLICE crash
    ) as engine:

        load_ms = (time.perf_counter() - load_start) * 1000
        print(f"[INFO] Engine loaded in {load_ms:,.0f} ms\n")

        with engine.create_conversation(
            messages=[
                {
                    "role": "system",
                    "content": (
                        "You are a calm, clear emergency first-aid assistant. "
                        "Give concise, numbered, actionable steps. No disclaimers."
                    ),
                }
            ]
        ) as conv:

            print("=" * 60)
            print("PROMPT:")
            print(f"  {TEST_PROMPT}")
            print("=" * 60)
            print("RESPONSE:\n")

            infer_start = time.perf_counter()
            response = ""

            for msg in conv.send_message_async({"role": "user", "content": TEST_PROMPT}):
                if isinstance(msg, dict):
                    for item in msg.get("content", []):
                        if item.get("type") == "text":
                            response += item.get("text", "")
                elif isinstance(msg, str):
                    response += msg

            infer_ms = (time.perf_counter() - infer_start) * 1000

            print(response, flush=True)
            print("\n" + "=" * 60)
            print(f"  Latency    : {infer_ms:,.0f} ms")
            print(f"  Characters : {len(response)}")
            print("=" * 60)

            rl = response.lower()
            hit = next((kw for kw in PASS_KEYWORDS if kw in rl), None)
            if hit:
                print(f"\n[PASS] Response contains expected keyword: '{hit}'")
            else:
                print(f"\n[FAIL] Response missing all of: {PASS_KEYWORDS}")
                sys.exit(1)


if __name__ == "__main__":
    main()
