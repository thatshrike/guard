package com.acousticguard

import com.acousticguard.ml.ModelManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TinyModelManifestTest {
    @Test
    fun parsesAndValidatesTheStudentModelContract() {
        val manifest = ModelManifest.fromJson(
            """
            {
              "artifact": "distress_student_int8.tflite",
              "model_version": "distress-student-int8-1",
              "runtime": "tflite",
              "quantization": "int8",
              "sample_rate_hz": 16000,
              "window_ms": 1000,
              "input_contract": "logmel64x32+motion-v1",
              "outputs": ["normal", "scream", "respiratory", "impact"],
              "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            }
            """.trimIndent(),
        )

        manifest.validate(
            expectedModelVersion = "distress-student-int8-1",
            expectedRuntime = "tflite",
            expectedQuantization = "int8",
        )

        assertEquals(16_000, manifest.sampleRateHz ?: -1)
        assertEquals(1_000, manifest.windowMs ?: -1)
        assertEquals(listOf("normal", "scream", "respiratory", "impact"), manifest.outputs)
    }

    @Test
    fun rejectsAManifestWithAnInvalidDigest() {
        val manifest = ModelManifest(
            artifact = "student.tflite",
            modelVersion = "student-1",
            sha256 = "not-a-digest",
        )

        assertTrue(runCatching { manifest.validate() }.isFailure)
    }
}
