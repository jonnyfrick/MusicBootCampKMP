# MusicBootCamp

Ear training at the MIDI keyboard. The app plays a note or two-voice chord, and you play it back on
your keyboard. Mistakes are remembered as short sequences and come back until you master them.
Input comes from a MIDI keyboard or, on the desktop, from an acoustic piano via the microphone
(pitch detection, single notes).

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

Preferences has a MIDI test (shows the keys arriving from your keyboard, plays a test note on the
MIDI output) and a microphone test (level meter and recognised note).

On macOS the first microphone test asks for microphone permission for the app that started Gradle
(Android Studio or the terminal).

### Other targets

`./gradlew :app:androidApp:assembleDebug`, `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`,
iOS via Xcode in [`app/iosApp`](app/iosApp).

## Tests

```bash
./gradlew :core:jvmTest :app:shared:jvmTest :app:desktopApp:test
```

`core` contains golden master tests that replay outputs recorded from the Java implementation;
they run on a synthetic data set that is part of the repository (personal practice data is optional,
see [MIGRATION.md](MIGRATION.md)). `app/desktopApp` renders every screen off-screen into
`app/desktopApp/build/screenshots`.
