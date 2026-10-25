# On-device model contract

AcousticGuard uses a hybrid on-device pipeline. Cheap DSP and motion features are always available;
the neural classifier is scheduled only for candidate windows or periodic quiet-state scans. The
classifier output is never an incident by itself. `DistressFusionEngine`, the visible countdown,
and the encrypted metadata-only outbox remain the safety boundary.

## Active model selection

`ModelSelector` attempts the domain-specific student model first:

- Asset: `app/src/main/assets/distress_student_int8.tflite`
- Manifest: `app/src/main/assets/distress_student_manifest.json`
- Model version: `distress-student-int8-1`
- Runtime: TensorFlow Lite interpreter
- Quantization: full integer INT8
- Input: 16 kHz, one-second window, `logmel64x32+motion-v1`
- Outputs: `normal`, `scream`, `respiratory`, `impact`

The repository currently does **not** contain a trained student asset. Until a reviewed asset and
manifest are added, selection reports a visible YAMNet fallback state rather than pretending that a
domain-specific model is active. If the student asset is present but fails its manifest, SHA-256,
tensor-shape, quantization, or output-contract checks, the app also falls back to YAMNet. If both
models fail to load, monitoring enters an explicit error state; it is never silently disabled.

The confirmation thresholds are no longer embedded only in the fusion implementation. The APK
packages `app/src/main/assets/detection_policy_v1.json`; its SHA-256 is verified before monitoring
starts and the immutable `DetectionPolicy` is passed into `DistressFusionEngine`. The current
`fusion-v1` values are implementation defaults and are not evidence of calibration.

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

- Vocal distress: `Screaming`, `Shout`, `Yell`, `Children shouting`
- Loud speech escalation: `Speech`, `Male speech, man speaking`, `Female speech, woman speaking`,
  `Child speech, kid speaking`, combined with local
  loudness and vocal-activity features. This is language-independent; the app does not transcribe
  or store the spoken words.
- Respiratory: `Breathing`, `Gasp`
- Impact: `Thump, thud`, `Slap, smack`, `Whack, thwack`, `Smash, crash`, `Breaking`, `Knock`, `Bang`

`YamnetLabelMapper` combines those label scores with DSP features and accelerometer jerk. The
output is still only a candidate score. `DistressFusionEngine` performs the temporal confirmation
policy and rejects highly uncertain or low-quality windows. Vocal distress does not require phone
motion; the same marker must still be present across multiple windows before the countdown starts.

## Student model input/output contract

`TinyDistressClassifier` uses one interpreter thread and a fixed contract so the training and
runtime pipelines can be tested independently:

1. Read normalized mono PCM at 16,000 Hz for 1,000 ms.
2. Encode 32 overlapping frames into 64 log-mel values per frame.
3. Append normalized motion energy and jerk.
4. Quantize the 2,050 input values using the input tensor's INT8 scale and zero point.
5. Dequantize the four output values, normalize them as probabilities, and calculate normalized
   entropy as the uncertainty estimate.

The validated student adapter requires fixed `[1, 2050]` and `[1, 4]` tensor shapes and INT8 input
and output tensors. A manifest must also record the preprocessing revision, dataset revision,
calibration revision, license, limitations, intended use, and threshold-policy revision.

The output order is fixed: `normal`, `scream`, `respiratory`, `impact`. A composite panic pattern
is a temporal fusion result, not a directly observable neural class. A student score therefore
cannot bypass motion corroboration for impacts, repeated-window confirmation, the ten-second
visible cancellation countdown, or the cooldown.

## Scheduling and diagnostics

`InferenceScheduler` uses loudness, spectral flux, crest factor, envelope variation, and motion as
a first-stage gate. It still runs a periodic quiet-state scan so soft respiratory events are not
permanently suppressed, and it keeps the model active after an uncertain result. The
`DetectionMetrics` object stores only scalar counters and p50/p95 latency estimates in memory:

- audio windows read
- model invocations and skipped invocations
- dropped windows
- inference latency p50/p95
- last model version

No PCM, spectrogram, transcript, location, or user identity is included in these metrics.

## Testable detector boundary

`DetectionPipeline` is the Android-independent orchestration boundary used by the foreground
service. It accepts one normalized window and a `MotionSnapshot`, then returns features, whether
inference ran, the inference result, and the temporal decision. `DetectionClock` and
`ReplayAudioWindowSource` allow deterministic replay tests without a microphone, a service, or
wall-clock sleeps.

## Replacing the model

To add the calibrated student model or replace either adapter:

1. Train on a private, access-controlled dataset split by person, device, room/environment, and recording session. Include hard negatives such as laughter, singing, television, music, vehicle noise, doors, dropped objects, exercise breathing, coughs, pets, wind, handling noise, replayed screams, and simultaneous sounds.
2. Export a full-integer INT8 TensorFlow Lite model with a representative calibration set.
3. Add the model asset and a recorded manifest containing source, version, license, input contract, output order, and SHA-256. Do not add raw training recordings to the repository.
4. Keep the `DistressClassifier` adapter boundary so the monitoring service receives `DistressInference` only.
5. Add representative unit/instrumentation tests for quiet audio, speech, music, screams, respiratory sounds, impacts, adversarial background noise, quantization scales, and device-specific microphones.
6. Calibrate thresholds on a held-out device-level validation set and report sensitivity, specificity, precision, recall, false alerts per hour, missed-event rate, latency, and battery cost by device class.
7. Compare the student model with the current YAMNet-plus-fusion baseline before changing which model is authoritative.

Do not run both models continuously in a normal production build. Shadow mode is an internal
validation tool and otherwise doubles inference work and battery cost.

Do not change thresholds from a handful of recordings. The application architecture prevents raw audio retention, but it cannot create a safety-valid dataset or prove emergency reliability by itself.
