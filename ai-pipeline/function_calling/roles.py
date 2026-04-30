"""
ai-pipeline/function_calling/roles.py

Role-aware system prompt and function schema resolver.

Each clinician role gets a different system prompt that tunes the model's
vocabulary, assumed knowledge level, and response format.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

Role = Literal["layperson", "paramedic", "military_medic"]

ROLES: list[Role] = ["layperson", "paramedic", "military_medic"]


@dataclass
class RoleConfig:
    role: Role
    system_prompt: str
    max_response_tokens: int


_CONFIGS: dict[Role, RoleConfig] = {
    "layperson": RoleConfig(
        role="layperson",
        system_prompt=(
            "You are a calm emergency first-aid assistant helping someone with no medical training. "
            "Use plain English. Give short, numbered steps. Lead with the single most important action. "
            "Never use medical jargon without immediately explaining it. "
            "Always end with: 'Call emergency services now if you haven't already.'"
        ),
        max_response_tokens=300,
    ),
    "paramedic": RoleConfig(
        role="paramedic",
        system_prompt=(
            "You are a clinical decision-support assistant for a trained paramedic. "
            "Use standard EMS terminology and protocol references. "
            "Be concise — the user is in the field and needs fast, precise guidance. "
            "Include drug dosages, routes, and contraindications where relevant. "
            "Cite the protocol document and page number when available."
        ),
        max_response_tokens=500,
    ),
    "military_medic": RoleConfig(
        role="military_medic",
        system_prompt=(
            "You are a Tactical Combat Casualty Care (TCCC) assistant for a military medic. "
            "Follow MARCH protocol order: Massive hemorrhage, Airway, Respiration, Circulation, Hypothermia. "
            "Be direct and decisive. Assume austere environment with limited supplies. "
            "Cite TCCC guidelines and CoTCCC recommendations where applicable."
        ),
        max_response_tokens=500,
    ),
}


def get_config(role: Role) -> RoleConfig:
    if role not in _CONFIGS:
        raise ValueError(f"Unknown role '{role}'. Valid roles: {ROLES}")
    return _CONFIGS[role]


def build_prompt(query: str, context: str, role: Role) -> list[dict]:
    """
    Build the full message list for LiteRT-LM create_conversation + send_message_async.

    Returns:
        system_messages: list with one system message (passed to create_conversation)
        user_message:    the user turn dict (passed to send_message_async)
    """
    config = get_config(role)

    system_content = config.system_prompt
    if context:
        system_content += (
            f"\n\nRelevant protocol context:\n{context}\n\n"
            "Base your answer on the context above. If the context does not cover the question, "
            "answer from general knowledge and note that no protocol was retrieved."
        )

    system_message = {"role": "system", "content": system_content}
    user_message = {"role": "user", "content": query}

    return system_message, user_message
