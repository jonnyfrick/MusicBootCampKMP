# Migration from the Java version

This project is a Kotlin Multiplatform / Compose Multiplatform port of the Swing
application in `~/Dropbox/MusicBootCampRepo/MusicBootCamp` (≈5,400 lines of Java,
28 files). This document records the decisions taken during the migration and
how the port was verified.

## Result at a glance

| | Java | Kotlin Multiplatform |
|---|---|---|
| UI | Swing dialogs (NetBeans form editor) | Compose Multiplatform, one window with tabs |
| Timing | `java.util.Timer` + `TimerTask` | Coroutines (`PracticeRunner`) |
| MIDI | `javax.sound.midi` | `MidiBackend` interface; desktop implementation with `javax.sound.midi` |
| Storage | Hand-written XML, one settings + one learned-sequences file per setup | Versioned JSON via kotlinx.serialization; one-way XML import |
| Platforms | Desktop (JVM) | Desktop fully working; Android, iOS, web compile and show the UI but have no MIDI yet |
| Tests | none | Golden master tests against recorded Java output + unit tests |

## Project layout

- `core/src/commonMain` – all logic, platform-independent, no UI:
  - `model/` – `PracticeSettings`, `PracticeMode`, `Direction`, `SequenceElement`, `RandomSource`, `SettingsRules`
  - `learning/LearnedSequences` – the mistake memory (Java: `LearnedSequencesDataStructure`, `PrioritiesArrayList`, `OnePriorityArrayList`, `LearnedSequencesPrioritizer`)
  - `engine/TheoryEngine` – chooses the next note(s) (Java: `TheoryEngine`)
  - `practice/` – `Corrector`s, `PracticeSession` (one step, Java: the `TimerTask.run()` bodies and `AddToCorrectorReceiver`), `PracticeRunner` (the clock)
  - `midi/` – `MidiMessage`, `Tuning` (Kammerton A via pitch bend), `NoteNames`
  - `persistence/` – `Setup`, `SetupRepository`, `DocumentStore`, `AppPreferences`
  - `legacy/LegacyImport` – reader for the Java XML files
- `app/shared/src/commonMain` – the Compose UI (`ui/`) and the platform interfaces (`platform/PlatformServices`)
- `app/shared/src/jvmMain` – desktop implementations: Java Sound MIDI, file storage, AWT file dialog for the import
- `app/desktopApp` – the desktop entry point (`main.kt`) and packaging
- `app/androidApp`, `app/iosApp`, `app/webApp` – entry points; they start the shared UI without MIDI

The wizard's `composeApp` module does not exist in this template generation; its role is split between `core` (logic) and `app/shared` (UI). The wizard's Ktor `server` module was removed because the app has no backend.

About 90 % of the production code is in `commonMain` (2,350 lines). The rest (250 lines) is the platform entry points and the desktop MIDI/file adapters in `app/shared/src/jvmMain`.

## How the port was verified (golden master)

Compiling proves little about whether the algorithms still behave the same, so the
Java code itself defines the expected results:

1. In the Java repository, branch **`golden_master_harness`** (commit `bd95af6`):
   - `Tools/RandomProvider` – the three `new Random()` call sites now go through it.
     Production behaviour is unchanged (it still returns `new Random()`).
   - `test/musicbootcamp/GoldenMasterGenerator.java` – runs the **unchanged** Java
     classes with scripted input and writes fixtures. It records every random number the
     Java code draws, every MIDI message it sends and the learned-sequence memory after
     every step.
2. The fixtures come in two sets in `core/src/jvmTest/resources/golden/`:
   - **`synthetic/` (in git, always runs):** a small generated data set (`SyntheticData.java`,
     97 monophonic and 82 two-voice learned sequences on all 5 priority levels, written by the Java
     app's own XML writer) with settings files, canonical dumps and three practice scenarios
     (1,050 steps that take 245 learned sequences). Sequences reaching outside the practice range
     start outside it, so they test the import but are never played (see the range fix below).
   - **`golden/` itself: your own data.** The settings files are in git; the learned sequences
     are personal practice data and **not in git** (`.gitignore`). Without them the three tests
     that need them are skipped. To run them, copy them from the Java app:
     `cp ~/Dropbox/MusicBootCampRepo/MusicBootCamp/learned_sequences_*.xml core/src/jvmTest/resources/golden/`
     and regenerate the personal fixtures (below).
3. `GoldenMasterTest` replays them. A `ReplayRandomSource` hands the Kotlin code
   exactly the numbers Java drew and fails if the Kotlin code asks for a random number with a
   different bound, or asks for a different count of them. Covered:
   - import of both learned-sequence files (25,142 and 2,597 sequences) – every sequence,
     priority and order identical
   - import of all three settings files
   - 5 practice scenarios, 1,400 steps in total (monophonic and two voices, empty and real
     memory, learning on/off): MIDI output, given notes and memory size per step, final memory
     content via SHA-256
   - pitch bend messages for every Kammerton A value from 415 to 466 Hz plus edge cases
   - note names for all 128 MIDI notes
4. A deliberately introduced deviation (wrong predecessor order in the two-voice corrector)
   makes the tests fail, so they do catch regressions.

To regenerate the fixtures (e.g. after changing the Java code):

```bash
cd ~/Dropbox/MusicBootCampRepo/MusicBootCamp && git switch golden_master_harness
javac -d /tmp/mbc -cp lib/swing-layout-1.0.3.jar $(find src test -name '*.java')
# synthetic set: inputs and fixtures are written into the working directory
(cd ~/Developer/MusicBootCampKMP/core/src/jvmTest/resources/golden/synthetic && \
  java -Djava.awt.headless=true -cp /tmp/mbc:$OLDPWD/lib/swing-layout-1.0.3.jar musicbootcamp.GoldenMasterGenerator . synthetic)
# personal set: reads your files in the Java project, writes only the fixtures
java -Djava.awt.headless=true -cp /tmp/mbc:lib/swing-layout-1.0.3.jar musicbootcamp.GoldenMasterGenerator ~/Developer/MusicBootCampKMP/core/src/jvmTest/resources/golden
```

Further tests: `CoreTest` (common, all platforms), `DesktopPersistenceTest` (imports the real
files, stores them through the file store and reloads them), `ScreenshotTest` (renders every tab
off-screen into `app/desktopApp/build/screenshots` and runs an exercise against a fake MIDI system).

```bash
./gradlew :core:jvmTest :app:shared:jvmTest :app:desktopApp:test
```

## Decisions

### Behaviour kept exactly as in Java
- Step logic, interval weighting, range reflection at the limits, embedding of learned sequences,
  priority levels (weights 1, 2, 2, 3, 5 for 5 levels), correction rules (only the first key press
  counts in monophonic mode; two voices accept a unison played once and any order).
- Two-voice mistakes are stored **newest chord first**, as the Java code did. This looks
  unintended (the Java source has a "FIXXXME Test if Krebs" note), but changing it would make
  existing memories replay differently. See "Open points".
- Note length = breathing time × sustain %, step period = breathing time (first step immediately).
- Tuning: RPN 0 pitch bend range ±2 semitones on all 16 channels, then the bend value.
- Range dialog rules (lowest 12, highest 108, at least 24 semitones, start position in the middle)
  with the original messages.

### Deliberate changes
- **Mode-specific memories.** Java cleared the learned sequences when the mode changed, and could
  overwrite the file with the empty memory afterwards. Now every setup keeps one memory per mode.
- **Autosave instead of "Save current setup?"** Settings are saved shortly after each change;
  learned sequences when an exercise stops and when the app closes. "Save setup as…" copies a setup.
- **Global preferences.** MIDI devices and Kammerton A are machine settings (`preferences.json`)
  instead of being stored in every setup file. Importing a Java setup adopts its Kammerton A.
- **MIDI devices can be plugged in while the app runs (macOS).** Java's own CoreMIDI support
  reads the device list only once, so a keyboard connected after the start was never found
  (reproduced with a virtual CoreMIDI source). The desktop app now lists and opens MIDI devices
  through [CoreMIDI4J](https://github.com/DerekCook/CoreMidi4J) (EPL 1.0), which sees devices
  appear and disappear; the app updates its lists and shows what was connected or disconnected.
  Device names now include the device, e.g. "Digital Keyboard Anschluss 1" instead of "Anschluss 1".
  `MidiHotplugTest` plugs a virtual keyboard in and out (a small Swift program; macOS only).
- **MIDI devices stay open.** Closing a MIDI input on macOS can block forever in Java's native
  code (`MidiInDevice.nStop` against its reader thread, e.g. after the keyboard was unplugged);
  this froze the app when a MIDI test was stopped. Devices are now opened once and only detached
  afterwards — which is what the Java version did too (its `closeDevices()` was commented out).
- **Default MIDI input** skips Java's built-in "Real Time Sequencer" (the Java app picked it
  when no device was saved, which is never a keyboard).
- **Crash cases handled.** Java crashed (and its timer silently died) on: all interval weights 0,
  a chord wider than the voice count, sequences starting outside 0–127, and on starting the
  unimplemented "Homophonic modes". Now these are prevented in the UI or ignored.
- **Exercise stays in its range (bug fix, also present in Java).** Once the note was outside the
  range, the Java reflection at the limits no longer pulled it back: steps turned into an unbounded
  random walk that could drift far away (reproduced: range 48–72, one voice at 107). It got outside
  through learned sequences recorded with a different range (or a narrowed range). Now
  learned sequences that reach outside the current range are skipped (they stay in memory, the
  Memory tab shows how many), and random steps from outside the range always move towards it.
  Within the range the behaviour and the consumed random numbers are unchanged, so the golden master
  tests still pass. Tests: `CoreTest.learnedSequencesOutsideTheRangeDoNotLeadTheExerciseAway`,
  `CoreTest.randomStepsFindBackIntoTheRange`.
- **Given notes hidden by default.** The point is to hear them; "Show notes" on the Practice tab
  reveals them (stored in `preferences.json`).
- **Settings edited in place** (no OK/Cancel dialogs); they are locked while an exercise runs,
  because Java also only read them when an exercise started.
- **Live feedback** during practice (correct/wrong counters, optionally the current note). The Java
  run dialog showed nothing.
- Velocity (was only editable in the XML file) and memory size ("Remember N predecessors" existed
  in the Java dialog but was not connected) are now editable.

### Left out (not functional in Java either)
- "Direction" (linear/random/alternating) and "transpositions" are stored and editable but no
  algorithm uses them, just like in Java. The UI says so.
- Unconnected Java widgets: "MIDI Thru", "Delete learned sequences after mastering them N times",
  "Count transposed sequences", `RecordSequence` (marked "Does not work yet").
- The MIDI message decoder in `AddToCorrectorReceiver` (debug output only).

## Additions after the migration

### Microphone input with pitch detection (acoustic piano, single notes)

Preferences → Input → "Microphone". Detected notes enter the exercise as ordinary note-on
messages, so the exercise logic is the same as with a MIDI keyboard.

- `core/pitch/PitchDetector` – McLeod Pitch Method (McLeod & Wyvill 2005), NSDF via FFT, own
  implementation in common Kotlin (no native code, no GPL libraries such as aubio or TarsosDSP;
  Pure Data's fiddle~/sigmund~ would need libpd built natively for every platform).
- `core/pitch/NoteTracker` – one note per key stroke: onset = level jump of the newest 11.6 ms
  hop, then the pitch must agree over two windows. The window just before the stroke is removed
  from the power spectrum, so a still ringing previous note (legato, sustain pedal) does not merge
  with the new one into a lower common pitch. Latency about 50 ms. The Kammerton A is taken into account.
- `core/practice/OwnSoundGate` – without headphones the microphone hears the app's own notes;
  input is ignored while an app note sounds and 200 ms after it.
- Desktop capture: `app/shared/src/jvmMain/.../JavaSoundAudio.kt` (javax.sound.sampled, 44.1 kHz
  mono). Other platforms have the `AudioInputBackend` interface but no implementation yet
  (Android `AudioRecord`, iOS `AVAudioEngine`, web `getUserMedia` + AudioWorklet).
- "Test microphone" in Preferences shows the level and the recognised note with its cents deviation.
- Tests (`PitchDetectionTest`) use synthetic piano tones with inharmonic partials, a weak bass
  fundamental and hammer noise: every note 36–96, legato, sustain pedal, repeated keys, 442 Hz
  tuning, detuning, noise. They still need to be checked against recordings of a real piano.
- Two voices from the microphone are not supported yet (planned: harmonic-sum candidate search
  within the known range, fallback Spotify Basic Pitch).
- macOS asks for microphone permission for the app that starts the JVM (e.g. the terminal).

## Storage

Desktop data directory: `~/Library/Application Support/MusicBootCamp` (macOS),
`%APPDATA%\MusicBootCamp` (Windows), `$XDG_DATA_HOME/musicbootcamp` (Linux).
Override for test runs: `./gradlew :app:desktopApp:run -Pmusicbootcamp.dataDir=/some/dir`.

- `setup-<name>.json`: `{"version":1,"name":…,"settings":{…},"learnedSequences":{"MONOPHONIC":[[level 0 sequences],…,[level 4]]}}`,
  with notes as `60` and chords as `[60, 67]`. Your `learned_sequences_settings_lin.xml` shrinks from 3.4 MB to 0.4 MB.
- `preferences.json`: MIDI devices, Kammerton A, last used setup.
- Files are written through a temp file and an atomic move.

**Importing your Java data:** ⋮ → "Import setup from MusicBootCamp (Java)…" → choose e.g.
`settings_lin.xml`. The learned sequences are found the way Java did it: `learned_sequences_` +
the `current_settings_file_path_` stored inside the file. So importing `default_settings.xml` brings
the sequences of whichever setup was current last, in your case `settings_two_voices_mid_range`.
The import is one-way: practice done in the new app does not flow back into the XML files.

## Open points

- **Manual check at the keyboard** (cannot be automated):
  1. Import `settings_lin.xml` and `settings_two_voices_mid_range.xml`. Check that the Memory tab shows 25,142 and 2,597 sequences.
  2. Preferences: choose your keyboard as MIDI In, your synth (or Gervill) as MIDI Out.
  3. Go! in monophonic mode: the start note sounds, the note length follows the sustain setting, and correct and wrong answers are counted properly.
  4. Two voices: play both notes (any order), and a unison with one key.
  5. Turn learning on, make mistakes on purpose, press Stop: "Stored" goes up, and the Memory tab count rises.
  6. Change Kammerton A while an exercise runs: the pitch shifts immediately.
  7. Close the app while an exercise is running, reopen it: the learned sequences are still there.
- **MIDI on other platforms:** Android (`android.media.midi`), iOS (CoreMIDI) and web (Web MIDI API,
  Chromium only) each need a `MidiBackend` and a persistent `DocumentStore`. The UI already runs there.
- **Two-voice sequence order** (see above): decide whether to reverse it. If you do, convert existing memories
  at the same time.
- The Mac here is Intel (`macos_x64`), a Kotlin/Native host that Kotlin marks as deprecated; iOS builds will need an Apple Silicon Mac in future.
