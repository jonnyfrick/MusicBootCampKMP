# MusicBootCamp

Ear training at the MIDI keyboard. The app plays a note or two-voice chord, and you play it back on
your keyboard. Mistakes are remembered as short sequences and come back until you master them.
Input comes from a MIDI keyboard or, on the desktop, from an acoustic piano via the microphone
(pitch detection, single notes).

Kotlin Multiplatform / Compose Multiplatform port of the original Java/Swing application. For what
changed and how the port was verified, see [MIGRATION.md](MIGRATION.md).

## Modules

- [`core`](core/src/commonMain/kotlin) – all practice logic, storage and the import of the Java files (platform-independent)
- [`app/shared`](app/shared/src/commonMain/kotlin) – the Compose UI; `jvmMain` has the desktop MIDI (javax.sound.midi) and file storage
- [`app/desktopApp`](app/desktopApp) – desktop entry point (the platform with full MIDI support)
- `app/androidApp`, `app/iosApp`, `app/webApp` – entry points that show the UI; MIDI is not implemented there yet

## Running

```bash
./gradlew :app:desktopApp:run
```

Other targets: `./gradlew :app:androidApp:assembleDebug`, `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`,
iOS via Xcode in [`app/iosApp`](app/iosApp).

## Tests

```bash
./gradlew :core:jvmTest :app:shared:jvmTest :app:desktopApp:test
```

`core` contains golden master tests that replay outputs recorded from the Java implementation.
`app/desktopApp` renders every screen off-screen into `app/desktopApp/build/screenshots`.
