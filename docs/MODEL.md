# On-device model contract

## Bundled model

AcousticGuard bundles the TensorFlow Lite Task Audio YAMNet AudioSet classifier:

- Asset: `app/src/main/assets/yamnet.tflite`
- Model version exposed to incident metadata: `yamnet-audioset-1`
- Source: TensorFlow Lite Task Audio YAMNet artifact
- Integrity hash: see `app/src/main/assets/yamnet_model_manifest.json`
- Runtime: `org.tensorflow:tensorflow-lite-task-audio:0.4.4`

The model is loaded from the APK and executed locally. There is no model download at runtime.

## Label adapter

YAMNet emits AudioSet labels. The app does not treat every label as distress:

- Vocal: `Screaming`, `Shout`, `Yell`, `Children shouting`
- Respiratory: `Breathing`, `Gasp`
- Impact: `Thump, thud`, `Slap, smack`, `Whack, thwack`, `Smash, crash`, `Breaking`, `Knock`, `Bang`

`YamnetLabelMapper` combines those label scores with DSP features and accelerometer jerk. The output is still only a candidate score. `DistressFusionEngine` performs the temporal confirmation policy.

## Replacing the model

To replace YAMNet with a calibrated custom model:

1. Export a TensorFlow Lite model with a fixed sample rate and input window.
2. Add the model asset and a signed/recorded manifest containing source, version, license, input contract, and SHA-256.
3. Keep the `YamnetDistressClassifier`-equivalent adapter boundary so the monitoring service receives `DistressInference` only.
4. Update the label mapping and model version.
5. Add representative unit/instrumentation tests for quiet audio, speech, music, screams, respiratory sounds, impacts, adversarial background noise, and device-specific microphones.
6. Re-run threshold calibration and report sensitivity, specificity, precision, recall, false alerts per hour, missed-event rate, latency, and battery cost by device class.

Do not change thresholds from a handful of recordings. The application architecture prevents raw audio retention, but it cannot create a safety-valid dataset or prove emergency reliability by itself.
