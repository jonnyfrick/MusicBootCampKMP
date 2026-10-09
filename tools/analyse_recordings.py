#!/usr/bin/env python3
"""Fetches recordings of exercises and replays them through the current recognition.

Recordings are made in debug builds with Settings → Recognition → "Record exercises" or the
optimization mode: a WAV file plus a JSON log with the same name.

    tools/analyse_recordings.py android            the phone connected over USB (USB debugging on)
    tools/analyse_recordings.py desktop            the desktop app's test data (build/dev-data)
    tools/analyse_recordings.py <folder or .wav>   e.g. ~/Downloads for recordings of the web app

Options:
    --last N        analyse the newest N recordings (default 1); --all for every one
    --list          only list what is there (after fetching), newest last
    --played 4=62,9=-   what was really played where it was not the given note ("-" = nothing);
                        only with a single recording
    -P name=value   passed on to the replay as -Pmusicbootcamp.<name>=<value>
                    (parameters, sweep, templates, voices, learnTemplates; see RecordingReplayTest)

Recordings fetched from the phone are kept in build/recordings/android (never committed: they are
personal data). The analysis of each recording lands in core/build/analysis/<name>/: steps.txt
(printed here), hops.csv and spectrogram.png.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACKAGE = "io.github.jonnyfrick.musicbootcamp"
PHONE_FOLDER = f"/sdcard/Android/data/{PACKAGE}/files/recordings"
ANDROID_COPY = os.path.join(ROOT, "build", "recordings", "android")
DESKTOP_FOLDER = os.path.join(ROOT, "app", "desktopApp", "build", "dev-data", "recordings")


def adb():
    found = shutil.which("adb")
    if found:
        return found
    for sdk in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), os.path.expanduser("~/Library/Android/sdk")):
        if sdk and os.path.isfile(os.path.join(sdk, "platform-tools", "adb")):
            return os.path.join(sdk, "platform-tools", "adb")
    sys.exit("adb not found: install the Android platform tools or set ANDROID_HOME.")


def fetch_from_phone():
    """Copies the recordings that are not here yet from the phone; returns the local folder."""
    tool = adb()
    devices = subprocess.run([tool, "devices"], capture_output=True, text=True).stdout.splitlines()[1:]
    ready = [line.split()[0] for line in devices if line.strip().endswith("device")]
    if not ready:
        waiting = [line for line in devices if line.strip()]
        hint = f" (seen: {'; '.join(waiting)} - allow USB debugging on the phone)" if waiting else ""
        sys.exit("No phone connected. Plug it in with USB debugging on" + hint + ".")
    listing = subprocess.run([tool, "shell", "ls", PHONE_FOLDER], capture_output=True, text=True)
    if listing.returncode != 0:
        sys.exit(f"No recordings on the phone ({PHONE_FOLDER}): {listing.stderr.strip() or listing.stdout.strip()}")
    os.makedirs(ANDROID_COPY, exist_ok=True)
    names = [n for n in listing.stdout.split() if n.endswith((".wav", ".json"))]
    new = [n for n in names if not os.path.isfile(os.path.join(ANDROID_COPY, n))]
    for name in new:
        target = os.path.join(ANDROID_COPY, name)
        pulled = subprocess.run([tool, "pull", f"{PHONE_FOLDER}/{name}", target], capture_output=True, text=True)
        if pulled.returncode != 0:
            # Some Android versions hide Android/data from adb; the debuggable app itself may read it.
            with open(target, "wb") as out:
                subprocess.run([tool, "exec-out", "run-as", PACKAGE, "cat", f"{PHONE_FOLDER}/{name}"], stdout=out, check=True)
    print(f"{len(names) // 2} recordings on the phone, {len(new)} files fetched to {os.path.relpath(ANDROID_COPY, ROOT)}")
    return ANDROID_COPY


def recordings(source):
    """The WAV files of [source] that have their log, oldest first (the names sort by time)."""
    if os.path.isfile(source):
        return [source]
    if not os.path.isdir(source):
        sys.exit(f"Neither a folder nor a file: {source}")
    wavs = sorted(f for f in os.listdir(source) if f.startswith("session-") and f.endswith(".wav"))
    return [os.path.join(source, f) for f in wavs if os.path.isfile(os.path.join(source, f[:-4] + ".json"))]


def describe(wav):
    with open(wav[:-4] + ".json") as f:
        log = json.load(f)
    info = log.get("info", {})
    steps = sum(1 for event in log.get("events", []) if event.get("type") == "STEP")
    seconds = (os.path.getsize(wav) - 44) / 2 / len(log.get("channels", [1])) / log.get("sampleRate", 44100)
    return info, f"{os.path.basename(wav)}: {steps} steps, {seconds:.0f} s, {info.get('voices', '1')} voice(s), " \
                 f"every {info.get('breathingTime', '?')} s, output {info.get('midiOutputDevice') or '?'}"


def analyse(wav, played, extra):
    info, summary = describe(wav)
    print("\n== " + summary)
    properties = {"recordings": os.path.abspath(wav)}
    if info.get("voices", "1") != "1":
        properties["voices"] = info["voices"]
    if played:
        properties["played"] = played
    properties.update(extra)
    command = ["./gradlew", ":core:jvmTest", "--tests", "*RecordingReplayTest.analyseRecordings*", "--rerun", "-q"]
    command += [f"-Pmusicbootcamp.{name}={value}" for name, value in properties.items()]
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
    report = os.path.join(ROOT, "core", "build", "analysis", os.path.basename(wav)[:-4], "steps.txt")
    if result.returncode != 0 or not os.path.isfile(report):
        print(result.stdout[-3000:], result.stderr[-3000:])
        sys.exit("The replay failed.")
    with open(report) as f:
        print(f.read())
    print(f"details: {os.path.relpath(os.path.dirname(report), ROOT)}/ (hops.csv, spectrogram.png)")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("source", help="android, desktop, or a folder / .wav file")
    parser.add_argument("--last", type=int, default=1)
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--played")
    parser.add_argument("-P", action="append", default=[], metavar="name=value")
    args = parser.parse_args()

    source = {"android": fetch_from_phone, "desktop": lambda: DESKTOP_FOLDER}.get(args.source, lambda: os.path.expanduser(args.source))()
    found = recordings(source)
    if not found:
        sys.exit(f"No recordings (session-….wav with its .json) in {source}")
    if args.list:
        for wav in found:
            print(describe(wav)[1])
        return
    chosen = found if args.all else found[-args.last:]
    if args.played and len(chosen) != 1:
        sys.exit("--played needs exactly one recording.")
    extra = dict(item.split("=", 1) for item in args.P)
    for wav in chosen:
        analyse(wav, args.played, extra)


if __name__ == "__main__":
    main()
