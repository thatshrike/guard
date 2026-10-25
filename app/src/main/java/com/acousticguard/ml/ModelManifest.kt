package com.acousticguard.ml

import org.json.JSONObject

/** The integrity and input/output contract recorded next to a packaged model asset. */
data class ModelManifest(
    val artifact: String,
    val modelVersion: String,
    val sha256: String,
    val runtime: String? = null,
    val quantization: String? = null,
    val sampleRateHz: Int? = null,
    val windowMs: Int? = null,
    val inputContract: String? = null,
    val preprocessingVersion: String? = null,
    val datasetRevision: String? = null,
    val calibrationRevision: String? = null,
    val license: String? = null,
    val limitations: String? = null,
    val thresholdPolicyRevision: String? = null,
    val outputs: List<String> = emptyList(),
    val intendedUse: String = "",
) {
    fun validate(
        expectedModelVersion: String? = null,
        expectedRuntime: String? = null,
        expectedQuantization: String? = null,
    ) {
        require(artifact.isNotBlank()) { "The model manifest does not name an artifact." }
        require(modelVersion.isNotBlank()) { "The model manifest does not name a model version." }
        require(sha256.matches(SHA256_HEX)) {
            "The model manifest contains an invalid SHA-256 digest."
        }
        if (expectedModelVersion != null) {
            require(modelVersion == expectedModelVersion) {
                "The packaged model version does not match the classifier contract."
            }
        }
        if (expectedRuntime != null) {
            require(runtime == expectedRuntime) {
                "The packaged model runtime does not match the classifier contract."
            }
        }
        if (expectedQuantization != null) {
            require(quantization == expectedQuantization) {
                "The packaged model quantization does not match the classifier contract."
            }
        }
    }

    fun validateStudentContract(
        expectedModelVersion: String,
        expectedRuntime: String,
        expectedQuantization: String,
        expectedSampleRateHz: Int,
        expectedWindowMs: Int,
        expectedInputContract: String,
        expectedOutputs: List<String>,
    ) {
        validate(
            expectedModelVersion = expectedModelVersion,
            expectedRuntime = expectedRuntime,
            expectedQuantization = expectedQuantization,
        )
        require(sampleRateHz == expectedSampleRateHz) {
            "The packaged model sample-rate contract is unsupported."
        }
        require(windowMs == expectedWindowMs) {
            "The packaged model window contract is unsupported."
        }
        require(inputContract == expectedInputContract) {
            "The packaged model input contract is unsupported."
        }
        require(outputs == expectedOutputs) {
            "The packaged model output labels do not match the classifier contract."
        }
        require(!preprocessingVersion.isNullOrBlank()) {
            "The student model manifest is missing its preprocessing revision."
        }
        require(!datasetRevision.isNullOrBlank()) {
            "The student model manifest is missing its dataset revision."
        }
        require(!calibrationRevision.isNullOrBlank()) {
            "The student model manifest is missing its calibration revision."
        }
        require(!license.isNullOrBlank()) {
            "The student model manifest is missing its license."
        }
        require(!limitations.isNullOrBlank()) {
            "The student model manifest is missing its limitations."
        }
        require(!thresholdPolicyRevision.isNullOrBlank()) {
            "The student model manifest is missing its threshold-policy revision."
        }
        require(intendedUse.isNotBlank()) {
            "The student model manifest is missing its intended use."
        }
    }

    companion object {
        fun fromJson(jsonText: String): ModelManifest {
            val json = JSONObject(jsonText)
            val outputs = buildList {
                val array = json.optJSONArray("outputs") ?: return@buildList
                for (index in 0 until array.length()) {
                    val output = array.optString(index).takeIf { it.isNotBlank() }
                    if (output != null) add(output)
                }
            }
            return ModelManifest(
                artifact = json.optString("artifact"),
                modelVersion = json.optString("model_version"),
                sha256 = json.optString("sha256"),
                runtime = json.optString("runtime").takeIf { it.isNotBlank() },
                quantization = json.optString("quantization").takeIf { it.isNotBlank() },
                sampleRateHz = json.optionalInt("sample_rate_hz"),
                windowMs = json.optionalInt("window_ms"),
                inputContract = json.optString("input_contract").takeIf { it.isNotBlank() },
                preprocessingVersion = json.optString("preprocessing_version").takeIf { it.isNotBlank() },
                datasetRevision = json.optString("dataset_revision").takeIf { it.isNotBlank() },
                calibrationRevision = json.optString("calibration_revision").takeIf { it.isNotBlank() },
                license = json.optString("license").takeIf { it.isNotBlank() },
                limitations = json.optString("limitations").takeIf { it.isNotBlank() },
                thresholdPolicyRevision = json.optString("threshold_policy_revision")
                    .takeIf { it.isNotBlank() },
                outputs = outputs,
                intendedUse = json.optString("intended_use"),
            )
        }

        private fun JSONObject.optionalInt(name: String): Int? =
            if (has(name) && !isNull(name)) optInt(name) else null

        private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
    }
}
