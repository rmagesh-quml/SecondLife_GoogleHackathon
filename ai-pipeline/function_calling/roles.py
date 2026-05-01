"""
ai-pipeline/function_calling/roles.py

Role-aware prompt system with Panic and Detail modes.
Panic mode: compact JSON output (60-120 tokens). Detail mode: full numbered explanation.
"""

from __future__ import annotations

# ── Role voice definitions ────────────────────────────────────────────────────

LAYPERSON = (
    "You are a calm emergency guide helping someone with zero medical training. "
    "Use plain English only. Number every step. Lead with the single most important action. "
    "Never use medical jargon without explaining it immediately. "
    "End every response with: 'Call emergency services now if you haven't already.'"
)

PARAMEDIC = (
    "You are a clinical decision-support tool for a trained paramedic in the field. "
    "Use clinical language. Include drug names, dosages, and routes where relevant. "
    "Be concise — the user needs fast, precise guidance. "
    "Cite protocol source and page when available."
)

MILITARY_MEDIC = (
    "You are a Tactical Combat Casualty Care assistant for a military medic. "
    "Use TCCC terminology. Assume austere environment with minimal equipment. "
    "Follow MARCH order: Massive hemorrhage, Airway, Respiration, Circulation, Hypothermia. "
    "Be direct and decisive. No hedging."
)

ROLE_PROMPTS: dict[str, str] = {
    "layperson": LAYPERSON,
    "paramedic": PARAMEDIC,
    "military_medic": MILITARY_MEDIC,
}

_DEFAULT_ROLE = "layperson"

# ── Panic mode role modifiers (terse, action-only) ────────────────────────────

_PANIC_ROLE_MODIFIER: dict[str, str] = {
    "layperson": "Plain English only. No jargon. Max 25 words for 'speak'.",
    "paramedic": "Clinical language. Include dosages if relevant. Concise.",
    "military_medic": "TCCC. MARCH order. Austere environment assumed. Direct.",
}

# ── JSON output schema for panic mode ────────────────────────────────────────

_PANIC_JSON_SCHEMA = (
    'Respond ONLY with valid JSON — no other text before or after:\n'
    '{"speak":"<25 words max, most critical action first>",'
    '"steps":["step 1","step 2","step 3"],'
    '"ask":"<one follow-up question to assess situation>"}'
)


def get_system_prompt(role: str) -> str:
    return ROLE_PROMPTS.get(role, ROLE_PROMPTS[_DEFAULT_ROLE])


def build_prompt(query: str, context: list[str], role: str) -> tuple[dict, dict]:
    """Detail mode: full context + numbered steps. Returns (system_msg, user_msg)."""
    system_msg = {"role": "system", "content": get_system_prompt(role)}
    if context:
        context_block = "\n\n".join(
            f"[Protocol {i + 1}]\n{chunk[:600]}" for i, chunk in enumerate(context[:2])
        )
        user_content = (
            f"Relevant protocol context:\n\n{context_block}\n\n"
            f"Question: {query}\n"
            "Answer with clear, numbered steps based on the context above."
        )
    else:
        user_content = f"Question: {query}\nAnswer with clear, numbered steps."
    user_msg = {"role": "user", "content": user_content}
    return system_msg, user_msg


def build_panic_prompt(query: str, context: list[str], role: str) -> tuple[dict, dict]:
    """
    Panic mode: compact context, JSON-only output, 60-120 token target.
    Returns (system_msg, user_msg).
    """
    modifier = _PANIC_ROLE_MODIFIER.get(role, _PANIC_ROLE_MODIFIER["layperson"])
    system_content = f"[PANIC MODE] {modifier}"
    system_msg = {"role": "system", "content": system_content}

    # Only first chunk, truncated to 300 chars
    context_line = f"Protocol context: {context[0][:300]}\n\n" if context else ""
    user_content = f"{context_line}Emergency: {query}\n\n{_PANIC_JSON_SCHEMA}"
    user_msg = {"role": "user", "content": user_content}
    return system_msg, user_msg
