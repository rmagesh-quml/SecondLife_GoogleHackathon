"""
ai-pipeline/function_calling/roles.py

Role-aware prompt system. Kept intentionally lightweight —
roles shape the system voice, not the core first-aid logic.
"""

from __future__ import annotations

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


def get_system_prompt(role: str) -> str:
    return ROLE_PROMPTS.get(role, ROLE_PROMPTS[_DEFAULT_ROLE])


def build_prompt(query: str, context: list[str], role: str) -> str:
    """Combine retrieved chunks + query into a user message string."""
    if context:
        context_block = "\n\n".join(
            f"[Protocol {i + 1}]\n{chunk}" for i, chunk in enumerate(context)
        )
        return (
            f"Relevant protocol context:\n\n{context_block}\n\n"
            f"Question: {query}\n"
            "Answer with clear, numbered steps based on the context above."
        )
    return f"Question: {query}\nAnswer with clear, numbered steps."
