# Secure offline dispatch

The dispatch subsystem is an independent, metadata-only outbox. The monitoring service supplies
one confirmed event after its own countdown; dispatch never accepts or stores audio, extracted
features, location, or a model decision object.

## API

Call `DispatchCoordinator` from an IO dispatcher:

```kotlin
data class DispatchSettings(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val publicKeyPem: String = "",
    val bearerToken: String = "",
)

class DispatchCoordinator(context: Context) {
    fun settings(): DispatchSettings
    fun saveSettings(settings: DispatchSettings): Result<Unit>
    fun enqueue(
        marker: String,
        confidence: Float,
        modelVersion: String,
        test: Boolean = false,
    ): Result<String>
    fun snapshot(): DispatchSnapshot
    fun clear(): Result<Unit>
    fun retry()

    companion object {
        val changes: StateFlow<Long>
    }
}
```

`DispatchSnapshot.records` contains `IncidentSummary` values with the event UUID, creation time,
marker, status, and test bit. It intentionally does not expose confidence, endpoint, key, bearer
token, or any raw signal. `snapshot().error` is a safe storage/runtime error string when the
encrypted store cannot be read or a scheduling operation needs recovery. An integrity failure is
never treated as an empty outbox and is never read from a plaintext fallback.

Use the returned UUID as the local event identity. The UUID and creation timestamp are generated
once at enqueue and are immutable. `DispatchCoordinator.changes` changes after durable outbox
updates, including worker status updates.

## Configuration and opt-in

Remote delivery is explicitly opted in by saving `DispatchSettings(enabled = true, ...)` before
enqueue. A disabled configuration can be saved with empty fields so a settings screen can load and
edit it later. When enabled, the endpoint must be HTTPS, have a host, and contain no user info,
fragment, or query parameters. Endpoint length is limited to 2,048 characters.

`publicKeyPem` must be an X.509 SubjectPublicKeyInfo PEM block (`BEGIN PUBLIC KEY`, not
`BEGIN RSA PUBLIC KEY`) containing an RSA key of at least 2,048 bits. The recipient key identity is
the base64url SHA-256 digest of the DER-encoded SPKI. Bearer tokens are optional, limited to 4,096
characters, and cannot contain control characters or CR/LF header injection.

At enqueue, a remote-opted-in record captures the exact endpoint, recipient public key, key
identity, and bearer token. Changing settings never reroutes an existing record to a new endpoint
or key. A record created while dispatch is disabled is `LOCAL_ONLY` forever; enabling dispatch
later does not retroactively send it. Disabling dispatch pauses future sends for previously opted-in
records and cancels scheduled work. A request already in flight cannot be recalled; its eventual
result is recorded. Re-enabling resumes eligible previously opted-in records using their original
captured destination.

## At-rest protection and limits

The complete configuration and outbox document is encrypted under `noBackupFilesDir` with an
Android Keystore AES-256-GCM key. The file has authenticated domain-separated ciphertext and is
written through a flushed/fsynced temporary file followed by a same-directory atomic replacement.
The new file is decrypted and parsed again before a write is reported successful. There is no
SharedPreferences or plaintext fallback.

The outbox accepts at most 64 records within a 24-hour TTL. Expired records are removed before a
new enqueue, snapshot, or retry pass. If 64 unexpired records exist, enqueue fails; an unsent record
is never silently evicted. The only persisted event fields are bounded metadata and delivery state.
Markers are limited to 256 characters, model versions to 128 characters, stored error text to 512
characters, PEM keys to 16 KiB, and the encrypted store file to 4 MiB. A generated envelope and
inner payload are each bounded to 64 KiB.

Statuses are:

- `LOCAL_ONLY`: stored locally because remote delivery was not opted in at enqueue.
- `PENDING`: remote delivery was opted in and work is eligible or waiting for retry.
- `PAUSED`: remote delivery was opted in, but global dispatch is disabled.
- `SENDING`: a worker has claimed the record.
- `ACCEPTED`: the receiver returned an HTTP 2xx response.
- `FAILED`: the receiver returned a terminal error or the bounded retry budget was exhausted.
- `EXPIRED`: a worker observed the 24-hour TTL; the record is removed on the next pruning pass.

`ACCEPTED` means only that the configured HTTPS endpoint accepted the request. It is not an
emergency-service acknowledgement, dispatch, rescue, or certified emergency response.

## HTTPS dispatch and retry behavior

Each eligible event is a unique WorkManager request named by its event UUID, constrained to a
connected network. The platform's standard certificate and hostname verification are retained;
the sender installs no trust-all verifier or custom trust store. Redirects are disabled. Connect
and read timeouts are 10 seconds, request bodies are bounded to 64 KiB, and response bodies are
read only up to 16 KiB and never persisted or logged. The request uses:

- `POST` with `Content-Type: application/json`;
- `Idempotency-Key: <event UUID>`;
- `Authorization: Bearer <token>` only when a token was configured.

Network I/O failures, HTTP 408, HTTP 429, and HTTP 5xx are retryable. Other 4xx responses and all
3xx redirects are terminal failures. The durable retry budget is five attempts with exponential
backoff starting at 30 seconds and capped at six hours; WorkManager also applies exponential
backoff. Records expire after 24 hours regardless of retry state. A coordinator constructed at app
launch calls `retry()` to recover pending work. Workers use the repository directly and do not
construct a coordinator, avoiding initialization recursion.

`clear()` durably removes all records (while preserving the saved settings) before canceling tagged WorkManager work. A racing worker
re-reads the durable record before every state transition, so a deleted record cannot be rewritten.
If a request was already on the wire, the endpoint may still receive it; the receiver must use the
idempotency key to deduplicate it.

## End-to-end envelope

The HTTPS body is a versioned JSON envelope, currently:

```json
{
  "v": 1,
  "alg": "A256GCM+RSA-OAEP-256",
  "event_id": "<UUID>",
  "key_id": "<base64url SHA-256 SPKI digest>",
  "wrapped_key": "<base64url RSA-OAEP ciphertext>",
  "iv": "<base64url 12-byte AES-GCM nonce>",
  "ciphertext": "<base64url AES-GCM ciphertext plus 128-bit tag>"
}
```

The inner plaintext JSON contains only `v`, `event_id`, `created_at_epoch_ms`, `marker`,
`confidence`, `model_version`, `test`, and `recipient_key_id`. The inner bytes are encrypted with
a fresh AES-256-GCM key. That key is wrapped with RSA-OAEP using SHA-256 for both the OAEP digest
and MGF1, with explicit parameters. AES-GCM authenticates the domain-separated value
`com.acousticguard.dispatch.envelope.v1|<event UUID>|<key id>` as AAD. Tampering or a recipient-key
mismatch fails closed.

## User-owned receiver requirements

The receiver owns the private RSA key corresponding to the configured SPKI public key. It should:

1. Require HTTPS and authenticate the caller according to the configured bearer token policy.
2. Reject unknown envelope versions/algorithms and verify `key_id` against the active private key's
   SPKI SHA-256 digest.
3. RSA-OAEP-decrypt `wrapped_key` with SHA-256/MGF1-SHA-256, then AES-256-GCM-decrypt
   `ciphertext` with the 12-byte IV, 128-bit tag, and the exact AAD above.
4. Verify the inner `event_id` matches both the envelope and `Idempotency-Key`, and validate the
   bounded metadata fields.
5. Deduplicate by event UUID (and retain the result for at least the 24-hour sender TTL). A
   duplicate request must return the same accepted outcome without creating a second incident.
6. Persist the accepted event durably before returning HTTP 2xx. The 2xx response is an endpoint
   acceptance acknowledgement only; it is not proof of emergency delivery.

The sender does not interpret the response body, so receiver response bodies should remain small.
Do not put secrets or sensitive details in URLs, redirects, response bodies, or logs.

## Required integration

The application module must provide:

- `androidx.work:work-runtime-ktx:2.10.1`;
- an `org.json` implementation for JVM tests if the test runtime does not already provide it.

The application manifest must also declare `android.permission.INTERNET` for optional HTTPS
delivery. It is a normal install-time permission and does not require a runtime prompt. No extra
worker or dispatch service declaration is required.

WorkManager's normal AndroidX initialization is used; no manifest service entry is required for the
worker. The caller should replace the old decision-object enqueue path with
`enqueue(marker, confidence, modelVersion, test)` only after its confirmation/countdown policy,
call `retry()` at app launch if it keeps a long-lived coordinator, and should not call the
coordinator with audio, features, or location. The subsystem does not create notification channels,
post notifications, or update monitoring status; the application owns those UI/status concerns.
