# Migration from the Java version

This project is a Kotlin Multiplatform / Compose Multiplatform port of the Swing
application in `~/Dropbox/MusicBootCampRepo/MusicBootCamp` (≈5,400 lines of Java,
28 files). This document records the decisions taken during the migration and
how the port was verified.

## Result at a glance

| | Java | Kotlin Multiplatform |
|---|---|---|
| UI | Swing dialogs (NetBeans form editor) | Compose Multiplatform, Material 3, adaptive (phone, tablet, desktop), English and German |
| Timing | `java.util.Timer` + `TimerTask` | Coroutines (`PracticeRunner`) |
| MIDI | `javax.sound.midi` | `MidiBackend` interface; desktop with `javax.sound.midi`, Android with `android.media.midi`, iOS with CoreMIDI, web with the Web MIDI API |
| Storage | Hand-written XML, one settings + one learned-sequences file per setup | Versioned JSON via kotlinx.serialization; one-way XML import |
| Platforms | Desktop (JVM) | Desktop, Android, iOS and web all have MIDI, microphone, sound and storage; desktop and Android are tested at the instrument, the web app in the browser without devices, iOS only compiles so far |
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
files, stores them through the file store and reloads them), `ScreenshotTest` (renders every screen
off-screen into `app/desktopApp/build/screenshots` in phone, tablet and desktop sizes and in German,
finds its way by the texts on screen and runs an exercise against a fake MIDI system).

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
  Memory screen shows how many), and random steps from outside the range always move towards it.
  Within the range the behaviour and the consumed random numbers are unchanged, so the golden master
  tests still pass. Tests: `CoreTest.learnedSequencesOutsideTheRangeDoNotLeadTheExerciseAway`,
  `CoreTest.randomStepsFindBackIntoTheRange`.
- **Late answers.** In Java a key press counted for whichever step was running
  when it arrived. Now a step still without an answer when the
  next note starts stays open for the late-answer tolerance (`PracticeSession.step(deferEvaluation
  = true)`, `PracticeRunner(lateAnswerTolerance = …)`); at a fast tempo several steps can be open.
  A key press answers the oldest open step — or, if it is exactly the note of a later open step,
  that one, and the older ones count as missed, so a skipped note or a false stroke does not shift
  all later answers by a step; a key press that is the note of a step just given up (too late) and
  of no open one is ignored for the same reason. (At first the tolerance was capped at half the step period and only
  the previous step could be answered; at 0.7 s breathing time answers 1.2 s after their note were
  then lost.) The next note still starts on time. A step answered in time is
  evaluated at once as before, so the exercise and the random numbers consumed are unchanged; only
  a deferred evaluation stores its mistake after the next note was chosen, so it can influence the
  steps after the next one only. The tolerance belongs to the setup (the exercise's settings, under
  Tempo: it goes with the breathing time) and applies to MIDI keyboards too, since late answers
  are the player's, not the microphone's; 0 is the Java behaviour. (At first it was a preference
  for the microphone only.) On a keyboard chords arrive key by key: one key answers a unison, as
  without tolerance, and keys that do not complete a chord count as a wrong answer when the
  step's time is over, without shifting later answers. Tests: `LateAnswerTest`.
- **Octaves count as correct with the microphone** (Settings → Input, on by default): a
  recognised note or chord with exactly the pitch classes of one of the last given ones counts as
  that one (`matchIgnoringOctaves`). The recognition confuses octaves far more often than the
  player (weak bass fundamentals of real pianos; a unison heard with its octave), so this removes
  many false mistakes; playing in the wrong octave is no longer caught. MIDI input is unchanged.
- **The microphone is the default input** (Settings → Input): most players sit at an acoustic
  piano, and it needs no device. Plugging in (or connecting) a MIDI keyboard switches the input
  to it; without a microphone the keyboard is the input from the start.
- **Given notes hidden by default.** The point is to hear them; "Show notes" on the Practice screen
  reveals them (stored in `preferences.json`).
- **Settings edited in place** (no OK/Cancel dialogs); they are locked while an exercise runs,
  because Java also only read them when an exercise started.
- **Live feedback** during practice (correct/wrong counters, optionally the current note). The Java
  run dialog showed nothing. Right or wrong shows as soon as the answer is certain
  (`Corrector.verdict`, `PracticeSession.takeVerdicts`), "nothing heard" instead of "wrong" for a step without any key or recognised note (it still counts as wrong), then "listening" while the next step waits;
  the evaluation itself still happens when the next step starts, as in Java, so the counters,
  the memory and the random numbers are unchanged. Tests: `ImmediateFeedbackTest`, `LiveResultTest`.
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

Settings → Input → "Microphone". Detected notes enter the exercise as ordinary note-on
messages, so the exercise logic is the same as with a MIDI keyboard.

- `core/pitch/PitchDetector` – McLeod Pitch Method (McLeod & Wyvill 2005), NSDF via FFT, own
  implementation in common Kotlin (no native code, no GPL libraries such as aubio or TarsosDSP;
  Pure Data's fiddle~/sigmund~ would need libpd built natively for every platform).
- `core/pitch/NoteTracker` – one note per key stroke: onset = level jump of the newest 11.6 ms
  hop, then the pitch must agree over two windows. The window just before the stroke is removed
  from the power spectrum, so a still ringing previous note (legato, sustain pedal) does not merge
  with the new one into a lower common pitch. Latency about 50 ms. The Kammerton A is taken into account.
- Removing the app's own sound (no headphones, "Gervill" as MIDI Out): the desktop app renders
  Gervill itself (`desktop/GervillSynth`, via `com.sun.media.sound.AudioSynthesizer.openStream`,
  which needs `--add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED`) and plays it through
  its own `SourceDataLine`, so it knows the samples the loudspeaker plays. The microphone port
  pairs each block with the same number of played samples on its reading thread
  (`AudioInputPort.blocksWith`). `core/pitch/EchoEstimator` predicts the app's sound in the
  microphone from them, on power spectra: the delay by votes (after each onset of the reference,
  the lag of the steepest rise in the microphone; the player's much louder strokes made a
  correlation of whole envelopes fail in real recordings), a gain per 1/6 octave
  as the median ratio microphone/reference (learned only while the player is not playing),
  reverberation as a slowest decay, times 2 as a margin. `NoteTracker` then detects strokes on
  what exceeds the prediction (and requires the total level to rise, and near the app's own
  onsets to stand out against its sound), and `PitchDetector` removes the predicted spectrum.
  Until the prediction is ready (a few notes into an exercise; in a room where the app is too
  quiet in the microphone to measure, never), `OwnSoundGate` applies instead: only the app's
  current note (and octaves) is ignored, so late answers to the previous note count at once.
  (At first input was simply blocked then, which in real recordings — app 20 dB below the piano,
  delay never found — blocked every other run completely; a level threshold learned from the
  microphone, `fallbackMargin`, now off by default, failed when the player plays all the time.)
  Within 400 ms after a recognised note, a new stroke over the app's sound must raise the total
  level by `followUpRise` (2.5×): the rest of the note beats with the app's sound, and one such
  false stroke once shifted all later answers by a step. With the prediction ready every note
  counts, including the one the app is playing. With other outputs
  `OwnSoundGate` below is used. Tests: `EchoCancellationTest` (synthetic room with reflections,
  reverberation and noise). The NSDF is now capped at 1: after removing a background, long lags
  could exceed it and push the true first peak below the threshold (sub-octave errors); without a
  background it is ≤ 1 anyway.
- `core/practice/OwnSoundGate` – without headphones the microphone hears the app's own notes.
  While an app note sounds and 200 ms after it, detected notes are ignored if they are that note
  or an octave of it (a typical detection error); other notes count. (At first all input was
  ignored then, which left too little time at fast tempos: at 1 s breathing time and 50 % sustain
  only the last 300 ms of a step.) A correct answer played while the same note still sounds is
  still ignored; subtracting the app's own sound from the microphone signal is planned.
- Late answers: see "Deliberate changes". The tolerance (the exercise's settings → Tempo → "Late answers",
  0–1000 ms, default 150 ms, `lateAnswerToleranceMillis` in the setup's settings) counts from the key stroke:
  `NoteTracker.detectionDelay` (about 58 ms) is added, because the note reaches the exercise only
  once it is recognised. Audio buffering (one 11.6 ms block on the desktop) is not added.
- Recording (Settings → Recognition → "Record exercises", off by default): `core/audio/SessionRecorder` writes
  the audio the detection gets as a WAV file and a JSON log (`RecordingLog`: app note on/off,
  steps, detected and accepted notes, evaluations, each with its sample position) to
  `recordings/session-<date>_<time>.wav/.json` in the data directory; with the app's own sound
  removed, the WAV has a second channel with what the loudspeaker played. Exercise events are placed at
  the end of the audio received so far (±1 block). `RecordingReplayTest` replays them through
  `NoteTracker` (see README). Recordings are personal data: never commit them.
- `preferences.json` stores the detection parameters with `detectionParametersRevision`; values
  from an older revision are reset to the current defaults on loading, so a default changed later
  (e.g. `fallbackMargin` 2 → 0) does not linger.
- Optimization mode (Settings → Recognition): every exercise stops by itself after n notes
  (`PracticeRunner(maxSteps = …)`; no next note follows the last one, so its answer may come up
  to one step period after the tolerance)
  and is recorded; the detection parameters (`core/pitch/DetectionParameters`, stored in
  `preferences.json`, defaults = the tuned constants) can be edited, and the Practice screen lists
  the last run step by step (`summarize`), so the player can report where they played something
  else. Recordings store the parameters they were made with. `RecordingReplayTest` analyses
  recordings: a step table with each recognition mistake classified, a CSV of every hop's
  decisions (`NoteTracker.trace`) and a spectrogram PNG (microphone, reference, predicted own
  sound, levels), optionally a parameter sweep (see README).
- Desktop capture: `app/shared/src/jvmMain/.../JavaSoundAudio.kt` (javax.sound.sampled, 44.1 kHz
  mono); Android, iOS and web: see below.
- "Test microphone" in Settings → Input shows the level and the recognised note with its cents deviation.
- Tests (`PitchDetectionTest`) use synthetic piano tones with inharmonic partials, a weak bass
  fundamental and hammer noise: every note 36–96, legato, sustain pedal, repeated keys, 442 Hz
  tuning, detuning, noise. They still need to be checked against recordings of a real piano.
- Several voices from the microphone: see "Chords from the microphone" below.
- macOS asks for microphone permission for the app that starts the JVM (e.g. the terminal).

### Chords from the microphone (exercises with several voices)

A separate branch, parametrised apart from single notes (`ChordDetectionParameters`, stored per
number of voices in `preferences.json`; editable in optimization mode when the exercise has
several voices).

- `core/pitch/ChordTracker` finds strokes with a `NoteTracker` (which also learns and removes the
  app's own sound); strokes within `chordSpreadMillis` (80 ms) are one chord, and during a
  chord's analysis window only a clearly louder stroke (2×) starts anew (the beating of close
  notes looks like strokes). The spectrum is averaged over 30–300 ms after the stroke (FFT 8192,
  Hann): the slower tempo of these exercises allows it, and it separates close partials down in
  the bass. What rang before the stroke and the app's predicted sound are removed; a window
  bringing less than `minNewShare` new level is no stroke.
- `core/pitch/TemplateChordRecognizer` works on a log-frequency axis (`LogSpectrum`, 3 bins per
  semitone). A hypothesis (a set of notes) is fitted as a non-negative mix of note templates
  (`Nnls`, Lawson–Hanson); its score is the unexplained part plus `notePenalty` per note. The
  hypotheses are the chords given lately and their near misses (a note ±1, ±2 semitones or an
  octave off, or left out) plus combinations of the strongest notes of one fit with all templates
  of the range — so the work grows about linearly with voices and range instead of with all
  combinations (4 voices in 4 octaves: tens of hypotheses instead of 200 000).
- `core/pitch/PianoTemplates`: a piano model (inharmonic partials, weak bass fundamental,
  partials decaying faster the higher they are) run through the same FFT and log mapping as the
  measurement, or the player's piano: Settings → Recognition → optimization mode → "Calibrate piano" asks
  for every note of the range once (`PianoCalibration`, measured exactly like a chord; a stroke
  of the asked pitch class counts in any octave — the model heard a real C3 as C4 —, another
  note is asked again) and stores `piano-templates.json` in the data directory; notes without one use the
  nearest learned note (within 6 semitones) moved along the log axis.
- `core/pitch/HarmonicChordRecognizer` (the default, `method = HARMONIC`): tells chords apart
  by **where** partials are, not how strong — the room and the microphone's position change single
  partials by many dB (the player's measurements; in the recordings the fundamental/second-partial
  ratio jumped from key to key). The log spectrum is divided by its envelope (levelling out the
  colouring); a chord's score is the share of clear peaks its partials explain (shared partials
  once) beyond chance (`chanceWeight` × the share of the spectrum its partials cover — a deep
  note's dense comb catches peaks by chance), minus `missingPenalty` per needed partial that is
  missing (C3 needs its odd partials, which C4 lacks: octaves without strengths). If an expected
  chord is the best one plus notes lying entirely on its partials (a twelfth), the expected one is
  taken. A calibration only tells which of partials 1–6 a note shows. Compared with the templates
  (synthetic, octave errors counted as right as in the app): 150 / 146 / 145 / 144 of 150 without
  and with three simulated room colourings (±10 dB) against 150 / 136 / 127 / 131; on real strokes
  207 single notes right with no octave error against 150 with 53 (model templates), and a real
  two-voice run 9 of 12 against 8 (templates learned from the player's piano), the rest being
  heard alike by both.
- A recognised chord goes to the exercise as one note-on per voice (a unison or a missing note
  repeats a note), so the two-voice corrector evaluates it unchanged; with a late-answer
  tolerance the open-step queue takes a chord as one answer. Until the app's sound is removed,
  `OwnSoundGate.acceptsChord` ignores a chord whose notes could all be the app's own.
- Recordings log the voices, the range and the chord parameters; `RecordingReplayTest` replays
  them by chord (`-Pmusicbootcamp.templates=…` for a calibration, `played=4=60+67`, `sweep=true`).
- Tests (`ChordRecognitionTest`, synthetic piano): 149 of 150 intervals up to two octaves from
  C2 (the exception: a fifth deep in the bass read an octave high), wrong chords recognised as
  played, unison, triads, chords over the app's own chords with room echo, calibration.
- Checked against real strokes (single-note recordings through the chord path with
  `-Pmusicbootcamp.voices=1`, and two-note chords mixed from them): the piano model reads notes
  below C4 an octave too high — on a real upright through a room microphone the fundamental there
  is far weaker than the second partial, and it varies from note to note, so no simple model fits.
  Templates learned from the player's own strokes (cross-validated: learned from half of the
  recordings, tested on the other half) removed the octave errors on single notes (50 → 1) and
  raised mixed real chords from 25 % to 78 % correct, with 1 % of wrong answers accepted as
  right. Hence the calibration; `-Pmusicbootcamp.learnTemplates=<file>` learns templates from
  single-note recordings as a start. Hypotheses stay within the exercise range ±2 semitones: which
  wrong chord it was does not matter, and octave ambiguity outside it only added errors.
  `givenBias` > 0 hardly raised the hits and let more wrong answers pass, so it defaults to 0.

### Android: microphone, MIDI and the app's own piano

`app/shared/src/androidMain/.../android/` (created by `androidServices` in `MainActivity`):

- **Sound:** Android has no real-time synthesizer to send MIDI to, so the app plays its own
  piano (see "The app's own piano: a sampler" below) through an `AudioTrack`: `AndroidPianoSynth`, the MIDI
  output "MusicBootCamp Piano". Being rendered by the app, it is a `RenderedSynth` like Gervill, so
  its sound is removed from the microphone signal the same way; both keep what they played in
  `PlayedAudioBuffer`.
- **Microphone:** `AndroidAudioInput` with `AudioRecord`, 44.1 kHz mono float, from the
  "unprocessed" source where the device has one, else the speech-recognition source (neither has
  the gain control and noise suppression of calls). The permission is asked for when the
  microphone is first needed (`AudioInputBackend.hasAccess` / `requestAccess`, used by
  `AppController.withMicrophoneAccess`).
- **MIDI:** `AndroidMidiBackend` with `android.media.midi` (USB keyboards, other apps' MIDI
  services); bytes are parsed by `core/midi/MidiParser` (running status, split chunks, system
  messages skipped). Devices open on a MIDI thread of their own.
- **Import of Java setups:** the system's file dialog only gives access to picked files, so the
  settings XML and its `learned_sequences_…` XML are picked together (`AndroidLegacyFilePicker`).
- **Found with the first recordings from a phone** (two voices, phone on the piano):
  - The output tore audibly: the low-latency `AudioTrack` has a buffer of a few milliseconds, which
    ran dry whenever the renderer waited for the processor (chord analysis). Now a normal track
    with ~190 ms of buffer and a renderer thread at audio priority.
  - Now and then the app's own chord was taken for the answer: when the microphone reader was
    held up, its blocks arrived in a burst, and `PlayedAudioBuffer` (aligning the reference by the
    play head at the time of reading) jumped ahead and back, so the echo cancellation was off for
    that step. It now only resyncs when it has been out of step for a second (samples really
    lost); the Android reader queues blocks without limit, has a second of system buffer and
    audio priority, so it is not held up by the analysis. Test: `PlayedAudioBufferTest`.
  - The answer to the first step was ignored while the app's note sounded (three seconds at that
    tempo; at 1 s per step with single notes the same happened to answers 0.55 s after the note):
    before the app's sound can be removed, `OwnSoundGate` dropped everything matching the app's
    notes while they sounded. A note is now the app's own only within 0.45 s of the app's attack,
    a chord within 0.9 s: the recognition only sees what a stroke adds, and the app's note has
    one attack.
  - Recordings now log `outputUnderruns` and `referenceResyncs` (`RenderedOutputPort.diagnostics`).
- **Storage:** setups in the app's private files; recordings in
  `Android/data/io.github.jonnyfrick.musicbootcamp/files/recordings`, reachable over USB.
- **Lifecycle:** rotating or resizing keeps the activity (`configChanges`), so an exercise keeps
  running; leaving the app stops it (Android silences background microphones anyway); the screen
  stays on while an exercise or a calibration runs.
- **Devices:** the device lists refresh whenever a choice opens (no refresh button any more), and a
  keyboard plugged in while the app runs becomes the MIDI input at once, on every platform (the
  output stays, so the app's own synthesizer keeps removing its sound). Test: `DeviceHotplugTest`.
- Tests: `PianoSynthTest` (pitch as our recognition hears it, tuning, pedal, full scale, parser).

### Answering on other instruments (winds)

The recognition was built for the piano. Settings → Input → "Instrument" (`PlayerInstrument`,
in the preferences) tells it what the player answers on; `InstrumentProfile` holds what differs:

- **Settling:** a blown note scoops into its pitch. With the piano's settings (pitch read 35 ms
  after the attack) real trumpet, trombone and clarinet tones came out a semitone off; winds wait
  90 ms and need four agreeing windows.
- **One attack:** a held tone swells slowly, which looked like several attacks (the same note two
  or three times). The same note again within half a second, without the level having dropped,
  is still that attack.
- **Legato:** a clear pitch other than the note sounding, held for ~70 ms, is a new note without
  a new attack (`NoteTracker.followPitch`). Only where the app's own sound cannot be taken for
  it: with headphones or when the app renders (and so removes) its sound.
- **Chords:** no stretching of partials; which low partials a note must show (winds 1–4, the
  clarinet 1 and 3, the flute only its fundamental); the analysis window starts 100 ms after the
  attack, and attacks within 150 ms are one chord (players never start exactly together).
- **Transposition:** the exercise always works in sounding pitch (played by ear). "Show notes as
  written" only changes how notes are displayed (`AppController.noteName`).

Tested on real tones (VSCO 2 Community Edition, CC0; `tools/prepare_wind_test_samples.py`,
`WindRecognitionTest`): every single tone of trumpet, trombone, clarinet and flute is heard once
and right (18 of 42 were wrong with the piano's settings), slurs up and down, pairs and triads
of one instrument, trombone with trumpet. Not tested: live players (vibrato, intonation, room),
oboe, saxophones and horn (they use the general wind profile), and the trombone's lowest fifth
in chords.

### The app's own piano: a sampler

Android, iOS and browsers have no real-time synthesizer, and only a sound the app renders itself
can be removed from the microphone signal. So the app has its own instrument, the MIDI output
"MusicBootCamp Piano" (`BUILT_IN_PIANO`) on every platform, also on the desktop next to Gervill
(`JavaSoundPiano`); `SynthMidiBackend` adds it to a platform's MIDI devices.

- **Samples:** the Salamander Grand Piano V3 by Alexander Holm (CC BY 3.0; credited in Settings →
  MIDI devices and in `files/piano/LICENSE.txt`). The app plays every note with one velocity, so
  one of the 16 velocity layers is enough: 30 samples (every minor third from A0 to C8), mono,
  44.1 kHz, trimmed to 2.5–7 s, 2.7 MB as FLAC in the app's resources. `tools/prepare_piano_samples.py`
  makes them from the original files.
- **Code:** `core/audio/Flac` decodes FLAC in common code (bit-exact: `FlacTest` checks the MD5 the
  encoder stored), `SampleSet` holds the recordings, `Sampler` plays a note from the nearest
  recording, re-pitched, and `VoiceSynth` is what it shares with the synthetic `PianoSynth` (MIDI
  handling, sustain pedal, pitch bend for the reference A, mixing). `Instruments` (app) decodes the
  samples in the background when the app starts; until then, or if that fails, notes are synthetic.
- **More instruments** are further `SampleSet`s: prepare the samples, add them to the resources and
  to `Instruments`.
- Tests: `FlacTest`, `SamplerTest`, `InstrumentsTest` (every note from C2 to C7 of the shipped
  samples is heard as itself by the app's pitch detection; also writes `build/piano-demo.wav`).

### iOS, web and Bluetooth MIDI

- **Bluetooth MIDI** (`BluetoothMidiConnector`, Settings → MIDI devices → "Connect Bluetooth
  MIDI…" where the app has to connect devices itself): Android shows the system's companion-device
  dialog filtered to the MIDI service (`AndroidBluetoothMidi`; no location or scan permission, only
  `BLUETOOTH_CONNECT` from Android 12) and keeps the connection open in `AndroidMidiBackend`; iOS
  shows Apple's `CABTMIDICentralViewController`. A connected device appears in the device lists
  and is chosen like one plugged in. On macOS, Windows and Linux the system pairs such devices
  (macOS: Audio MIDI Setup → Bluetooth) and they are ordinary MIDI devices; in the browser too.
- **iOS** (`app/shared/src/iosMain/.../ios/`, `iosServices`): CoreMIDI with hot-plug notifications
  (`IosMidiBackend`, bytes through `MidiParser`); one `AVAudioEngine` for the microphone (a tap on
  the input node, audio session in measurement mode: no call processing) and the app's piano
  (an `AVAudioSourceNode`), which is a `RenderedSynth` as on Android; setups in Application Support.
  `Info.plist` has the usage texts for microphone and Bluetooth.
- **Web** (`app/shared/src/webMain/.../web/WebServices.kt`, `webServices`, the same code for
  JavaScript and WebAssembly): Web MIDI API (not in Safari; without it the microphone and the piano
  still work), `getUserMedia` without echo cancellation, noise suppression and gain control, the
  app's piano through the Web Audio API (also a `RenderedSynth`), setups in local storage. The
  browser side is a few small JavaScript functions exchanging only numbers, strings and callbacks.
  Sound goes through two audio worklets on the browser's audio thread: the piano is rendered on
  the main thread a quarter of a second ahead and queued in "mbc-player", and "mbc-recorder" posts
  the microphone's blocks, which wait in the message queue while the main thread is busy. (At
  first both used `ScriptProcessorNode` on the main thread; with the recognition running there
  too, the sound tore audibly whenever a chord was analysed.)

### Developer tools only in debug builds

The import of Java XML setups and everything for tuning the recognition (Settings → Recognition:
recording, optimization mode, detection parameters, calibration) are developer tools
(`PlatformServices.debugTools`). On: desktop runs from Gradle (`run` sets `musicbootcamp.debug`;
`-Pmusicbootcamp.debug=false` shows the app as users get it) and debuggable Android builds
(`installDebug`). Off: the packaged desktop app and Android release builds. Without them the menu
item and the settings page are gone, and the recognition always uses its default parameters,
never records and ignores a calibration, whatever a debug build stored on the device
(`AppController.tuning`). Test: `ScreenshotTest.aReleaseBuildHasNoDeveloperTools`.

### Adaptive user interface (phones, tablets, desktop)

The first UI mirrored the Java dialogs as tabs of one desktop window. It now follows Material 3's
patterns so that the same `commonMain` code fits every window size:

- **Navigation:** three destinations (Practice, Memory, Settings) in `NavigationSuiteScaffold`, a
  bottom bar on phones and a rail beside the content from medium width on, chosen by window size
  class, not by platform.
- **Practice:** Start / Stop is the floating action button. The exercise's settings (Java: the
  Random options) open in a bottom sheet on phones and stay open in a side pane on wide windows
  (`isWideWindow`, from 840 dp). The setup switcher is the app bar's title, its actions are in the
  overflow menu.
- **Settings:** list-detail (`ListDetailPaneScaffold`): pages Input, MIDI devices, Tuning and
  Recognition; one at a time with back navigation on phones, side by side on wide windows.
- **Building blocks** in `Components.kt` (`SwitchSetting`, `SliderSetting`, `RadioSetting`,
  `ChoiceSetting`, `NavigationSetting`, `ButtonRow`, `CenteredColumn`): one row per setting with the
  explanation as supporting text, whole rows as touch targets, semantics for screen readers, no
  fixed widths. Sliders keep every value in range, so the Java dialogs' corrections of typed
  values ("You are not a bat!") no longer occur; − / + buttons give exact values on small screens.
  The range is one slider with two thumbs.
- **Languages:** all texts, including the controller's messages (`UiText`), are Compose resources
  in English and German (`composeResources/values*/strings.xml`), following the system language.
  Error texts from the platform stay as they are.
- **Theme:** own light and dark colour scheme, Android 12+ uses the wallpaper's colours
  (`platformColorScheme`); right answers have their own colour next to Material's error colour and
  are also shown by icon and word.
- Icons are Material Symbols as path data (`Icons.kt`): the material-icons libraries are no longer
  published for Compose Multiplatform.

## Storage

Desktop data directory: `~/Library/Application Support/MusicBootCamp` (macOS),
`%APPDATA%\MusicBootCamp` (Windows), `$XDG_DATA_HOME/musicbootcamp` (Linux).
Override for test runs: `./gradlew :app:desktopApp:run -Pmusicbootcamp.dataDir=/some/dir`.

- `setup-<name>.json`: `{"version":2,"name":…,"settings":{…},"learnedSequences":{"MONOPHONIC":[[level 0 sequences],…,[level 4]]}}`,
  with notes as `60` and chords as `[60, 67]`. Your `learned_sequences_settings_lin.xml` shrinks from 3.4 MB to 0.4 MB.
- Format versions: 2 moved the late-answer tolerance from the preferences into each setup's
  settings; a format-1 setup takes over the preferences' value when it is loaded
  (`SetupRepository.load`, test `SetupFormatTest`) and is saved as format 2 from then on.
- `preferences.json`: MIDI devices, Kammerton A, last used setup.
- Files are written through a temp file and an atomic move.

**Importing your Java data:** ⋮ → "Import setup from MusicBootCamp (Java)…" → choose e.g.
`settings_lin.xml`. The learned sequences are found the way Java did it: `learned_sequences_` +
the `current_settings_file_path_` stored inside the file. So importing `default_settings.xml` brings
the sequences of whichever setup was current last, in your case `settings_two_voices_mid_range`.
The import is one-way: practice done in the new app does not flow back into the XML files.

## Open points

- **Manual check at the keyboard** (cannot be automated):
  1. Import `settings_lin.xml` and `settings_two_voices_mid_range.xml`. Check that the Memory screen shows 25,142 and 2,597 sequences.
  2. Settings → MIDI devices: choose your keyboard as MIDI In, your synth (or Gervill) as MIDI Out.
  3. Start in monophonic mode: the start note sounds, the note length follows the sustain setting, and correct and wrong answers are counted properly.
  4. Two voices: play both notes (any order), and a unison with one key.
  5. Turn learning on, make mistakes on purpose, press Stop: "Stored" goes up, and the Memory screen count rises.
  6. Change Kammerton A while an exercise runs: the pitch shifts immediately.
  7. Close the app while an exercise is running, reopen it: the learned sequences are still there.
- **Developer tools on the web are on for everyone, temporarily** (`debugTools = true` in
  `WebServices.kt`), to tune the recognition in browsers; recordings come as downloads there
  (`DownloadRecordingStore`). Set it back to false when that is done.
- **Piano calibration for users?** It is a debug-only tool for now because the harmonic chord
  recognition does well without it. If recognition turns out weaker on other pianos, rooms or
  phones, calibration could help and would then move out of the developer tools (into
  Settings → Input, with a simpler flow).
- **Untested platform code:** iOS (never run: this Mac cannot build for an iPhone), Bluetooth MIDI
  on Android and iOS, and microphone and MIDI in the browser need a first test with real devices.
- **Web: the recognition runs on the main thread.** Playing and recording are on the audio
  thread now, so they no longer tear; the analysis still shares the main thread with the UI. If
  the UI stutters while chords are analysed, move the analysis into a Web Worker.
- **iOS:** no recording store yet (so no optimization mode), no import of Java setups, and the
  microphone tap delivers blocks of about 100 ms, which delays recognition accordingly.
- **Two-voice sequence order** (see above): decide whether to reverse it. If you do, convert existing memories
  at the same time.
- The Mac here is Intel (`macos_x64`), a Kotlin/Native host that Kotlin marks as deprecated; iOS builds will need an Apple Silicon Mac in future.
