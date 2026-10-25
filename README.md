# AcousticGuard

AcousticGuard is a native Android application for zero-touch, on-device distress-pattern detection. It processes short microphone windows in RAM, uses cheap DSP and motion features to gate a small local model, correlates scores with inertial motion, and requires repeated corroboration before starting a visible cancellation countdown.

The repository contains the Android prototype, Gradle wrapper, unit tests, lint configuration,
encrypted outbox, optional user-owned HTTPS receiver, and a debug APK build path. The bundled
YAMNet artifact is available; the domain-specific student model remains a required external ML
deliverable and is intentionally not fabricated in source control.

## Implemented product flow

1. Open AcousticGuard and review the privacy explanation.
2. Enable monitoring and grant microphone + notification permissions.
3. The app starts a microphone foreground service with an ongoing status notification.
4. A low-cost DSP/motion gate decides whether the model needs to run. When it does, the active classifier processes the short audio window locally. Only scalar scores and features leave the audio buffer.
5. The local detector recognizes scream/shout and loud speech acoustically, so shouted distress
   in English, Hindi, or another language follows the same language-independent path. Motion is
   still used for impact corroboration, while vocal distress can trigger with the phone still.
6. `DistressFusionEngine` requires repeated qualifying windows.
7. A 10-second visible notification countdown appears with a **Cancel** action.
8. If not cancelled, only bounded metadata is written to the encrypted Android Keystore-backed outbox.
9. Remote delivery is optional and off by default. When explicitly enabled, the app encrypts the metadata for a user-owned HTTPS receiver and retries through WorkManager.

## Machine-learning implementation

- `ModelSelector.kt` prefers the optional domain-specific INT8 TinyML model and falls back to the verified YAMNet AudioSet artifact when the student asset is absent or fails its manifest/integrity contract.
- `TinyDistressClassifier.kt` defines the `logmel64x32+motion-v1` input contract, requires fixed full-integer INT8 tensors, and uses one TFLite interpreter thread. The trained student model is intentionally not fabricated in source control; it must come from the private, reviewed training pipeline before it can become the primary model.
- `app/src/main/assets/yamnet.tflite` is the verified TensorFlow Lite Task Audio YAMNet AudioSet fallback artifact.
- `YamnetDistressClassifier.kt` invokes YAMNet on-device through `tensorflow-lite-task-audio`.
- `YamnetLabelMapper.kt` allow-lists the AudioSet labels relevant to this product: screaming/shouting, breathing/gasping, and acoustic impact labels.
- `AudioSignalProcessor.kt` adds loudness, high-frequency energy, spectral flux, crest factor, silence, and envelope features.
- `InferenceScheduler.kt` skips most quiet-state model calls while retaining a periodic quiet scan and an uncertainty-triggered retry path.
- `DetectionMetricsTracker.kt` keeps only aggregate in-memory counters and latency percentiles for debug/operational visibility.
- `DetectionPipeline.kt` separates scheduling, feature extraction, model invocation, and temporal fusion from the Android service; `DetectionClock` and `ReplayAudioWindowSource` support deterministic replay tests.
- `detection_policy_v1.json` is a SHA-verified, versioned temporal policy artifact consumed by the fusion engine.
- `MotionFeatureTracker.kt` derives inertial energy and jerk from the accelerometer.
- `DistressFusionEngine.kt` is fail-closed: a single sound is never enough, impact requires inertial jerk, and confirmations are rate-limited.

Model provenance and the bundled SHA-256 are recorded in `app/src/main/assets/yamnet_model_manifest.json`. The expected student-model manifest is documented in `docs/MODEL.md`; model selection fails visibly if neither model can load. See that document for replacement, calibration, and fallback guidance.

The repeatable power and latency comparison plan is in `docs/BATTERY_BENCHMARK.md`.

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
├── ml/             TinyML/YAMNet adapters, manifests, DSP features, fusion policy
├── monitoring/     Foreground microphone service, countdown, motion tracking
└── ui/             Compose screens, permissions, dispatch settings, theme tokens
```

## Build from a clean machine

Requirements:

- JDK 17 or JDK 27 (the wrapper is Gradle 9.8.2; Android/Kotlin compilation still targets Java 17)
- Android SDK Platform 35
- Android Build Tools 35.0.0
- Android Studio with the Android SDK location configured

The repository guard rejects unsupported Java majors with a clear error. On Windows, select the
installed JDK explicitly before invoking the wrapper, for example:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-27.0.1.0.3'
```

For the full `lintDebug` check, use JDK 17 with the current AGP 8.7.3 lint engine. JDK 27 works
for Gradle configuration, unit tests, and APK assembly, but this lint engine's Kotlin analysis
parser still rejects the Java 27 version string.

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

## Battery and model-validation boundary

The scheduler and motion policy are instrumentation foundations, not proof of a battery target. Record device-specific battery, CPU, thermal, latency, dropped-window, false-alert, and missed-event measurements before changing the production model or thresholds. Do not enable shadow YAMNet plus student inference continuously in a normal build because that doubles model work.

## Safety boundary

This project is compile-ready and implements real on-device ML, but the bundled YAMNet model is a general AudioSet classifier, not a clinically validated distress detector. It must not be represented as a certified emergency-response system without a consented representative dataset, device/battery testing, threshold calibration, false-positive/false-negative evaluation, abuse testing, privacy/legal review, and an independent safety case. The app intentionally does not silently contact emergency services or claim that an alert reached them.
