# AcousticGuard

AcousticGuard is a native Android application for zero-touch, on-device distress-pattern detection. It processes short microphone windows in RAM, runs a bundled pretrained YAMNet TensorFlow Lite model locally, correlates audio labels with inertial motion, and requires repeated corroboration before starting a visible cancellation countdown.

The repository is now compile-ready: it includes the Gradle wrapper, Android SDK configuration, the on-device model asset, unit tests, lint configuration, encrypted outbox, optional user-owned HTTPS receiver, and a debug APK build path.

## Implemented product flow

1. Open AcousticGuard and review the privacy explanation.
2. Enable monitoring and grant microphone + notification permissions.
3. The app starts a microphone foreground service with an ongoing status notification.
4. YAMNet classifies the short audio window locally. Only scalar label scores and DSP features leave the audio buffer.
5. Motion corroboration and `DistressFusionEngine` require repeated qualifying windows.
6. A 10-second visible notification countdown appears with a **Cancel** action.
7. If not cancelled, only bounded metadata is written to the encrypted Android Keystore-backed outbox.
8. Remote delivery is optional and off by default. When explicitly enabled, the app encrypts the metadata for a user-owned HTTPS receiver and retries through WorkManager.

## Machine-learning implementation

- `app/src/main/assets/yamnet.tflite` is the official TensorFlow Lite Task Audio YAMNet AudioSet classifier artifact.
- `YamnetDistressClassifier.kt` invokes the model on-device through `tensorflow-lite-task-audio`.
- `YamnetLabelMapper.kt` allow-lists the AudioSet labels relevant to this product: screaming/shouting, breathing/gasping, and acoustic impact labels.
- `AudioSignalProcessor.kt` adds loudness, high-frequency energy, spectral flux, crest factor, silence, and envelope features.
- `MotionFeatureTracker.kt` derives inertial energy and jerk from the accelerometer.
- `DistressFusionEngine.kt` is fail-closed: a single sound is never enough, impact requires inertial jerk, and confirmations are rate-limited.

Model provenance and the bundled SHA-256 are recorded in `app/src/main/assets/yamnet_model_manifest.json`. See `docs/MODEL.md` for replacement and validation guidance.

## Privacy and storage guarantees

- Raw PCM is held only in a bounded in-memory window and cleared after inference.
- Raw audio is never logged, persisted, uploaded, or included in an incident record.
- The encrypted dispatch store is kept under `noBackupFilesDir` and uses Android Keystore AES-256-GCM.
- Android backup/device-transfer rules exclude application data.
- Cleartext network traffic is disabled; the optional transport accepts HTTPS only, disables redirects, validates the receiver public key, and uses platform TLS verification.
- Remote delivery is opt-in. Events generated while remote delivery is disabled remain local-only and are never retroactively rerouted.

## Project layout

```text
app/src/main/java/com/acousticguard/
├── dispatch/       Encrypted outbox, hybrid envelope, WorkManager transport
├── ml/             YAMNet adapter, label mapping, DSP features, fusion policy
├── monitoring/     Foreground microphone service, countdown, motion tracking
└── ui/             Compose screens, permissions, dispatch settings, theme tokens
```

## Build from a clean machine

Requirements:

- JDK 17
- Android SDK Platform 35
- Android Build Tools 35.0.0
- Android Studio with the Android SDK location configured

From the repository root:

```bash
./gradlew test
./gradlew lintDebug
./gradlew assembleDebug
./gradlew assembleRelease
```

The installable debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install on a connected device or emulator with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build is signed with the standard debug key. A release build still needs your production keystore and signing configuration; no private signing material is included in the repository.

`assembleRelease` produces `app/build/outputs/apk/release/app-release-unsigned.apk` with R8 and resource shrinking enabled. Add your own `signingConfigs.release` credentials outside source control before distributing it.

## Optional remote receiver

Settings supports an explicit user-owned receiver configuration:

- HTTPS endpoint without query parameters, fragments, or user info
- X.509 `BEGIN PUBLIC KEY` RSA public key, at least 2048 bits
- Optional bearer token

The receiver must decrypt the documented envelope and deduplicate by `Idempotency-Key`. A 2xx response means only that the configured receiver accepted the encrypted request; it is not proof of emergency-service delivery. The complete receiver contract is in `docs/DISPATCH.md`.

## Safety boundary

This project is compile-ready and implements real on-device ML, but the bundled YAMNet model is a general AudioSet classifier, not a clinically validated distress detector. It must not be represented as a certified emergency-response system without a consented representative dataset, device/battery testing, threshold calibration, false-positive/false-negative evaluation, abuse testing, privacy/legal review, and an independent safety case. The app intentionally does not silently contact emergency services or claim that an alert reached them.
