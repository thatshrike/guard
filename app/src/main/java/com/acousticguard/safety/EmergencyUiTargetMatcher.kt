package com.acousticguard.safety

import java.util.Locale

enum class EmergencyUiTargetKind {
    OPEN_EMERGENCY_INTERFACE,
    SOS_ACTION_REQUIRES_USER,
}

data class EmergencyUiTarget(
    val kind: EmergencyUiTargetKind,
    val label: String,
)

/**
 * Conservative, deterministic matching for the system emergency entry point.
 *
 * The matcher intentionally distinguishes a button that opens the emergency interface from an
 * SOS action that could start a call or countdown. The latter is reported to the user and is
 * never clicked automatically.
 */
class EmergencyUiTargetMatcher {
    fun classify(
        text: CharSequence?,
        contentDescription: CharSequence?,
    ): EmergencyUiTarget? {
        val labels = listOfNotNull(text, contentDescription)
            .map(::normalize)
            .filter(String::isNotEmpty)

        if (labels.any { it in SOS_ACTION_LABELS }) {
            return EmergencyUiTarget(
                kind = EmergencyUiTargetKind.SOS_ACTION_REQUIRES_USER,
                label = labels.first { it in SOS_ACTION_LABELS },
            )
        }
        if (labels.any { it in OPEN_INTERFACE_LABELS }) {
            return EmergencyUiTarget(
                kind = EmergencyUiTargetKind.OPEN_EMERGENCY_INTERFACE,
                label = labels.first { it in OPEN_INTERFACE_LABELS },
            )
        }
        return null
    }

    private fun normalize(value: CharSequence): String = value
        .toString()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ")
        .trim()

    private companion object {
        /** Labels that may expose an emergency dialer or contact list, but not start SOS directly. */
        val OPEN_INTERFACE_LABELS = setOf(
            "emergency",
            "emergency call",
            "emergency dialer",
            "llamada de emergencia",
            "appel d urgence",
            "notruf",
            "notfall",
            "chamada de emergencia",
            "chamada de emergência",
            "chiamata di emergenza",
            "紧急呼叫",
            "緊急通報",
            "긴급 통화",
            "आपातकालीन कॉल",
        )

        /** These are intentionally never activated by the accessibility service. */
        val SOS_ACTION_LABELS = setOf(
            "sos",
            "emergency sos",
            "emergency services",
            "call emergency services",
            "llamar a emergencias",
            "appeler les secours",
            "notruf sos",
            "emergenza sos",
            "緊急 sos",
            "긴급 sos",
            "आपातकालीन sos",
        )
    }
}
