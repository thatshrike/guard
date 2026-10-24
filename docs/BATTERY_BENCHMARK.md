# Battery and performance benchmark plan

This document defines the repeatable comparison needed before making the TinyML student model the
authoritative production classifier. It is a measurement plan, not a claim that the targets have
already been met.

## Workloads

Run each workload for at least 30 minutes on every supported device class, with the screen off
after setup and the same audio route, volume, permissions, and battery starting level:

1. YAMNet baseline with the existing every-window inference loop.
2. YAMNet with allocation fixes and the two-stage scheduler.
3. TinyML without gating.
4. TinyML with gating and periodic quiet scans.
5. TinyML with the normal, candidate, and impact motion sampling policies.

Do not run YAMNet and TinyML concurrently outside an internal shadow experiment; that doubles model
work and is not a fair production battery comparison.

## Record

- battery percentage per hour and average/peak current where the device exposes it
- CPU time and process memory/RSS
- inference count per hour and p50/p95 latency
- thermal status and throttling
- audio windows read, skipped, and dropped
- false alerts per hour and missed-event rate on the held-out fixture set
- time from event onset to the visible countdown
- recall on low-volume respiratory examples

The in-app `DetectionMetrics` object records only scalar window and inference diagnostics in RAM.
Use Android Studio Power Profiler and `dumpsys batterystats` for device-level power evidence; do
not add raw PCM, spectrograms, transcripts, location, or identity to the benchmark artifacts.

## Initial gates

Finalize numeric gates after the baseline is measured. The first review should require:

- no held-out positive-event recall regression
- no false-alert increase versus the current baseline
- p95 inference latency below the audio-window cadence
- no inference backlog during a 30-minute run
- a smaller student asset and lower inference CPU time than YAMNet
- no increase in end-to-end battery drain
- every confirmed event still passes the visible countdown and encrypted metadata-only queue

Record device model, Android version, build variant, model SHA-256, thresholds, sensor policy, and
the exact test fixture revision with every result.
