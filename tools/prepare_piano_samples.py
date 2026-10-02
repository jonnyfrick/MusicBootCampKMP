#!/usr/bin/env python3
"""Prepares the piano samples the app ships, from the Salamander Grand Piano V3
(Alexander Holm, CC BY 3.0, https://github.com/sfzinstruments/SalamanderGrandPiano).

One velocity layer (by default v8 of 16, about mezzo-piano to mezzo-forte), every minor third from A0 to C8, as the
original is sampled. Each sample becomes mono, 44.1 kHz, 16 bit, starts at its onset, is cut
to a few seconds (longer in the bass) with a fade-out, and all are scaled by one common
factor, so the notes keep their natural balance. Output: FLAC files named by MIDI note.

Usage: prepare_piano_samples.py <folder with the …v<layer>.flac files> <output folder> [layer, default 8]
Needs macOS `afconvert` and the `flac` command line tool.
"""
import array
import os
import subprocess
import sys
import tempfile
import wave

RATE = 44100
DEFAULT_LAYER = 8
NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
NOTES = list(range(21, 109, 3))  # A0, C1, D#1, … C8


def name(note):
    return f"{NAMES[note % 12]}{note // 12 - 1}"


def seconds(note):
    """How much of a note is kept: 7 s at the bottom, 2.5 s at the top."""
    return 7.0 - (note - 21) / 87 * 4.5


def load(path):
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, "a.wav")
        subprocess.run(["afconvert", "-f", "WAVE", "-d", f"LEI16@{RATE}", "-c", "1", path, wav], check=True)
        with wave.open(wav) as f:
            assert f.getframerate() == RATE and f.getnchannels() == 1 and f.getsampwidth() == 2
            samples = array.array("h")
            samples.frombytes(f.readframes(f.getnframes()))
    return samples


def main(source, target, layer=DEFAULT_LAYER):
    os.makedirs(target, exist_ok=True)
    cut = {}
    for note in NOTES:
        samples = load(os.path.join(source, f"{name(note)}v{layer}.flac"))
        peak = max(abs(s) for s in samples)
        # The onset: the first sample above 1 % of the peak, minus 2 ms.
        onset = next(i for i, s in enumerate(samples) if abs(s) > peak * 0.01)
        start = max(0, onset - RATE // 500)
        kept = samples[start:start + int(seconds(note) * RATE)]
        fade = min(len(kept), RATE // 2)
        for i in range(fade):
            index = len(kept) - fade + i
            kept[index] = int(kept[index] * (1 - i / fade) ** 2)
        cut[note] = kept
    gain = 0.9 * 32767 / max(max(abs(s) for s in kept) for kept in cut.values())
    total = 0
    for note, kept in cut.items():
        scaled = array.array("h", (int(s * gain) for s in kept))
        with tempfile.TemporaryDirectory() as tmp:
            wav = os.path.join(tmp, "a.wav")
            with wave.open(wav, "wb") as f:
                f.setnchannels(1)
                f.setsampwidth(2)
                f.setframerate(RATE)
                f.writeframes(scaled.tobytes())
            out = os.path.join(target, f"{note:03d}.flac")
            subprocess.run(["flac", "--best", "--silent", "--force", "--no-padding", "--no-seektable", "-o", out, wav], check=True)
            total += os.path.getsize(out)
    print(f"layer v{layer}: {len(cut)} samples, {total / 1e6:.1f} MB, gain {gain:.2f}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else DEFAULT_LAYER)
