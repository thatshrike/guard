package com.acousticguard.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyUiTargetMatcherTest {
    private val matcher = EmergencyUiTargetMatcher()

    @Test
    fun emergencyEntryIsSafeToOpenButSosIsAlwaysLeftForTheUser() {
        assertEquals(
            EmergencyUiTargetKind.OPEN_EMERGENCY_INTERFACE,
            matcher.classify("Emergency", null)?.kind,
        )
        assertEquals(
            EmergencyUiTargetKind.SOS_ACTION_REQUIRES_USER,
            matcher.classify("Emergency SOS", null)?.kind,
        )
    }

    @Test
    fun contentDescriptionsAndLocalizedLabelsAreSupported() {
        assertEquals(
            EmergencyUiTargetKind.OPEN_EMERGENCY_INTERFACE,
            matcher.classify(null, "  Emergency   Call ")?.kind,
        )
        assertEquals(
            EmergencyUiTargetKind.OPEN_EMERGENCY_INTERFACE,
            matcher.classify("Notruf", null)?.kind,
        )
        assertEquals(
            EmergencyUiTargetKind.OPEN_EMERGENCY_INTERFACE,
            matcher.classify("आपातकालीन कॉल", null)?.kind,
        )
    }

    @Test
    fun unrelatedLabelsDoNotTriggerTheHandoffMatcher() {
        assertNull(matcher.classify("Power off", "Restart"))
        assertTrue(matcher.classify("Emergency SOS", "Call") != null)
    }
}
