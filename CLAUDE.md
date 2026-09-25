# CLAUDE.md

Instructions for Claude Code sessions in this repository, local or in the cloud. Read
[README.md](README.md) (modules, running, tests) and [MIGRATION.md](MIGRATION.md) (why things are
the way they are, verification, deliberate deviations from the Java version) first — don't
duplicate them here.

## Conventions

- Code, comments, commit messages and docs in English, whatever language the chat is in.
- Logic belongs in `core/src/commonMain`, UI in `app/shared/src/commonMain`. Platform code only
  behind the interfaces in `app/shared/.../platform/PlatformServices.kt`, implemented in the
  platform source sets (`jvmMain` for desktop). Keep roughly 90 % of the code in `commonMain`.
- UI state lives in `AppController` as Compose state (`mutableStateOf`); asynchronous work uses
  coroutines. Never block the UI thread with device or file I/O.
- `PracticeSession` and `OwnSoundGate` are not thread-safe: use them only on the confined
  dispatcher of `PracticeRunner`.

## Behaviour pinned to the Java version

- `GoldenMasterTest` replays outputs recorded from the unchanged Java app. Never edit fixtures in
  `core/src/jvmTest/resources/golden/` by hand and never loosen these tests to make a change pass.
- Changing practice behaviour on purpose is fine, but: keep the random numbers consumed identical
  wherever the Java behaviour is kept, add a test for the new behaviour, and document the change in
  MIGRATION.md under "Deliberate changes".
- Fixtures are regenerated with the Java harness (branch `golden_master_harness` of
  `jonnyfrick/MusicBootCampRepo`, see MIGRATION.md).

## Data

- Never commit personal practice data: `learned_sequences_*` and `canonical_learned_sequences_*`
  directly in `golden/` are git-ignored on purpose. The synthetic set in `golden/synthetic/` is the
  shareable replacement.
- Never run the app or tests against the real data directory
  (`~/Library/Application Support/MusicBootCamp` etc.); use
  `./gradlew :app:desktopApp:run -Pmusicbootcamp.dataDir=build/dev-data` ("Desktop App (test data)").
- The setup JSON has a `version` field; a format change needs a version bump and a migration of
  existing files.

## MIDI and audio on the desktop

- MIDI devices are listed and opened through CoreMIDI4J (hot-plugging on macOS) and are never closed
  with `MidiDevice.close()`: on macOS that can hang forever in native code. Ports only detach.
- Hardware (MIDI keyboard, microphone, sound) cannot be tested in the cloud or by automated tests.
  Say so, and give the user concrete steps to check the change at the instrument.

## Before committing

```bash
./gradlew :core:jvmTest :app:shared:jvmTest :app:desktopApp:test
```

- UI changes: `ScreenshotTest` renders every tab into `app/desktopApp/build/screenshots` — look at
  the PNGs, not only at the test result.
- Changes to `commonMain`: also compile the other targets, e.g.
  `./gradlew :app:shared:compileKotlinJs :app:shared:compileKotlinWasmJs :app:shared:compileAndroidMain`.
- Skipped tests are expected where their preconditions are missing: `MidiHotplugTest` (macOS with
  Swift only) and the tests using personal learned sequences.

## Working in the cloud

- Work on your own branch and open a pull request; the user reviews and merges. Don't push to
  `master` unless asked.
- The cloud machine is Linux without MIDI, microphone or display, and possibly without an Android
  SDK. If Gradle fails because the Android SDK is missing, install the Android command-line tools
  (or ask the user) instead of removing the Android targets.
