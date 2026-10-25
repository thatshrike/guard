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

    @Test
    fun studentContractRequiresDatasetCalibrationAndLimitationsMetadata() {
        val manifest = ModelManifest(
            artifact = "distress_student_int8.tflite",
            modelVersion = "distress-student-int8-1",
            sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            runtime = "tflite",
            quantization = "int8",
            sampleRateHz = 16_000,
            windowMs = 1_000,
            inputContract = "logmel64x32+motion-v1",
            preprocessingVersion = "logmel64x32+motion-v1",
            datasetRevision = "dataset-test-1",
            calibrationRevision = "calibration-test-1",
            license = "internal",
            limitations = "Not validated for emergency response.",
            thresholdPolicyRevision = "fusion-v1",
            outputs = listOf("normal", "scream", "respiratory", "impact"),
            intendedUse = "Controlled validation only.",
        )

        manifest.validateStudentContract(
            expectedModelVersion = "distress-student-int8-1",
            expectedRuntime = "tflite",
            expectedQuantization = "int8",
            expectedSampleRateHz = 16_000,
            expectedWindowMs = 1_000,
            expectedInputContract = "logmel64x32+motion-v1",
            expectedOutputs = listOf("normal", "scream", "respiratory", "impact"),
        )

        assertTrue(
            runCatching {
                manifest.copy(datasetRevision = null).validateStudentContract(
                    expectedModelVersion = "distress-student-int8-1",
                    expectedRuntime = "tflite",
                    expectedQuantization = "int8",
                    expectedSampleRateHz = 16_000,
                    expectedWindowMs = 1_000,
                    expectedInputContract = "logmel64x32+motion-v1",
                    expectedOutputs = listOf("normal", "scream", "respiratory", "impact"),
                )
            }.isFailure,
        )
    }
}
