package com.acousticguard.ml

import android.content.Context
import java.security.MessageDigest

/** Verifies that the model packaged in the APK matches the recorded artifact manifest. */
object ModelAssetVerifier {
    fun readManifest(context: Context, manifestAssetName: String): ModelManifest =
        context.assets.open(manifestAssetName).use { input ->
            ModelManifest.fromJson(input.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }

    fun verify(
        context: Context,
        modelAssetName: String,
        manifestAssetName: String,
        expectedModelVersion: String? = null,
        expectedRuntime: String? = null,
        expectedQuantization: String? = null,
    ): ModelManifest {
        val manifest = readManifest(context, manifestAssetName)
        require(manifest.artifact == modelAssetName) {
            "The packaged model manifest points to a different asset."
        }
        manifest.validate(
            expectedModelVersion = expectedModelVersion,
            expectedRuntime = expectedRuntime,
            expectedQuantization = expectedQuantization,
        )

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
        require(actualHash.equals(manifest.sha256, ignoreCase = true)) {
            "The packaged model failed its integrity check."
        }
        return manifest
    }

    private const val BUFFER_BYTES = 64 * 1_024
}
