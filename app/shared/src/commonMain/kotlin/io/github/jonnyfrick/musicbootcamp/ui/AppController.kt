package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jonnyfrick.musicbootcamp.resources.*
import io.github.jonnyfrick.musicbootcamp.core.audio.SessionRecorder
import io.github.jonnyfrick.musicbootcamp.core.audio.StepSummary
import io.github.jonnyfrick.musicbootcamp.core.learning.LearnedSequences
import io.github.jonnyfrick.musicbootcamp.core.legacy.LegacyImport
import io.github.jonnyfrick.musicbootcamp.core.midi.Tuning
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeSettings
import io.github.jonnyfrick.musicbootcamp.core.persistence.AppPreferences
import io.github.jonnyfrick.musicbootcamp.core.persistence.Setup
import io.github.jonnyfrick.musicbootcamp.core.persistence.SetupRepository
import io.github.jonnyfrick.musicbootcamp.core.practice.PracticeRunner
import io.github.jonnyfrick.musicbootcamp.core.practice.PracticeStatus
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeMode
import io.github.jonnyfrick.musicbootcamp.core.persistence.InputSource
import io.github.jonnyfrick.musicbootcamp.core.persistence.OPTIMIZATION_STEP_RANGE
import io.github.jonnyfrick.musicbootcamp.core.pitch.AudioBlock
import io.github.jonnyfrick.musicbootcamp.core.pitch.DetectedNote
import io.github.jonnyfrick.musicbootcamp.core.pitch.DetectionParameters
import io.github.jonnyfrick.musicbootcamp.core.pitch.toJson
import io.github.jonnyfrick.musicbootcamp.core.pitch.NoteTracker
import io.github.jonnyfrick.musicbootcamp.core.pitch.detectNotes
import io.github.jonnyfrick.musicbootcamp.core.pitch.detectedNotes
import io.github.jonnyfrick.musicbootcamp.core.pitch.toNoteOn
import io.github.jonnyfrick.musicbootcamp.core.practice.OwnSoundGate
import io.github.jonnyfrick.musicbootcamp.platform.AudioInputPort
import io.github.jonnyfrick.musicbootcamp.platform.Instruments
import io.github.jonnyfrick.musicbootcamp.platform.MidiInputPort
import io.github.jonnyfrick.musicbootcamp.platform.MidiOutputPort
import io.github.jonnyfrick.musicbootcamp.platform.PlatformServices
import io.github.jonnyfrick.musicbootcamp.platform.RenderedOutputPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.MutableStateFlow
import io.github.jonnyfrick.musicbootcamp.core.pitch.toNoteOns
import io.github.jonnyfrick.musicbootcamp.core.pitch.detectedChords
import io.github.jonnyfrick.musicbootcamp.core.pitch.matchIgnoringOctaves
import io.github.jonnyfrick.musicbootcamp.core.pitch.LearnedTemplates
import io.github.jonnyfrick.musicbootcamp.core.pitch.ChordDetectionParameters
import io.github.jonnyfrick.musicbootcamp.core.pitch.PianoCalibration
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * UI state and actions of the app (Java: `MusicBootCampMainWindow`, `ParameterManager`
 * and the dialogs' OK handlers). Holds Compose state, so the UI recomposes on changes.
 *
 * Differences to the Java version: settings apply immediately and are saved
 * automatically; learned sequences are saved when an exercise stops and on exit.
 */
class AppController(
    private val services: PlatformServices,
    private val scope: CoroutineScope,
) {
    private val repository = SetupRepository(services.documents)
    private var setup: Setup = Setup(DEFAULT_SETUP_NAME)

    var ready by mutableStateOf(false)
        private set
    var setupNames by mutableStateOf(listOf<String>())
        private set
    var setupName by mutableStateOf(DEFAULT_SETUP_NAME)
        private set
    var settings by mutableStateOf(PracticeSettings())
        private set
    var preferences by mutableStateOf(AppPreferences())
        private set

    /** Sequences per priority level in the memory of the current mode. */
    var memoryCounts by mutableStateOf(List(LearnedSequences.PRIORITY_LEVELS) { 0 })
        private set
    /** Learned sequences of the current mode that reach outside the current range and are skipped. */
    var sequencesOutsideRange by mutableStateOf(0)
        private set
    var status by mutableStateOf(PracticeStatus())
        private set
    var inputDevices by mutableStateOf(listOf<String>())
        private set
    var outputDevices by mutableStateOf(listOf<String>())
        private set

    /** A message for the user (snackbar); cleared by [dismissMessage]. */
    var message by mutableStateOf<UiText?>(null)
        private set

    /** True from "Go" until the exercise has stopped and everything is saved. */
    var running by mutableStateOf(false)
        private set

    val midiUnavailableReason: String? get() = services.midi.unavailableReason
    /** Debug builds only: the Java import and the tools for tuning the recognition are available. */
    val debugTools: Boolean get() = services.debugTools
    val canImportLegacyFiles: Boolean get() = debugTools && services.legacyFiles != null

    /**
     * The preferences as far as they tune the recognition: in a release build always the
     * defaults, never recording, whatever a debug build may have stored on this device.
     */
    private val tuning: AppPreferences
        get() = if (debugTools) preferences else preferences.copy(
            recordMicrophone = false,
            optimizationMode = false,
            detectionParameters = DetectionParameters(),
            chordDetectionParameters = emptyMap(),
        )

    var audioInputDevices by mutableStateOf(listOf<String>())
        private set

    /** Microphone level (RMS, 0..1) while the microphone is open. */
    var microphoneLevel by mutableStateOf(0.0)
        private set
    var micTesting by mutableStateOf(false)
        private set
    var micTestNote by mutableStateOf<DetectedNote?>(null)
        private set

    val audioUnavailableReason: String? get() = services.audio.unavailableReason

    private var runner: PracticeRunner? = null
    private var output: MidiOutputPort? = null
    private var gate: OwnSoundGate? = null
    private var lateAnswerTolerance = Duration.ZERO
    private var recorder: SessionRecorder? = null

    /** The chords given lately, for the chord recognition (read from the audio thread). */
    private val expectedChords = MutableStateFlow<List<List<Int>>>(emptyList())

    /** The player's piano as measured by calibration; the chord recognition's templates. */
    var learnedTemplates by mutableStateOf(LearnedTemplates())
        private set
    private var recordingInfo: Map<String, String> = emptyMap()

    /** Step by step what the last run in optimization mode recorded; shown on the Practice tab. */
    var runSummary by mutableStateOf<RunSummary?>(null)
        private set

    /** Fixed run length in optimization mode (microphone only); null = until stopped. */
    private val optimizationSteps: Int?
        get() = preferences.optimizationSteps.takeIf {
            tuning.optimizationMode && preferences.inputSource == InputSource.MICROPHONE
        }
    private val closers = mutableListOf<() -> Unit>()
    private var micTestPort: AudioInputPort? = null
    private var micTestJob: Job? = null

    var midiTesting by mutableStateOf(false)
        private set

    /** Key presses received during the MIDI test, newest first. */
    var midiTestNotes by mutableStateOf(listOf<MidiMessage>())
        private set
    private var midiTestInput: MidiInputPort? = null
    private var midiTestOutput: MidiOutputPort? = null
    private var midiTestJob: Job? = null
    private var statusJob: Job? = null
    private var saveJob: Job? = null
    private var preferencesJob: Job? = null

    fun load() {
        launchSafely {
            preferences = repository.loadPreferences().let {
                // Where there is no microphone (or no access to one at all), the keyboard is the input.
                if (it.inputSource == InputSource.MICROPHONE && services.audio.unavailableReason != null) it.copy(inputSource = InputSource.MIDI) else it
            }
            if (debugTools) learnedTemplates = repository.loadTemplates()
            val names = repository.setupNames()
            val initial = preferences.lastSetup?.takeIf { it in names } ?: names.firstOrNull()
            setup = initial?.let { runCatching { repository.load(it) }.getOrNull() } ?: Setup(DEFAULT_SETUP_NAME).also { repository.save(it) }
            refreshSetupState()
            setupNames = repository.setupNames()
            refreshDevices()
            ready = true
        }
        launchSafely { services.midi.devicesChanged.collect { onMidiDevicesChanged() } }
        // The piano's samples: decoded in the background; until then its notes are synthetic.
        scope.launch {
            runCatching { withContext(Dispatchers.Default) { Instruments.load() } }
            pianoSampled = Instruments.isPianoSampled
        }
    }

    /**
     * A MIDI device was plugged in or removed: update the lists and say what changed. A keyboard
     * plugged in is meant to be played on, so it becomes the input at once (the output stays:
     * the app's own synthesizer can remove its sound from the microphone, a keyboard's cannot).
     */
    private fun onMidiDevicesChanged() {
        val inputsBefore = inputDevices.toSet()
        val before = (inputDevices + outputDevices).toSet()
        refreshDevices()
        val after = (inputDevices + outputDevices).toSet()
        val added = after - before
        val removed = before - after
        if (added.isEmpty() && removed.isEmpty()) return
        val newInput = inputDevices.firstOrNull { it !in inputsBefore && it != JAVA_SEQUENCER }
        if (newInput != null && !running) {
            selectInputDevice(newInput)
            setInputSource(InputSource.MIDI) // the default is the microphone
        }
        message = UiText.Joined(
            buildList {
                if (added.isNotEmpty()) add(text(Res.string.msg_midi_connected, added.joinToString()))
                if (newInput != null && !running) add(text(Res.string.msg_midi_chosen, newInput))
                if (removed.isNotEmpty()) add(text(Res.string.msg_midi_disconnected, removed.joinToString()))
                if (running && added.isNotEmpty()) add(text(Res.string.msg_restart_to_use))
            },
        )
    }

    // ------------------------------------------------------------------ setups

    fun updateSettings(transform: (PracticeSettings) -> PracticeSettings) {
        if (running) return
        setup.settings = transform(setup.settings)
        refreshSetupState()
        scheduleSave()
    }

    fun selectSetup(name: String) {
        if (running || name == setupName) return
        launchSafely {
            saveNow()
            val loaded = runCatching { repository.load(name) }.getOrElse {
                message = text(Res.string.msg_open_setup_failed, name, it.toUiText())
                null
            } ?: return@launchSafely
            setup = loaded
            refreshSetupState()
            updatePreferences { it.copy(lastSetup = name) }
        }
    }

    /** Java: "Save Setup As". Copies settings and learned sequences under a new name. */
    fun saveSetupAs(name: String) {
        if (running) return
        val cleanName = uniqueName(SetupRepository.sanitizeName(name))
        launchSafely {
            saveNow()
            setup = setup.copy(cleanName)
            repository.save(setup)
            setupNames = repository.setupNames()
            refreshSetupState()
            updatePreferences { it.copy(lastSetup = cleanName) }
        }
    }

    fun deleteCurrentSetup() {
        if (running) return
        launchSafely {
            saveJob?.cancel()
            repository.delete(setup.name)
            val remaining = repository.setupNames()
            setup = remaining.firstOrNull()?.let { repository.load(it) } ?: Setup(DEFAULT_SETUP_NAME).also { repository.save(it) }
            setupNames = repository.setupNames()
            refreshSetupState()
            updatePreferences { it.copy(lastSetup = setup.name) }
        }
    }

    /** Forgets every learned sequence of the current mode. */
    fun clearMemory() {
        if (running) return
        setup.memory().clear()
        refreshSetupState()
        scheduleSave()
    }

    /** Imports a Java settings file together with its learned sequences as a new setup. */
    fun importLegacySetup() {
        val picker = services.legacyFiles ?: return
        if (running) return
        launchSafely {
            val selection = runCatching { picker.pickSettingsFile() }.getOrElse {
                message = text(Res.string.msg_import_failed, it.toUiText())
                null
            } ?: return@launchSafely
            saveNow()
            val name = uniqueName(SetupRepository.sanitizeName(selection.suggestedName))
            val result = runCatching { LegacyImport.importSetup(name, selection.settingsXml, selection.readSibling) }
                .getOrElse {
                    message = text(Res.string.msg_import_failed, it.toUiText())
                    return@launchSafely
                }
            setup = result.setup
            repository.save(setup)
            setupNames = repository.setupNames()
            refreshSetupState()
            result.legacySettings.referenceAHz?.let { reference -> updatePreferences { it.copy(referenceAHz = reference) } }
            updatePreferences { it.copy(lastSetup = name) }

            message = UiText.Joined(
                buildList {
                    add(text(Res.string.msg_imported, name, setup.memory().size))
                    result.legacySettings.referenceAHz?.let { add(text(Res.string.msg_imported_reference_a, it)) }
                    result.warnings.forEach { add(UiText.Raw(it)) }
                },
            )
        }
    }

    // ---------------------------------------------------------------- practice

    /** Java: "Go!" in the run dialog. */
    fun startPractice() {
        if (running) return
        val microphone = preferences.inputSource == InputSource.MICROPHONE
        if (microphone && !withMicrophoneAccess(::startPractice)) return
        val problem = when {
            services.midi.unavailableReason != null -> services.midi.unavailableReason?.let(UiText::Raw)
            microphone && services.audio.unavailableReason != null -> services.audio.unavailableReason?.let(UiText::Raw)
            !settings.mode.isImplemented -> text(Res.string.msg_mode_not_implemented)
            settings.intervalPriorities.all { it == 0 } -> text(Res.string.intervals_problem)
            else -> null
        }
        if (problem != null) {
            message = problem
            return
        }
        stopMicTest()
        stopMidiTest()

        // Learned sequences are saved when the exercise stops; no background save may touch them meanwhile.
        saveJob?.cancel()
        val input = runCatching { if (microphone) openMicrophoneInput(settings) else openMidiInput() }.getOrElse {
            closePorts()
            message = text(Res.string.msg_open_input_failed, it.toUiText())
            return
        }
        val practiceOutput = (gate ?: output!!).let { port -> recorder?.recording(port) ?: port }
        recordingInfo = mapOf(
            "breathingTime" to settings.breathingTime.toString(),
            "sustain" to settings.sustain.toString(),
            "lateAnswerToleranceMillis" to settings.lateAnswerToleranceMillis.toString(),
            "usesHeadphones" to preferences.usesHeadphones.toString(),
            "referenceAHz" to preferences.referenceAHz.toString(),
            "midiOutputDevice" to (preferences.midiOutputDevice ?: ""),
            "optimizationSteps" to (optimizationSteps?.toString() ?: ""),
            "detectionParameters" to tuning.detectionParameters.toJson(),
            "voices" to settings.mode.voices.toString(),
            "octavesCountAsCorrect" to preferences.octavesCountAsCorrect.toString(),
            "range" to "${settings.lowLimit}..${settings.highLimit}",
            "chordDetectionParameters" to tuning.chordParameters(settings.mode.voices).toJson(),
        )
        runSummary = null

        val practice = PracticeRunner(
            scope, settings, setup.memory(), practiceOutput, input,
            lateAnswerTolerance = lateAnswerTolerance,
            maxSteps = optimizationSteps,
            onStep = { step ->
                recorder?.step(step)
                // The chords the player may be answering: the latest few (late answers).
                expectedChords.value = (expectedChords.value + listOf(step.given)).takeLast(EXPECTED_CHORDS)
            },
            onEvaluation = { recorder?.evaluation(it) },
        )
        runner = practice
        running = true
        statusJob = scope.launch {
            practice.status.collect {
                status = it
                if (it.finished && runner === practice) stopPractice()
            }
        }
        practice.start()
    }

    /** Java: "Stop" in the run dialog. */
    fun stopPractice() {
        val practice = runner ?: return
        runner = null
        launchSafely { stopAndSave(practice) }
    }

    /** Stops a running exercise and writes everything to disk; call before the app exits. */
    suspend fun shutdown() {
        stopMicTest()
        stopMidiTest()
        val practice = runner
        runner = null
        if (practice != null) stopAndSave(practice) else saveNow()
    }

    private suspend fun stopAndSave(practice: PracticeRunner) {
        statusJob?.cancel()
        practice.stop()
        status = practice.status.value
        finishRecording()
        closePorts()
        refreshSetupState()
        saveNow()
        running = false
    }

    /** Opens the MIDI output (tuned) and the MIDI keyboard; returns the keyboard's messages. */
    private fun openMidiInput(): Flow<MidiMessage> {
        lateAnswerTolerance = settings.lateAnswerToleranceMillis.milliseconds // a key press arrives at once
        openOutput()
        val inputName = preferences.midiInputDevice?.takeIf { it in inputDevices } ?: defaultDevice(inputDevices)
            ?: throw UserError(text(Res.string.msg_no_midi_input))
        val input = services.midi.openInput(inputName)
        closers += input::close
        return input.messages
    }

    /** Opens the MIDI output (tuned) and the microphone; returns the recognised notes as note-ons. */
    private fun openMicrophoneInput(settings: PracticeSettings): Flow<MidiMessage> {
        val audio = services.audio.open(preferences.audioInputDevice)
        closers += audio::close
        // Without headphones the microphone hears the app. If the app renders its synthesizer
        // itself, it knows what it played and removes that; until it can (and with other outputs),
        // input matching the app's current note is ignored.
        val parameters = tuning.detectionParameters
        val voices = settings.mode.voices
        val chordParameters = tuning.chordParameters(voices)
        val removeOwnSound = if (voices > 1) chordParameters.strokes.echoCancellation else parameters.echoCancellation
        val rendered = if (preferences.usesHeadphones || !removeOwnSound) null else openRenderedOutput(audio.sampleRate)
        val midiOutput = rendered ?: openOutput()
        val ownSound = if (preferences.usesHeadphones) null else OwnSoundGate(midiOutput)
        gate = ownSound
        // The tolerance counts from the key stroke; the note arrives only once it is recognised.
        // A chord is decided only after its analysis window.
        lateAnswerTolerance = settings.lateAnswerToleranceMillis.milliseconds + if (voices > 1) {
            chordParameters.windowEndMillis.milliseconds + NoteTracker(audio.sampleRate, parameters = chordParameters.strokes).detectionDelay
        } else {
            NoteTracker(audio.sampleRate, parameters = parameters).detectionDelay
        }
        val recording = if (tuning.recordMicrophone || tuning.optimizationMode) {
            val channels = if (rendered != null) listOf("microphone", "reference") else listOf("microphone")
            services.recordings?.let { SessionRecorder(it.create(), audio.sampleRate, channels) }
        } else {
            null
        }
        recorder = recording
        val blocks = if (rendered != null) audio.blocksWith(rendered::playedAudio) else audio.blocks.map { AudioBlock(it) }
        expectedChords.value = emptyList()
        if (voices > 1) {
            return blocks
                .onEach { block -> recording?.audio(listOfNotNull(block.microphone, block.reference)) }
                .detectedChords(
                    audio.sampleRate, voices, settings.lowLimit..settings.highLimit, preferences.referenceAHz,
                    chordParameters, learnedTemplates, expected = { expectedChords.value },
                    onLevel = { microphoneLevel = it },
                )
                .onEach { recording?.detectedChord(it) }
                .flowOn(Dispatchers.Default)
                .filter { chord -> chord.ownSoundRemoved || ownSound?.acceptsChord(chord.notes) ?: true }
                .map { chord -> chord.copy(notes = octaveMatch(chord.notes) ?: chord.notes) }
                .flatMapConcat { chord -> chord.toNoteOns(voices).asFlow() }
                .onEach { recording?.accepted(it) }
        }
        return blocks
            .onEach { block -> recording?.audio(listOfNotNull(block.microphone, block.reference)) }
            .detectedNotes(audio.sampleRate, preferences.referenceAHz, onLevel = { microphoneLevel = it }, parameters = parameters)
            .onEach { recording?.detected(it) }
            .flowOn(Dispatchers.Default)
            // Evaluated where the exercise runs, which is also where the gate sees the notes played.
            .filter { note -> note.midiNote in 0..127 && (note.ownSoundRemoved || ownSound?.accepts(note.toNoteOn()) ?: true) }
            .map { note -> note.copy(midiNote = octaveMatch(listOf(note.midiNote))?.first() ?: note.midiNote).toNoteOn() }
            .onEach { recording?.accepted(it) }
    }

    /** With octaves counting as correct: the given chord (or note) these notes are in another octave. */
    private fun octaveMatch(notes: List<Int>): List<Int>? =
        if (preferences.octavesCountAsCorrect) matchIgnoringOctaves(notes, expectedChords.value) else null

    /** Writes the header and the log of a recorded session; the audio has stopped by now. */
    private suspend fun finishRecording() {
        val recording = recorder ?: return
        recorder = null
        // Before the ports close: how the output fared (dropouts, lost alignment with the microphone).
        val diagnostics = (output as? RenderedOutputPort)?.diagnostics().orEmpty()
        runCatching { withContext(Dispatchers.Default) { recording.finish(recordingInfo + diagnostics) } }
            .onSuccess {
                message = text(Res.string.msg_recording_saved, recording.name)
                if (tuning.optimizationMode) runSummary = RunSummary(recording.name, recording.summary())
            }
            .onFailure { message = text(Res.string.msg_recording_failed, it.toUiText()) }
    }

    /**
     * The app's own rendering of the chosen MIDI output, if it can do that (its own piano, or
     * Gervill on the desktop), so its sound can be removed from the microphone signal; null otherwise.
     */
    private fun openRenderedOutput(sampleRate: Int): RenderedOutputPort? {
        refreshDevices()
        val synth = services.renderedSynths.firstOrNull { it.deviceName == selectedOutputDevice() } ?: return null
        val port = runCatching { synth.open(sampleRate) }.getOrElse {
            message = text(Res.string.msg_own_sound_failed, it.toUiText())
            return null
        }
        output = port
        Tuning.messages(preferences.referenceAHz).forEach(port::send)
        return port
    }

    private fun selectedOutputDevice(): String? =
        preferences.midiOutputDevice?.takeIf { it in outputDevices } ?: defaultDevice(outputDevices)

    private fun openOutput(): MidiOutputPort {
        refreshDevices()
        val outputName = selectedOutputDevice() ?: throw UserError(text(Res.string.msg_no_midi_output))
        val port = services.midi.openOutput(outputName)
        output = port
        Tuning.messages(preferences.referenceAHz).forEach(port::send)
        return port
    }

    private fun closePorts() {
        closers.forEach { runCatching { it() } }
        closers.clear()
        output?.let { runCatching { it.close() } }
        output = null
        gate = null
    }

    // ------------------------------------------------------------ microphone test

    /** Opens the microphone and shows level and recognised notes in Preferences. */
    /**
     * True if the microphone may be used now. Otherwise asks the user (Android) and, if they
     * allow it, runs [retry] afterwards; false meanwhile.
     */
    private fun withMicrophoneAccess(retry: () -> Unit): Boolean {
        if (services.audio.hasAccess) return true
        launchSafely {
            if (services.audio.requestAccess()) retry() else message = text(Res.string.msg_microphone_denied)
        }
        return false
    }

    fun startMicTest() {
        if (running || micTesting) return
        if (!withMicrophoneAccess(::startMicTest)) return
        val audio = runCatching { services.audio.open(preferences.audioInputDevice) }.getOrElse {
            message = text(Res.string.msg_open_microphone_failed, it.toUiText())
            return
        }
        micTestPort = audio
        micTesting = true
        micTestNote = null
        micTestJob = launchSafely {
            audio.blocks
                .detectNotes(
                    audio.sampleRate,
                    preferences.referenceAHz,
                    onNote = { micTestNote = it },
                    onLevel = { microphoneLevel = it },
                    parameters = tuning.detectionParameters,
                )
                .flowOn(Dispatchers.Default)
                .collect()
        }
    }

    // ---------------------------------------------------------- piano calibration

    /** The note to play now while calibrating; null when not calibrating. */
    var calibrationTarget by mutableStateOf<Int?>(null)
        private set
    /** What the last stroke sounded like, if it was not the asked note. */
    var calibrationHeard by mutableStateOf<List<Int>?>(null)
        private set
    var calibrationProgress by mutableStateOf(0 to 0)
        private set
    var calibrating by mutableStateOf(false)
        private set
    private var calibrationJob: Job? = null
    private var calibrationPort: AudioInputPort? = null

    /**
     * Asks for each note of the current range in turn and learns its spectrum (headphones or not:
     * the app plays nothing meanwhile); saved when all notes are done.
     */
    fun startCalibration() {
        if (running || calibrating) return
        if (!withMicrophoneAccess(::startCalibration)) return
        stopMicTest()
        val audio = runCatching { services.audio.open(preferences.audioInputDevice) }.getOrElse {
            message = text(Res.string.msg_open_microphone_failed, it.toUiText())
            return
        }
        calibrationPort = audio
        val notes = (settings.lowLimit..settings.highLimit).toList()
        val calibration = PianoCalibration(
            audio.sampleRate, notes, preferences.referenceAHz, tuning.chordParameters(2), learnedTemplates,
        )
        calibrating = true
        calibrationTarget = calibration.target
        calibrationHeard = null
        calibrationProgress = 0 to calibration.total
        calibrationJob = launchSafely {
            try {
                audio.blocks
                    .map { block ->
                        calibration.process(block)
                        Triple(calibration.target, calibration.lastHeard, calibration.done)
                    }
                    .flowOn(Dispatchers.Default)
                    .collect { (target, heard, done) ->
                        microphoneLevel = calibration.level
                        calibrationTarget = target
                        calibrationHeard = heard
                        calibrationProgress = done to calibration.total
                        if (target == null) {
                            learnedTemplates = calibration.result()
                            repository.saveTemplates(learnedTemplates)
                            message = text(Res.string.msg_calibrated, learnedTemplates.notes.size)
                            throw CancellationException("done")
                        }
                    }
            } finally {
                closeCalibration()
            }
        }
    }

    fun stopCalibration() {
        calibrationJob?.cancel()
        closeCalibration()
    }

    private fun closeCalibration() {
        calibrating = false
        calibrationJob = null
        calibrationPort?.close()
        calibrationPort = null
        calibrationTarget = null
        calibrationHeard = null
        microphoneLevel = 0.0
    }

    /** Back to the piano model for the chord recognition. */
    fun forgetCalibration() = launchSafely {
        learnedTemplates = LearnedTemplates()
        repository.saveTemplates(learnedTemplates)
    }

    // --------------------------------------------------------------- MIDI test

    /** Opens the chosen MIDI devices and shows the keys played in Preferences. */
    fun startMidiTest() {
        if (running || midiTesting) return
        refreshDevices()
        val input = runCatching {
            val name = preferences.midiInputDevice?.takeIf { it in inputDevices } ?: defaultDevice(inputDevices)
                ?: throw UserError(text(Res.string.msg_no_midi_input))
            services.midi.openInput(name)
        }.getOrElse {
            message = text(Res.string.msg_open_midi_input_failed, it.toUiText())
            return
        }
        midiTestInput = input
        midiTesting = true
        midiTestNotes = emptyList()
        midiTestJob = launchSafely {
            input.messages.filter { it.isNoteOn }.collect { note ->
                midiTestNotes = (listOf(note) + midiTestNotes).take(MIDI_TEST_HISTORY)
            }
        }
    }

    fun stopMidiTest() {
        midiTestJob?.cancel()
        midiTestJob = null
        midiTestInput?.let { runCatching { it.close() } }
        midiTestInput = null
        midiTestOutput?.let { runCatching { it.close() } }
        midiTestOutput = null
        midiTesting = false
    }

    /** Plays A4 (with the current Kammerton A) on the MIDI output, to check the sound. */
    fun playTestNote() {
        if (running) return
        val output = midiTestOutput ?: runCatching {
            refreshDevices()
            val name = preferences.midiOutputDevice?.takeIf { it in outputDevices } ?: defaultDevice(outputDevices)
                ?: throw UserError(text(Res.string.msg_no_midi_output))
            services.midi.openOutput(name)
        }.getOrElse {
            message = text(Res.string.msg_open_midi_output_failed, it.toUiText())
            return
        }
        // Kept open while the test runs; otherwise closed again after the note.
        val keepOpen = midiTesting
        if (keepOpen) midiTestOutput = output
        launchSafely {
            try {
                Tuning.messages(preferences.referenceAHz).forEach(output::send)
                output.send(MidiMessage.noteOn(TEST_NOTE, settings.midiOutVelocity))
                delay(TEST_NOTE_MILLIS)
                output.send(MidiMessage.noteOff(TEST_NOTE))
            } finally {
                if (!keepOpen) runCatching { output.close() }
            }
        }
    }

    fun stopMicTest() {
        micTestJob?.cancel()
        micTestJob = null
        micTestPort?.close()
        micTestPort = null
        micTesting = false
        microphoneLevel = 0.0
    }

    // ------------------------------------------------------------- preferences

    fun refreshDevices() {
        inputDevices = runCatching { services.midi.inputDevices() }.getOrDefault(emptyList())
        outputDevices = runCatching { services.midi.outputDevices() }.getOrDefault(emptyList())
        audioInputDevices = runCatching { services.audio.devices() }.getOrDefault(emptyList())
    }

    val canConnectBluetoothMidi: Boolean get() = services.bluetoothMidi != null

    /** Lets the user connect a Bluetooth MIDI device; once connected it is chosen like one plugged in. */
    fun connectBluetoothMidi() {
        val connector = services.bluetoothMidi ?: return
        if (running) return
        launchSafely {
            runCatching { connector.connect() }
                .onSuccess { onMidiDevicesChanged() }
                .onFailure { if (it is CancellationException) throw it else message = text(Res.string.msg_bluetooth_failed, it.toUiText()) }
        }
    }

    /** The synthesizers the app renders itself, whose sound it can remove from the microphone; null if none. */
    val ownSynthName: String? get() = services.renderedSynths.joinToString(" / ") { it.deviceName }.ifEmpty { null }

    /** The app's piano plays recorded samples (they are loaded when the app starts). */
    var pianoSampled by mutableStateOf(false)
        private set

    fun setShowGivenNotes(show: Boolean) = updatePreferences { it.copy(showGivenNotes = show) }

    fun selectInputDevice(name: String) {
        val wasTesting = midiTesting
        stopMidiTest()
        updatePreferences { it.copy(midiInputDevice = name) }
        if (wasTesting) startMidiTest()
    }
    fun selectOutputDevice(name: String) {
        midiTestOutput?.let { runCatching { it.close() } }
        midiTestOutput = null
        updatePreferences { it.copy(midiOutputDevice = name) }
    }

    /** Java: Preferences → Kammerton A. Applies immediately to the output of a running exercise. */
    fun setReferenceA(hz: Double) {
        val clamped = hz.coerceIn(Tuning.MIN_A_HZ, Tuning.MAX_A_HZ)
        updatePreferences { it.copy(referenceAHz = clamped) }
        output?.let { port -> Tuning.messages(clamped).forEach(port::send) }
    }

    fun setInputSource(source: InputSource) = updatePreferences { it.copy(inputSource = source) }
    fun setUsesHeadphones(uses: Boolean) = updatePreferences { it.copy(usesHeadphones = uses) }
    fun setOctavesCountAsCorrect(on: Boolean) = updatePreferences { it.copy(octavesCountAsCorrect = on) }
    fun setOptimizationMode(on: Boolean) = updatePreferences { it.copy(optimizationMode = on) }
    fun setOptimizationSteps(steps: Int) =
        updatePreferences { it.copy(optimizationSteps = steps.coerceIn(OPTIMIZATION_STEP_RANGE)) }
    fun setDetectionParameters(parameters: DetectionParameters) = updatePreferences { it.copy(detectionParameters = parameters) }
    fun setChordDetectionParameters(voices: Int, parameters: ChordDetectionParameters) =
        updatePreferences { it.copy(chordDetectionParameters = it.chordDetectionParameters + (voices to parameters)) }
    fun setRecordMicrophone(record: Boolean) = updatePreferences { it.copy(recordMicrophone = record) }
    val recordingsLocation: String? get() = services.recordings?.location
    fun selectAudioInputDevice(name: String?) {
        val wasTesting = micTesting
        stopMicTest()
        updatePreferences { it.copy(audioInputDevice = name) }
        if (wasTesting) startMicTest()
    }

    /** The device used when none is chosen: the first one that is not Java's built-in sequencer. */
    fun defaultDevice(devices: List<String>): String? =
        devices.firstOrNull { it != JAVA_SEQUENCER } ?: devices.firstOrNull()

    fun dismissMessage() {
        message = null
    }

    // ----------------------------------------------------------------- helpers

    private fun updatePreferences(transform: (AppPreferences) -> AppPreferences) {
        preferences = transform(preferences)
        // One save at a time, each writing the newest preferences.
        val previous = preferencesJob
        preferencesJob = launchSafely {
            previous?.join()
            repository.savePreferences(preferences)
        }
    }

    /** Launches in [scope]; a failure becomes a message instead of crashing the app. */
    private fun launchSafely(block: suspend CoroutineScope.() -> Unit): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = text(Res.string.msg_error, e.toUiText())
        }
    }

    private fun refreshSetupState() {
        setupName = setup.name
        settings = setup.settings
        memoryCounts = setup.memory().countsByPriority()
        val range = settings.lowLimit..settings.highLimit
        sequencesOutsideRange = setup.memory().count { sequence -> sequence.any { element -> element.pitches.any { it !in range } } }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = launchSafely {
            delay(AUTOSAVE_DELAY_MILLIS)
            repository.save(setup)
        }
    }

    private suspend fun saveNow() {
        saveJob?.cancel()
        runCatching { repository.save(setup) }.onFailure { message = text(Res.string.msg_save_failed, setup.name, it.toUiText()) }
    }

    private fun uniqueName(base: String): String {
        if (base !in setupNames) return base
        var i = 2
        while ("$base ($i)" in setupNames) i++
        return "$base ($i)"
    }

    companion object {
        const val DEFAULT_SETUP_NAME = "Default"
        private const val MIDI_TEST_HISTORY = 8
        private const val TEST_NOTE = 69 // A4
        private const val TEST_NOTE_MILLIS = 1000L
        private const val JAVA_SEQUENCER = "Real Time Sequencer"
        private const val AUTOSAVE_DELAY_MILLIS = 800L
    }
}

private const val EXPECTED_CHORDS = 3

/** What a run in optimization mode recorded, step by step. */
class RunSummary(val recording: String, val steps: List<StepSummary>)
