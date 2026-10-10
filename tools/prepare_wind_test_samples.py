#!/usr/bin/env python3
"""Prepares short wind-instrument tones for the recognition tests, from the VSCO 2 Community
Edition by Versilian Studios (CC0, https://github.com/sgossner/VSCO-2-CE).

Each sustained sample becomes mono, 44.1 kHz, 16 bit, starts at its onset, is cut to 1.8 s with
a short fade-out and scaled to the same peak. Output: <instrument>/<MIDI note>.flac.

Usage: prepare_wind_test_samples.py <folder with one subfolder of .wav files per instrument> <output folder>
Needs macOS `afconvert` and the `flac` command line tool.
"""
import array
import os
import re
import subprocess
import sys
import tempfile
import wave

RATE = 44100
SECONDS = 1.8
NAMES = {"C": 0, "C#": 1, "D": 2, "D#": 3, "E": 4, "F": 5, "F#": 6, "G": 7, "G#": 8, "A": 9, "A#": 10, "B": 11}


# Samples whose name is not what they sound (measured): the clarinet's top "F#5" is an F.
MISNAMED = {"DCClar_susLong_F#5": 89}


def midi(file_name):
    """The library counts octaves one lower than MIDI's usual names: its C3 is middle C (60)."""
    for prefix, note in MISNAMED.items():
        if file_name.startswith(prefix):
            return note
    name, octave = re.search(r"_([A-G]#?)(\d)_", file_name).groups()
    return NAMES[name] + 12 * (int(octave) + 2)


def load(path):
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, "a.wav")
        subprocess.run(["afconvert", "-f", "WAVE", "-d", f"LEI16@{RATE}", "-c", "1", path, wav], check=True)
        with wave.open(wav) as f:
            samples = array.array("h")
            samples.frombytes(f.readframes(f.getnframes()))
    return samples


def main(source, target):
    for instrument in sorted(os.listdir(source)):
        folder = os.path.join(source, instrument)
        if not os.path.isdir(folder):
            continue
        os.makedirs(os.path.join(target, instrument), exist_ok=True)
        total = 0
        for name in sorted(os.listdir(folder)):
            if not name.endswith(".wav"):
                continue
            samples = load(os.path.join(folder, name))
            peak = max(abs(s) for s in samples)
            onset = next(i for i, s in enumerate(samples) if abs(s) > peak * 0.02)
            kept = samples[max(0, onset - RATE // 200):][:int(SECONDS * RATE)]
            fade = RATE // 10
            for i in range(fade):
                kept[len(kept) - fade + i] = int(kept[len(kept) - fade + i] * (1 - i / fade))
            gain = 0.5 * 32767 / max(abs(s) for s in kept)
            scaled = array.array("h", (int(s * gain) for s in kept))
            with tempfile.TemporaryDirectory() as tmp:
                wav = os.path.join(tmp, "a.wav")
                with wave.open(wav, "wb") as f:
                    f.setnchannels(1)
                    f.setsampwidth(2)
                    f.setframerate(RATE)
                    f.writeframes(scaled.tobytes())
                out = os.path.join(target, instrument, f"{midi(name):03d}.flac")
                subprocess.run(["flac", "--best", "--silent", "--force", "--no-padding", "--no-seektable", "-o", out, wav], check=True)
                total += os.path.getsize(out)
        print(f"{instrument}: {len(os.listdir(os.path.join(target, instrument)))} notes, {total / 1e3:.0f} kB")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
