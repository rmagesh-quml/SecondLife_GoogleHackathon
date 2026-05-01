package com.secondlife.emergency

import com.secondlife.emergency.EmergencyRouter.ProtocolId

data class ProtocolCard(
    val protocolId: ProtocolId,
    val title: String,
    val immediateSteps: List<String>,
    val timerLabel: String? = null,
    val callEmergency: Boolean = true,
)

object ProtocolCardCache {

    val cards: Map<ProtocolId, ProtocolCard> = mapOf(
        ProtocolId.SEIZURE to ProtocolCard(
            protocolId = ProtocolId.SEIZURE,
            title = "Seizure",
            immediateSteps = listOf(
                "Move sharp/hard objects away from them",
                "Do NOT hold them down or restrain them",
                "Do NOT put anything in their mouth",
                "Turn them gently onto their side if possible",
                "Start timing the seizure now",
            ),
            timerLabel = "Seizure timer",
        ),
        ProtocolId.CHOKING_ADULT to ProtocolCard(
            protocolId = ProtocolId.CHOKING_ADULT,
            title = "Choking (Adult)",
            immediateSteps = listOf(
                "Ask: Can you speak or cough? If yes — encourage coughing",
                "If they cannot: lean them forward, support their chest",
                "Give 5 firm back blows between shoulder blades",
                "Give 5 abdominal thrusts (Heimlich maneuver)",
                "Alternate 5 back blows + 5 thrusts until airway clears",
            ),
        ),
        ProtocolId.CHOKING_INFANT to ProtocolCard(
            protocolId = ProtocolId.CHOKING_INFANT,
            title = "Choking (Infant)",
            immediateSteps = listOf(
                "Hold infant face-down on your forearm, head lower than chest",
                "Give 5 firm back blows between shoulder blades with heel of hand",
                "Flip to face-up, give 5 chest thrusts with 2 fingers on breastbone",
                "Look in mouth — remove object ONLY if you can clearly see it",
                "Repeat back blows + chest thrusts until airway clears",
            ),
        ),
        ProtocolId.SEVERE_BLEEDING to ProtocolCard(
            protocolId = ProtocolId.SEVERE_BLEEDING,
            title = "Severe Bleeding",
            immediateSteps = listOf(
                "Apply firm direct pressure with a clean cloth or bandage",
                "Do NOT remove cloth — add more on top if soaked through",
                "Elevate the injured area above heart level if possible",
                "Maintain continuous firm pressure — do NOT release",
                "If on a limb and bleeding won't stop: apply tourniquet above wound",
            ),
            timerLabel = "Pressure timer",
        ),
        ProtocolId.CPR to ProtocolCard(
            protocolId = ProtocolId.CPR,
            title = "CPR",
            immediateSteps = listOf(
                "Place heel of hand on center of chest (lower half of sternum)",
                "Push down 5–6 cm at 100–120 compressions per minute",
                "Allow full chest recoil between compressions",
                "After 30 compressions: 2 rescue breaths (tilt head, seal mouth)",
                "Continue 30:2 — use AED as soon as one is available",
            ),
            timerLabel = "CPR metronome",
        ),
        ProtocolId.BURN to ProtocolCard(
            protocolId = ProtocolId.BURN,
            title = "Burn",
            immediateSteps = listOf(
                "Cool with cool (not cold/icy) running water for 20 minutes",
                "Do NOT use ice, butter, toothpaste, or creams",
                "Remove jewelry/clothing near the burn — unless stuck to skin",
                "Cover loosely with clean cling film or non-fluffy material",
                "Do NOT burst any blisters",
            ),
            timerLabel = "Cooling timer (20 min)",
        ),
        ProtocolId.FRACTURE to ProtocolCard(
            protocolId = ProtocolId.FRACTURE,
            title = "Fracture",
            immediateSteps = listOf(
                "Immobilize the injured area — do NOT attempt to straighten",
                "Support the limb above and below the injury point",
                "Apply ice wrapped in cloth to reduce swelling",
                "Check circulation below injury (pulse, sensation, color)",
                "Do NOT allow weight-bearing on the injured limb",
            ),
        ),
        ProtocolId.ALLERGIC_REACTION to ProtocolCard(
            protocolId = ProtocolId.ALLERGIC_REACTION,
            title = "Severe Allergic Reaction",
            immediateSteps = listOf(
                "Use epinephrine auto-injector (EpiPen) into outer mid-thigh NOW",
                "Lay person flat with legs raised — unless breathing is difficult",
                "Call emergency services immediately after injection",
                "A second EpiPen can be given after 5–15 minutes if no improvement",
                "Stay with them — symptoms can return after 15–30 minutes",
            ),
        ),
        ProtocolId.POISONING to ProtocolCard(
            protocolId = ProtocolId.POISONING,
            title = "Poisoning / Overdose",
            immediateSteps = listOf(
                "Do NOT induce vomiting unless Poison Control specifically instructs",
                "Note what was taken, how much, when, and person's weight/age",
                "Call Poison Control (1-800-222-1222 US) or emergency services",
                "Keep them awake and monitor breathing closely",
                "If unconscious but breathing: recovery position on their side",
            ),
        ),
    )

    fun get(id: ProtocolId): ProtocolCard? = cards[id]
}
