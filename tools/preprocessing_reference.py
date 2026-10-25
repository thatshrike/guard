#!/usr/bin/env python3
"""Reference implementation for AcousticGuard's logmel64x32+motion-v1 contract.

This file intentionally uses only the Python standard library so it can run in a small training or
CI environment. The implementation mirrors LogMelFeatureEncoder.kt; it is a contract reference,
not a production-time audio processor.
"""

from __future__ import annotations

import math
from typing import Iterable, Sequence


SAMPLE_RATE_HZ = 16_000
MEL_BINS = 64
FRAME_COUNT = 32
FRAME_LENGTH = 512
FRAME_HOP = 496
MIN_FILTER_HZ = 50.0
LOG_EPSILON = 1e-6
LOG_MIN = -12.0
LOG_MAX = 4.0


def hz_to_mel(hz: float) -> float:
    return 1_127.0 * math.log(1.0 + hz / 700.0)


def mel_to_hz(mel: float) -> float:
    return 700.0 * (math.exp(mel / 1_127.0) - 1.0)


def fft_in_place(real: list[float], imaginary: list[float]) -> None:
    reverse_index = 0
    for index in range(1, len(real)):
        bit = len(real) >> 1
        while reverse_index & bit:
            reverse_index ^= bit
            bit >>= 1
        reverse_index ^= bit
        if index < reverse_index:
            real[index], real[reverse_index] = real[reverse_index], real[index]
            imaginary[index], imaginary[reverse_index] = imaginary[reverse_index], imaginary[index]

    length = 2
    while length <= len(real):
        angle = -2.0 * math.pi / length
        step_real = math.cos(angle)
        step_imaginary = math.sin(angle)
        block_start = 0
        while block_start < len(real):
            weight_real = 1.0
            weight_imaginary = 0.0
            for offset in range(length // 2):
                even_index = block_start + offset
                odd_index = even_index + length // 2
                odd_real = real[odd_index] * weight_real - imaginary[odd_index] * weight_imaginary
                odd_imaginary = real[odd_index] * weight_imaginary + imaginary[odd_index] * weight_real
                even_real = real[even_index]
                even_imaginary = imaginary[even_index]
                real[even_index] = even_real + odd_real
                imaginary[even_index] = even_imaginary + odd_imaginary
                real[odd_index] = even_real - odd_real
                imaginary[odd_index] = even_imaginary - odd_imaginary
                next_weight_real = weight_real * step_real - weight_imaginary * step_imaginary
                weight_imaginary = weight_real * step_imaginary + weight_imaginary * step_real
                weight_real = next_weight_real
            block_start += length
        length <<= 1


def build_filter_edges() -> tuple[list[int], list[int], list[int]]:
    minimum_mel = hz_to_mel(MIN_FILTER_HZ)
    maximum_mel = hz_to_mel(max(MIN_FILTER_HZ, SAMPLE_RATE_HZ / 2.0 - 1.0))
    points: list[int] = []
    for index in range(MEL_BINS + 2):
        mel = minimum_mel + (maximum_mel - minimum_mel) * index / (MEL_BINS + 1)
        frequency = mel_to_hz(mel)
        point = math.floor((FRAME_LENGTH + 1) * frequency / SAMPLE_RATE_HZ)
        points.append(max(0, min(FRAME_LENGTH // 2, point)))

    starts: list[int] = []
    centers: list[int] = []
    ends: list[int] = []
    for mel in range(MEL_BINS):
        starts.append(points[mel])
        centers.append(max(points[mel + 1], points[mel]))
        ends.append(min(FRAME_LENGTH // 2 + 1, max(points[mel + 2], points[mel + 1] + 1)))
    return starts, centers, ends


FILTER_STARTS, FILTER_CENTERS, FILTER_ENDS = build_filter_edges()
WINDOW = [
    float(0.5 - 0.5 * math.cos((2.0 * math.pi * index) / (FRAME_LENGTH - 1)))
    for index in range(FRAME_LENGTH)
]


def encode(samples: Sequence[float], motion_energy: float, motion_jerk: float) -> list[float]:
    destination = [0.0] * (MEL_BINS * FRAME_COUNT + 2)
    for frame in range(FRAME_COUNT):
        offset = frame * FRAME_HOP
        real = [
            samples[offset + index] * WINDOW[index]
            if offset + index < len(samples)
            else 0.0
            for index in range(FRAME_LENGTH)
        ]
        imaginary = [0.0] * FRAME_LENGTH
        fft_in_place(real, imaginary)
        power = [real[index] * real[index] + imaginary[index] * imaginary[index] for index in range(FRAME_LENGTH // 2 + 1)]
        feature_offset = frame * MEL_BINS
        for mel in range(MEL_BINS):
            start = FILTER_STARTS[mel]
            center = FILTER_CENTERS[mel]
            end = FILTER_ENDS[mel]
            energy = 0.0
            for index in range(start, center):
                energy += power[index] * (index - start) / max(1, center - start)
            for index in range(center, end):
                energy += power[index] * (end - index) / max(1, end - center)
            log_energy = math.log(energy + LOG_EPSILON)
            destination[feature_offset + mel] = max(
                0.0,
                min(1.0, (log_energy - LOG_MIN) / (LOG_MAX - LOG_MIN)),
            )

    motion_offset = MEL_BINS * FRAME_COUNT
    destination[motion_offset] = max(0.0, min(1.0, motion_energy))
    destination[motion_offset + 1] = max(0.0, min(1.0, motion_jerk))
    return destination


def contract_signal() -> list[float]:
    return [
        0.35 * math.sin(2.0 * math.pi * 440.0 * index / SAMPLE_RATE_HZ)
        + 0.10 * math.sin(2.0 * math.pi * 3_000.0 * index / SAMPLE_RATE_HZ)
        for index in range(SAMPLE_RATE_HZ)
    ]


if __name__ == "__main__":
    vector = encode(contract_signal(), motion_energy=0.25, motion_jerk=0.75)
    for index in (0, 1, 63, 64, 511, 1_024, 1_536, 2_048, 2_049):
        print(index, f"{vector[index]:.9f}")
