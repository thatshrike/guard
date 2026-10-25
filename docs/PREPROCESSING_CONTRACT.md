# Preprocessing contract

The student-model input contract is versioned as `logmel64x32+motion-v1`. Training code and the
Android runtime must produce the same 2,050 values for the same normalized one-second window.

## Contract

- mono normalized PCM;
- 16,000 Hz sample rate;
- 16,000 samples per input window;
- 32 frames;
- 512-sample Hann window;
- 496-sample frame hop;
- 64 triangular mel bins from 50 Hz to just below Nyquist;
- natural-log energy with the runtime's `1e-6` floor;
- log-energy normalization from `-12` to `4`, clipped to `0..1`;
- motion energy followed by motion jerk, both clipped to `0..1`;
- no raw audio retained after encoding.

## Implementations

- Android runtime: `app/src/main/java/com/acousticguard/ml/LogMelFeatureEncoder.kt`;
- standard-library reference: `tools/preprocessing_reference.py`;
- golden-vector checks: `app/src/test/java/com/acousticguard/LogMelFeatureEncoderTest.kt`.

Run the reference vector from the repository root with:

```bash
python tools/preprocessing_reference.py
```

The reference implementation is for training and verification only. It is not a replacement for
the allocation-bounded Android encoder.

When the preprocessing algorithm changes, increment the contract revision, regenerate the golden
vectors, retrain or re-export the student model, and update its manifest. Do not silently change
the meaning of `distress-student-int8-1`.
