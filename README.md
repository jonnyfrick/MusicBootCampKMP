# MusicBootCamp

Ear training at the MIDI keyboard. The app plays a note or two-voice chord, and you play it back on
your keyboard. Mistakes are remembered as short sequences and come back until you master them.
Input comes from a MIDI keyboard or, on the desktop, from an acoustic piano via the microphone
(pitch detection for single notes, chord recognition for exercises with several voices).

Kotlin Multiplatform / Compose Multiplatform port of the original Java/Swing application. For what
changed and how the port was verified, see [MIGRATION.md](MIGRATION.md).

## Platforms

| Platform | Status |
|---|---|
| **Desktop** (macOS, Windows, Linux) | Fully working: MIDI in/out, microphone input, setups saved to disk, import of the Java app's files |
| Android, iOS, web | The UI runs; MIDI, microphone and persistent storage are not implemented yet |

## Modules

- [`core`](core/src/commonMain/kotlin) – all practice logic, pitch detection, storage and the import of the Java files (platform-independent)
- [`app/shared`](app/shared/src/commonMain/kotlin) – the Compose UI; `jvmMain` has the desktop MIDI (javax.sound.midi), microphone (javax.sound.sampled) and file storage
- [`app/desktopApp`](app/desktopApp) – desktop entry point and packaging
- `app/androidApp`, `app/iosApp`, `app/webApp` – entry points for the other platforms

## Running the desktop app

**Android Studio / IntelliJ IDEA:** open the project and choose a run configuration (shared in [`.run/`](.run)):

- **Desktop App** – starts the app with your real data
  (`~/Library/Application Support/MusicBootCamp` on macOS, `%APPDATA%\MusicBootCamp` on Windows,
  `~/.local/share/musicbootcamp` on Linux)
- **Desktop App (test data)** – the same app, but with its own data directory
  `app/desktopApp/build/dev-data`, so trying things out never touches your real setups
- **JVM Tests** – all tests that run on the JVM

**Command line:**

```bash
./gradlew :app:desktopApp:run
```

Installable packages (DMG, MSI, DEB) for the current OS: `./gradlew :app:desktopApp:packageDistributionForCurrentOS`.

MIDI devices can be plugged in and out while the app runs; the device lists update by themselves
(on macOS via [CoreMIDI4J](https://github.com/DerekCook/CoreMidi4J)).

Settings → MIDI devices has a MIDI test (shows the keys arriving from your keyboard, plays a test
note on the MIDI output); Settings → Input has a microphone test (level meter and recognised note).

Without headphones and with "Gervill" as MIDI Out, the app renders the synthesizer itself and removes
its own sound from the microphone signal. That needs the JVM argument
`--add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED`, which `run` and the packaged app set;
run from elsewhere without it, the app falls back to ignoring input that matches its own note.

On macOS the first microphone test asks for microphone permission for the app that started Gradle
(Android Studio or the terminal).

With "Record exercises" (Settings → Recognition, a developer tool: only in debug builds, i.e. when started
with `./gradlew … run` or installed with `installDebug`; see [MIGRATION.md](MIGRATION.md)) every exercise with microphone input is saved to
`recordings/` in the data directory: the microphone signal as WAV plus a JSON log of the notes the
app played, the notes recognised and the evaluations. To see how the current pitch detection handles
them:

```bash
./gradlew :core:jvmTest --tests '*RecordingReplayTest*' --rerun -Pmusicbootcamp.recordings=<folder or .wav>
```

This writes a step table, a CSV of every analysis hop and a spectrogram PNG per recording to
`core/build/analysis/<name>/`. Optional: `-Pmusicbootcamp.played=4=62,9=-` (what you played
where it was not the given note, `-` = nothing), `-Pmusicbootcamp.parameters={"rawRise":1.3}`
(replay with changed detection parameters) and `-Pmusicbootcamp.sweep=true` (rank a grid of
parameters), `-Pmusicbootcamp.voices=2` (use the chord recognition), `-Pmusicbootcamp.templates=<piano-templates.json>`
(a piano calibration) and `-Pmusicbootcamp.learnTemplates=<file>` (with `voices=1`: learn piano templates from single-note
recordings). "Optimization mode" in Settings → Recognition makes such test runs easy: a fixed
number of notes, always recorded, detection parameters editable, and a step-by-step list afterwards.

### Other targets

Android (MIDI over USB, microphone, the app's own piano as sound): `./gradlew :app:androidApp:installDebug`
with a phone connected (USB debugging on), or `assembleDebug` for the APK in
`app/androidApp/build/outputs/apk/debug`. Recordings land in
`Android/data/io.github.jonnyfrick.musicbootcamp/files/recordings` on the phone.

Web (Web MIDI in Chromium browsers and Firefox, microphone, the app's own piano; setups in the browser's local
storage): `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`, then http://localhost:8080.

iOS (CoreMIDI, microphone, the app's own piano) via Xcode in [`app/iosApp`](app/iosApp), on an Apple Silicon Mac.

Bluetooth MIDI: on Android and iOS under Settings → MIDI devices → "Connect Bluetooth MIDI…"; on macOS connect the
device in Audio MIDI Setup → Bluetooth, then it is listed like any MIDI device.

## Tests

```bash
./gradlew :core:jvmTest :app:shared:jvmTest :app:desktopApp:test
```

`core` contains golden master tests that replay outputs recorded from the Java implementation;
they run on a synthetic data set that is part of the repository (personal practice data is optional,
see [MIGRATION.md](MIGRATION.md)). `app/desktopApp` renders every screen off-screen into
`app/desktopApp/build/screenshots`, in phone, tablet and desktop window sizes and in German.
