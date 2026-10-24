package com.acousticguard.dispatch

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Encrypted, metadata-only persistence for the dispatch configuration and outbox.
 *
 * The file is kept under noBackupFilesDir, never uses SharedPreferences, and is replaced by a
 * same-directory atomic move after the temporary file has been flushed and fsynced. Every write
 * is decrypted and parsed again before it is reported as successful.
 */
class EncryptedIncidentQueue(private val context: Context) {
    private val appContext = context.applicationContext

    internal fun load(): Result<QueueState> = synchronized(DispatchRepositoryLock.monitor) {
        loadLocked()
    }

    internal fun save(state: QueueState): Result<Unit> = synchronized(DispatchRepositoryLock.monitor) {
        saveLocked(state)
    }

    internal fun loadLocked(): Result<QueueState> {
        val target = storeFile()
        if (!target.exists()) return Result.success(QueueState())
        return runCatching {
            if (target.length() > MAX_STORE_BYTES) throw DispatchStorageException(STORAGE_ERROR)
            decode(target.readBytes())
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(storageFailure(it)) },
        )
    }

    internal fun saveLocked(state: QueueState): Result<Unit> {
        return runCatching {
            validateState(state)
            val target = storeFile()
            val parent = target.parentFile ?: throw DispatchStorageException(STORAGE_ERROR)
            if (!parent.exists() && !parent.mkdirs()) throw DispatchStorageException(STORAGE_ERROR)

            val temporary = File(parent, "${target.name}.tmp")
            val encoded = encode(state)
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(encoded)
                    output.flush()
                    output.fd.sync()
                }
                atomicReplace(temporary, target)
                if (target.length() > MAX_STORE_BYTES || decode(target.readBytes()) != state) {
                    throw DispatchStorageException(STORAGE_ERROR)
                }
            } finally {
                temporary.delete()
            }
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(storageFailure(it)) },
        )
    }

    private fun storeFile(): File = File(appContext.noBackupFilesDir, STORE_FILE_NAME)

    private fun encode(state: QueueState): ByteArray {
        val plaintext = state.toJson().toString().toByteArray(Charsets.UTF_8)
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(STORE_AAD)
        val ciphertext = cipher.doFinal(plaintext)

        return ByteArrayOutputStream(iv.size + ciphertext.size + 16).use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(STORE_MAGIC)
                output.writeByte(iv.size)
                output.write(iv)
                output.write(ciphertext)
            }
            buffer.toByteArray()
        }
    }

    private fun decode(encoded: ByteArray): QueueState {
        if (encoded.size < STORE_HEADER_BYTES + GCM_TAG_BYTES) throw DispatchStorageException(STORAGE_ERROR)
        val input = DataInputStream(ByteArrayInputStream(encoded))
        if (input.readInt() != STORE_MAGIC) throw DispatchStorageException(STORAGE_ERROR)
        val ivLength = input.readUnsignedByte()
        if (ivLength !in 12..16) throw DispatchStorageException(STORAGE_ERROR)
        val iv = ByteArray(ivLength)
        input.readFully(iv)
        val ciphertext = input.readBytes()
        if (ciphertext.size < GCM_TAG_BYTES || ciphertext.size > MAX_STORE_BYTES) {
            throw DispatchStorageException(STORAGE_ERROR)
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(STORE_AAD)
        val plaintext = cipher.doFinal(ciphertext)
        if (plaintext.size > MAX_PLAINTEXT_BYTES) throw DispatchStorageException(STORAGE_ERROR)
        return queueStateFromJson(JSONObject(String(plaintext, Charsets.UTF_8)))
    }

    private fun atomicReplace(temporary: File, target: File) {
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // File.renameTo is atomic for a same-directory rename on Android filesystems.
            if (!temporary.renameTo(target)) throw DispatchStorageException(STORAGE_ERROR)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun validateState(state: QueueState) {
        if (state.records.size > MAX_OUTBOX_RECORDS) throw DispatchStorageException(STORAGE_ERROR)
        if (DispatchValidation.validateSettings(state.settings).isFailure) {
            throw DispatchStorageException(STORAGE_ERROR)
        }
        val ids = HashSet<String>(state.records.size)
        state.records.forEach { record ->
            if (!ids.add(record.id)) throw DispatchStorageException(STORAGE_ERROR)
            runCatching { java.util.UUID.fromString(record.id) }.getOrElse {
                throw DispatchStorageException(STORAGE_ERROR)
            }
            if (record.createdAtEpochMs < 0L || record.marker.isBlank() || record.marker.length > MAX_MARKER_LENGTH) {
                throw DispatchStorageException(STORAGE_ERROR)
            }
            if (!record.confidence.isFinite() || record.confidence !in 0f..1f) {
                throw DispatchStorageException(STORAGE_ERROR)
            }
            if (record.modelVersion.isBlank() || record.modelVersion.length > MAX_MODEL_VERSION_LENGTH) {
                throw DispatchStorageException(STORAGE_ERROR)
            }
            if (record.status !in DispatchStatus.all || record.attemptCount < 0 || record.attemptCount > DispatchPolicy.MAX_ATTEMPTS) {
                throw DispatchStorageException(STORAGE_ERROR)
            }
            if (record.endpoint.length > MAX_ENDPOINT_LENGTH ||
                record.publicKeyPem.length > MAX_PUBLIC_KEY_PEM_LENGTH ||
                record.bearerToken.length > MAX_BEARER_TOKEN_LENGTH ||
                record.recipientKeyId.length > 128 ||
                record.lastError.orEmpty().length > MAX_ERROR_LENGTH
            ) {
                throw DispatchStorageException(STORAGE_ERROR)
            }
            if (record.remoteOptIn) {
                if (record.endpoint.isEmpty() || record.publicKeyPem.isEmpty() || record.recipientKeyId.isEmpty()) {
                    throw DispatchStorageException(STORAGE_ERROR)
                }
                val validated = DispatchValidation.validateSettings(
                    DispatchSettings(
                        enabled = true,
                        endpoint = record.endpoint,
                        publicKeyPem = record.publicKeyPem,
                        bearerToken = record.bearerToken,
                    ),
                )
                if (validated.isFailure || validated.getOrThrow().recipientKeyId != record.recipientKeyId) {
                    throw DispatchStorageException(STORAGE_ERROR)
                }
            }
        }
    }

    private fun storageFailure(failure: Throwable): DispatchStorageException =
        if (failure is DispatchStorageException) failure else DispatchStorageException(STORAGE_ERROR)

    private companion object {
        const val STORE_FILE_NAME = "acoustic_guard_dispatch_v2.enc"
        const val KEY_ALIAS = "acoustic_guard_dispatch_store_v2"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val STORE_MAGIC = 0x41474432 // AGD2; no metadata is stored in the clear.
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val STORE_HEADER_BYTES = Int.SIZE_BYTES + 1 + GCM_IV_BYTES
        const val MAX_STORE_BYTES = 4 * 1_024 * 1_024
        const val MAX_PLAINTEXT_BYTES = 2 * 1_024 * 1_024
        val STORE_AAD = "com.acousticguard.dispatch.store.v2".toByteArray(Charsets.UTF_8)
        const val STORAGE_ERROR = "Encrypted dispatch storage is unavailable."
    }
}

private fun QueueState.toJson(): JSONObject = JSONObject().apply {
    put("version", 2)
    put(
        "settings",
        JSONObject().apply {
            put("enabled", settings.enabled)
            put("endpoint", settings.endpoint)
            put("public_key_pem", settings.publicKeyPem)
            put("bearer_token", settings.bearerToken)
        },
    )
    put(
        "records",
        JSONArray().apply {
            records.forEach { record ->
                put(
                    JSONObject().apply {
                        put("id", record.id)
                        put("created_at_epoch_ms", record.createdAtEpochMs)
                        put("marker", record.marker)
                        put("confidence", record.confidence.toDouble())
                        put("model_version", record.modelVersion)
                        put("test", record.test)
                        put("status", record.status)
                        put("remote_opt_in", record.remoteOptIn)
                        put("endpoint", record.endpoint)
                        put("public_key_pem", record.publicKeyPem)
                        put("recipient_key_id", record.recipientKeyId)
                        put("bearer_token", record.bearerToken)
                        put("attempt_count", record.attemptCount)
                        put("next_attempt_at_epoch_ms", record.nextAttemptAtEpochMs)
                        put("sending_started_at_epoch_ms", record.sendingStartedAtEpochMs)
                        put("last_error", record.lastError ?: JSONObject.NULL)
                    },
                )
            }
        },
    )
}

private fun queueStateFromJson(json: JSONObject): QueueState {
    if (json.getInt("version") != 2) throw DispatchStorageException("Encrypted dispatch storage is unavailable.")
    val settingsJson = json.getJSONObject("settings")
    val settings = DispatchSettings(
        enabled = settingsJson.getBoolean("enabled"),
        endpoint = settingsJson.getString("endpoint"),
        publicKeyPem = settingsJson.getString("public_key_pem"),
        bearerToken = settingsJson.getString("bearer_token"),
    )
    if (DispatchValidation.validateSettings(settings).isFailure) {
        throw DispatchStorageException("Encrypted dispatch storage is unavailable.")
    }

    val recordsJson = json.getJSONArray("records")
    if (recordsJson.length() > MAX_OUTBOX_RECORDS) throw DispatchStorageException("Encrypted dispatch storage is unavailable.")
    val records = buildList(recordsJson.length()) {
        for (index in 0 until recordsJson.length()) {
            val recordJson = recordsJson.getJSONObject(index)
            val lastError = if (recordJson.isNull("last_error")) null else recordJson.getString("last_error")
            add(
                StoredIncident(
                    id = recordJson.getString("id"),
                    createdAtEpochMs = recordJson.getLong("created_at_epoch_ms"),
                    marker = recordJson.getString("marker"),
                    confidence = recordJson.getDouble("confidence").toFloat(),
                    modelVersion = recordJson.getString("model_version"),
                    test = recordJson.getBoolean("test"),
                    status = recordJson.getString("status"),
                    remoteOptIn = recordJson.getBoolean("remote_opt_in"),
                    endpoint = recordJson.getString("endpoint"),
                    publicKeyPem = recordJson.getString("public_key_pem"),
                    recipientKeyId = recordJson.getString("recipient_key_id"),
                    bearerToken = recordJson.getString("bearer_token"),
                    attemptCount = recordJson.getInt("attempt_count"),
                    nextAttemptAtEpochMs = recordJson.getLong("next_attempt_at_epoch_ms"),
                    sendingStartedAtEpochMs = recordJson.getLong("sending_started_at_epoch_ms"),
                    lastError = lastError,
                ),
            )
        }
    }
    val state = QueueState(settings = settings, records = records)
    // Re-run all structural and cryptographic identity checks after parsing.
    validateParsedState(state)
    return state
}

private fun validateParsedState(state: QueueState) {
    if (state.records.size > MAX_OUTBOX_RECORDS) throw DispatchStorageException("Encrypted dispatch storage is unavailable.")
    val ids = HashSet<String>(state.records.size)
    state.records.forEach { record ->
        if (!ids.add(record.id)) throw DispatchStorageException("Encrypted dispatch storage is unavailable.")
        runCatching { java.util.UUID.fromString(record.id) }.getOrElse {
            throw DispatchStorageException("Encrypted dispatch storage is unavailable.")
        }
        if (record.createdAtEpochMs < 0L || record.marker.isBlank() || record.marker.length > MAX_MARKER_LENGTH ||
            record.marker.any { Character.isISOControl(it) } || !record.confidence.isFinite() || record.confidence !in 0f..1f ||
            record.modelVersion.isBlank() || record.modelVersion.length > MAX_MODEL_VERSION_LENGTH ||
            record.modelVersion.any { Character.isISOControl(it) } || record.status !in DispatchStatus.all ||
            record.attemptCount !in 0..DispatchPolicy.MAX_ATTEMPTS || record.endpoint.length > MAX_ENDPOINT_LENGTH ||
            record.publicKeyPem.length > MAX_PUBLIC_KEY_PEM_LENGTH || record.bearerToken.length > MAX_BEARER_TOKEN_LENGTH ||
            record.bearerToken.any { it == '\r' || it == '\n' || Character.isISOControl(it) } || record.recipientKeyId.length > 128 ||
            record.lastError.orEmpty().length > MAX_ERROR_LENGTH
        ) {
            throw DispatchStorageException("Encrypted dispatch storage is unavailable.")
        }
        if (record.remoteOptIn) {
            if (record.endpoint.isEmpty() || record.publicKeyPem.isEmpty() || record.recipientKeyId.isEmpty()) {
                throw DispatchStorageException("Encrypted dispatch storage is unavailable.")
            }
            val validated = DispatchValidation.validateSettings(
                DispatchSettings(true, record.endpoint, record.publicKeyPem, record.bearerToken),
            ).getOrElse { throw DispatchStorageException("Encrypted dispatch storage is unavailable.") }
            if (validated.recipientKeyId != record.recipientKeyId) {
                throw DispatchStorageException("Encrypted dispatch storage is unavailable.")
            }
        }
    }
}
