package com.secondlife.emergency

object EmergencyRouter {

    enum class ProtocolId {
        SEIZURE, CHOKING_ADULT, CHOKING_INFANT, SEVERE_BLEEDING,
        CPR, BURN, FRACTURE, ALLERGIC_REACTION, POISONING, UNKNOWN
    }

    fun classify(text: String): ProtocolId {
        val t = text.lowercase()
        return when {
            t.anyOf("seizure", "seizing", "convuls", "shaking uncontrollably", "fitting") ->
                ProtocolId.SEIZURE

            t.anyOf("choking", "can't breathe", "cannot breathe", "turning blue", "blue lips") &&
                t.anyOf("infant", "baby", "newborn") ->
                ProtocolId.CHOKING_INFANT

            t.anyOf("choking", "can't breathe", "cannot breathe", "turning blue", "blue lips", "heimlich") ->
                ProtocolId.CHOKING_ADULT

            t.anyOf("bleeding", "blood everywhere", "hemorrhage", "cut badly", "cut deeply", "gushing blood", "won't stop bleeding") ->
                ProtocolId.SEVERE_BLEEDING

            t.anyOf("not breathing", "no pulse", "cardiac arrest", "unresponsive", "cpr", "collapsed") ->
                ProtocolId.CPR

            t.anyOf("burn", "burned", "scalded", "on fire", "chemical burn") ->
                ProtocolId.BURN

            t.anyOf("broken bone", "fracture", "broken arm", "broken leg", "broken wrist", "bone sticking") ->
                ProtocolId.FRACTURE

            t.anyOf("allergic", "anaphylax", "epipen", "hives", "throat swelling", "swollen throat") ->
                ProtocolId.ALLERGIC_REACTION

            t.anyOf("poison", "swallowed", "overdose", "toxic", "ingested something") ->
                ProtocolId.POISONING

            else -> ProtocolId.UNKNOWN
        }
    }

    private fun String.anyOf(vararg keywords: String): Boolean =
        keywords.any { this.contains(it) }
}
