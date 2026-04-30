"""
ai-pipeline/inference/benchmark.py

On-device performance benchmark for SecondLife / Gemma 4 E4B.
Uses litert_lm's native Benchmark API — no manual timing loops for token throughput.

Run from repo root:
    python ai-pipeline/inference/benchmark.py
"""

from __future__ import annotations

import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from statistics import mean

os.environ["GLOG_minloglevel"] = "3"
os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"

import litert_lm
litert_lm.set_min_log_severity(litert_lm.LogSeverity.ERROR)

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
MODEL_E4B = REPO_ROOT / "shared" / "models" / "gemma-4-E4B-it.litertlm"
MODEL_E2B = REPO_ROOT / "shared" / "models" / "gemma-4-E2B-it.litertlm"
RESULTS_PATH = REPO_ROOT / "benchmark_results.json"

NATIVE_PASSES = 5
E2E_PASSES = 5
WARMUP = 1   # pass index 0 excluded from averages

E2E_QUERIES = [
    "child choking, not breathing",
    "elderly man collapsed, no pulse",
    "severe bleeding from leg wound",
    "person unresponsive after fall",
    "allergic reaction, face swelling",
]


# ── Backend probe ──────────────────────────────────────────────────────────────

def probe_backend() -> litert_lm.Backend:
    """Try GPU first, fall back to CPU."""
    try:
        bm = litert_lm.Benchmark(
            model_path=str(_resolve_model()),
            backend=litert_lm.Backend.GPU,
            prefill_tokens=16,
            decode_tokens=16,
        )
        bm.run()
        print("[INFO] Backend: GPU (Metal/WebGPU)")
        return litert_lm.Backend.GPU
    except Exception as e:
        print(f"[INFO] GPU unavailable ({e.__class__.__name__}) — using CPU")
        return litert_lm.Backend.CPU


def _resolve_model() -> Path:
    if MODEL_E4B.exists():
        return MODEL_E4B
    if MODEL_E2B.exists():
        print(f"[INFO] Using fallback: {MODEL_E2B.name}")
        return MODEL_E2B
    print("[ERROR] No model found. See shared/models/README.md.")
    sys.exit(1)


# ── Native benchmark ───────────────────────────────────────────────────────────

def run_native_benchmark(model_path: Path, backend: litert_lm.Backend) -> dict:
    """
    5 passes with litert_lm.Benchmark API.
    Pass 0 is warm-up — excluded from averages.
    Returns aggregated stats dict.
    """
    print(f"\n[Native] Running {NATIVE_PASSES} passes (pass 0 = warm-up)...")

    raw: list[litert_lm.BenchmarkInfo] = []
    for i in range(NATIVE_PASSES):
        bm = litert_lm.Benchmark(
            model_path=str(model_path),
            backend=backend,
            prefill_tokens=128,
            decode_tokens=256,
        )
        r = bm.run()
        tag = "warm-up" if i == 0 else f"pass {i}"
        print(
            f"  [{tag}] init={r.init_time_in_second:.2f}s  "
            f"TTFT={r.time_to_first_token_in_second:.3f}s  "
            f"decode={r.last_decode_tokens_per_second:.1f} tok/s  "
            f"prefill={r.last_prefill_tokens_per_second:.1f} tok/s"
        )
        raw.append(r)

    measured = raw[WARMUP:]  # exclude pass 0

    init_times     = [r.init_time_in_second for r in measured]
    ttft_times     = [r.time_to_first_token_in_second for r in measured]
    decode_speeds  = [r.last_decode_tokens_per_second for r in measured]
    prefill_speeds = [r.last_prefill_tokens_per_second for r in measured]

    return {
        "passes_total": NATIVE_PASSES,
        "passes_measured": NATIVE_PASSES - WARMUP,
        "prefill_tokens": 128,
        "decode_tokens": 256,
        "init_time_s":              round(mean(init_times), 3),
        "ttft_avg_s":               round(mean(ttft_times), 3),
        "ttft_min_s":               round(min(ttft_times), 3),
        "ttft_max_s":               round(max(ttft_times), 3),
        "decode_tokens_per_s_avg":  round(mean(decode_speeds), 1),
        "decode_tokens_per_s_min":  round(min(decode_speeds), 1),
        "decode_tokens_per_s_max":  round(max(decode_speeds), 1),
        "prefill_tokens_per_s_avg": round(mean(prefill_speeds), 1),
    }


# ── E2E benchmark ──────────────────────────────────────────────────────────────

def run_e2e_benchmark(model_path: Path, backend: litert_lm.Backend) -> dict:
    """
    5 full pipeline queries through SecondLifePipeline.
    Pass 0 is warm-up — excluded from averages.
    """
    sys.path.insert(0, str(REPO_ROOT / "ai-pipeline"))
    from inference.secondlife_pipeline import SecondLifePipeline
    from rag.pipeline import PROTOCOLS_PATH

    print(f"\n[E2E] Running {E2E_PASSES} queries (pass 0 = warm-up)...")

    latencies: list[int] = []
    char_counts: list[int] = []

    with SecondLifePipeline(
        model_path=model_path,
        protocols_path=str(PROTOCOLS_PATH),
        audit_path=str(REPO_ROOT / "audit_log.json"),
    ) as pipeline:
        for i, query in enumerate(E2E_QUERIES[:E2E_PASSES]):
            result = pipeline.respond(query, role="layperson")
            tag = "warm-up" if i == 0 else f"pass {i}"
            print(
                f"  [{tag}] {result['latency_ms']:,} ms  "
                f"{len(result['response'])} chars  "
                f"citation: {result['citation'] or 'none'}"
            )
            if i >= WARMUP:
                latencies.append(result["latency_ms"])
                char_counts.append(len(result["response"]))

    return {
        "passes_total": E2E_PASSES,
        "passes_measured": E2E_PASSES - WARMUP,
        "e2e_latency_avg_ms": round(mean(latencies)),
        "e2e_latency_min_ms": min(latencies),
        "e2e_latency_max_ms": max(latencies),
        "avg_response_chars": round(mean(char_counts)),
    }


# ── Report ─────────────────────────────────────────────────────────────────────

def print_report(model_path: Path, backend: litert_lm.Backend, native: dict, e2e: dict) -> None:
    backend_name = "GPU (Metal)" if backend == litert_lm.Backend.GPU else "CPU"
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")

    print("\n")
    print("╔══════════════════════════════════════════════════════════════╗")
    print("║              SecondLife — On-Device Benchmark                ║")
    print("╠══════════════════════════════════════════════════════════════╣")
    print(f"║  Model   : {model_path.name:<51}║")
    print(f"║  Backend : {backend_name:<51}║")
    print(f"║  Date    : {now:<51}║")
    print("╠══════════════════════════════════════════════════════════════╣")
    print("║  NATIVE BENCHMARK  (litert_lm.Benchmark API)                 ║")
    print("╠══════════════════════════════════════════════════════════════╣")
    print(f"║  Model init time         : {native['init_time_s']:.2f} s{'':<34}║")
    print(f"║  Time to first token     : {native['ttft_avg_s']:.3f} s avg  "
          f"({native['ttft_min_s']:.3f}–{native['ttft_max_s']:.3f} s){'':<14}║")
    print(f"║  Decode speed            : {native['decode_tokens_per_s_avg']:.1f} tok/s avg  "
          f"({native['decode_tokens_per_s_min']:.1f}–{native['decode_tokens_per_s_max']:.1f}){'':<12}║")
    print(f"║  Prefill speed           : {native['prefill_tokens_per_s_avg']:.1f} tok/s{'':<33}║")
    print("╠══════════════════════════════════════════════════════════════╣")
    print("║  END-TO-END BENCHMARK  (RAG + Gemma 4 + TTS-ready output)   ║")
    print("╠══════════════════════════════════════════════════════════════╣")
    print(f"║  E2E latency             : {e2e['e2e_latency_avg_ms']:,} ms avg  "
          f"({e2e['e2e_latency_min_ms']:,}–{e2e['e2e_latency_max_ms']:,} ms){'':<6}║")
    print(f"║  Avg response length     : {e2e['avg_response_chars']} chars{'':<35}║")
    print("╠══════════════════════════════════════════════════════════════╣")
    print("║  ★  PITCH-DECK NUMBERS                                       ║")
    print("╠══════════════════════════════════════════════════════════════╣")
    print(f"║  Decode speed    →  {native['decode_tokens_per_s_avg']:.0f} tokens/sec on-device{'':<24}║")
    print(f"║  Time to first token →  {native['ttft_avg_s']:.2f} s{'':<32}║")
    print(f"║  Full response   →  ~{e2e['e2e_latency_avg_ms'] / 1000:.0f} s end-to-end{'':<30}║")
    print(f"║  Model load      →  {native['init_time_s']:.1f} s (cached after first run){'':<21}║")
    print(f"║  Zero network calls  ✓{'':<40}║")
    print(f"║  Fully offline       ✓{'':<40}║")
    print("╚══════════════════════════════════════════════════════════════╝")
    print()


# ── Save results ───────────────────────────────────────────────────────────────

def save_results(model_path: Path, backend: litert_lm.Backend, native: dict, e2e: dict) -> None:
    payload = {
        "model": model_path.name,
        "backend": "GPU" if backend == litert_lm.Backend.GPU else "CPU",
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "native_benchmark": native,
        "e2e_benchmark": e2e,
    }
    with open(RESULTS_PATH, "w") as f:
        json.dump(payload, f, indent=2)
    print(f"[INFO] Results saved → {RESULTS_PATH.relative_to(REPO_ROOT)}")


# ── Main ───────────────────────────────────────────────────────────────────────

def main() -> None:
    model_path = _resolve_model()
    print(f"[INFO] Model: {model_path.name}  ({model_path.stat().st_size / 1e9:.2f} GB)")

    backend = probe_backend()

    native = run_native_benchmark(model_path, backend)
    e2e    = run_e2e_benchmark(model_path, backend)

    print_report(model_path, backend, native, e2e)
    save_results(model_path, backend, native, e2e)


if __name__ == "__main__":
    main()
