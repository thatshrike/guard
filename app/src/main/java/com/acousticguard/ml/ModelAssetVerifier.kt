package com.acousticguard.ml

import android.content.Context
import java.security.MessageDigest
import org.json.JSONObject

/** Verifies that the model packaged in the APK matches the recorded artifact manifest. */
object ModelAssetVerifier {
    fun verify(
        context: Context,
        modelAssetName: String,
        manifestAssetName: String,
        expectedModelVersion: String,
    ) {
        val manifest = context.assets.open(manifestAssetName).use { input ->
            JSONObject(input.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
        require(manifest.optString("model_version") == expectedModelVersion) {
            "The packaged model version does not match the classifier contract."
        }
        require(manifest.optString("artifact") == modelAssetName) {
            "The packaged model manifest points to a different asset."
        }
        val expectedHash = manifest.optString("sha256")
        require(expectedHash.matches(SHA256_HEX)) {
            "The packaged model manifest contains an invalid SHA-256 digest."
        }

        val actualHash = context.assets.open(modelAssetName).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
        require(actualHash.equals(expectedHash, ignoreCase = true)) {
            "The packaged model failed its integrity check."
        }
    }

    private const val BUFFER_BYTES = 64 * 1_024
    private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
}
