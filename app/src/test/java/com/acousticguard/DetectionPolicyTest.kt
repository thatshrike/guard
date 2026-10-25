package com.acousticguard

import com.acousticguard.ml.DetectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionPolicyTest {
    @Test
    fun bundledPolicyContractHasAStableDigest() {
        val json = """
            {
              "policy_version": "fusion-v1",
              "scream_threshold": 0.72,
              "respiratory_threshold": 0.80,
              "impact_threshold": 0.74,
              "impact_jerk_threshold": 0.42,
              "uncertainty_threshold": 0.85,
              "audio_quality_threshold": 0.10,
              "required_consecutive_windows": 3,
              "confirmation_window_ms": 8000,
              "cooldown_ms": 60000,
              "sha256": "43d8a0f326727a5c93e99813fe648f3487afc8b45d62036bada7a9399fb4b7e2"
            }
        """.trimIndent()

        val policy = DetectionPolicy.fromJson(json)

        assertEquals(DetectionPolicy.CURRENT, policy)
        assertEquals("43d8a0f326727a5c93e99813fe648f3487afc8b45d62036bada7a9399fb4b7e2", policy.sha256())
    }

    @Test
    fun tamperingWithThresholdsInvalidatesThePolicy() {
        val json = """
            {
              "policy_version": "fusion-v1",
              "scream_threshold": 0.71,
              "respiratory_threshold": 0.80,
              "impact_threshold": 0.74,
              "impact_jerk_threshold": 0.42,
              "uncertainty_threshold": 0.85,
              "audio_quality_threshold": 0.10,
              "required_consecutive_windows": 3,
              "confirmation_window_ms": 8000,
              "cooldown_ms": 60000,
              "sha256": "43d8a0f326727a5c93e99813fe648f3487afc8b45d62036bada7a9399fb4b7e2"
            }
        """.trimIndent()

        assertTrue(runCatching { DetectionPolicy.fromJson(json) }.isFailure)
    }
}
