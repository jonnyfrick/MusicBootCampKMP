package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jonnyfrick.musicbootcamp.core.audio.SessionRecorder
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
import io.github.jonnyfrick.musicbootcamp.core.persistence.MAX_LATE_ANSWER_TOLERANCE_MILLIS
import io.github.jonnyfrick.musicbootcamp.core.pitch.DetectedNote
import io.github.jonnyfrick.musicbootcamp.core.pitch.NoteTracker
import io.github.jonnyfrick.musicbootcamp.core.pitch.detectNotes
import io.github.jonnyfrick.musicbootcamp.core.practice.OwnSoundGate
import io.github.jonnyfrick.musicbootcamp.platform.AudioInputPort
import io.github.jonnyfrick.musicbootcamp.platform.MidiInputPort
import io.github.jonnyfrick.musicbootcamp.platform.MidiOutputPort
import io.github.jonnyfrick.musicbootcamp.platform.PlatformServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
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
    var message by mutableStateOf<String?>(null)
        private set

    /** True from "Go" until the exercise has stopped and everything is saved. */
    var running by mutableStateOf(false)
        private set

    val midiUnavailableReason: String? get() = services.midi.unavailableReason
    val canImportLegacyFiles: Boolean get() = services.legacyFiles != null

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
    private var recordingInfo: Map<String, String> = emptyMap()
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
            preferences = repository.loadPreferences()
            val names = repository.setupNames()
            val initial = preferences.lastSetup?.takeIf { it in names } ?: names.firstOrNull()
            setup = initial?.let { runCatching { repository.load(it) }.getOrNull() } ?: Setup(DEFAULT_SETUP_NAME).also { repository.save(it) }
            refreshSetupState()
            setupNames = repository.setupNames()
            refreshDevices()
            ready = true
        }
        launchSafely { services.midi.devicesChanged.collect { onMidiDevicesChanged() } }
    }

    /** A MIDI device was plugged in or removed: update the lists and say what changed. */
    private fun onMidiDevicesChanged() {
        val before = (inputDevices + outputDevices).toSet()
        refreshDevices()
        val after = (inputDevices + outputDevices).toSet()
        val added = after - before
        val removed = before - after
        if (added.isEmpty() && removed.isEmpty()) return
        message = buildList {
            if (added.isNotEmpty()) add("MIDI connected: ${added.joinToString()}")
            if (removed.isNotEmpty()) add("MIDI disconnected: ${removed.joinToString()}")
            if (running) add("Stop and restart the exercise to use it.")
        }.joinToString(". ")
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
                message = "Could not open setup '$name': ${it.message}"
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
                message = "Import failed: ${it.message}"
                null
            } ?: return@launchSafely
            saveNow()
            val name = uniqueName(SetupRepository.sanitizeName(selection.suggestedName))
            val result = runCatching { LegacyImport.importSetup(name, selection.settingsXml, selection.readSibling) }
                .getOrElse {
                    message = "Import failed: ${it.message}"
                    return@launchSafely
                }
            setup = result.setup
            repository.save(setup)
            setupNames = repository.setupNames()
            refreshSetupState()
            result.legacySettings.referenceAHz?.let { reference -> updatePreferences { it.copy(referenceAHz = reference) } }
            updatePreferences { it.copy(lastSetup = name) }

            message = buildString {
                append("Imported '$name' with ${setup.memory().size} learned sequences")
                result.learnedSequencesFileName?.let { append(" from $it") }
                append('.')
                result.legacySettings.referenceAHz?.let { append(" Kammerton A set to $it Hz.") }
                if (result.warnings.isNotEmpty()) append(' ').append(result.warnings.joinToString(" "))
            }
        }
    }

    // ---------------------------------------------------------------- practice

    /** Java: "Go!" in the run dialog. */
    fun startPractice() {
        if (running) return
        val microphone = preferences.inputSource == InputSource.MICROPHONE
        val problem = when {
            services.midi.unavailableReason != null -> services.midi.unavailableReason
            microphone && services.audio.unavailableReason != null -> services.audio.unavailableReason
            microphone && settings.mode != PracticeMode.MONOPHONIC ->
                "The microphone recognises single notes so far. Choose the monophonic mode or MIDI input."
            !settings.mode.isImplemented -> "The mode '${settings.mode.legacyId}' is not implemented (it was not in the Java version either)."
            settings.intervalPriorities.all { it == 0 } -> "Give at least one interval a weight above 0."
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
        val input = runCatching { if (microphone) openMicrophoneInput() else openMidiInput() }.getOrElse {
            closePorts()
            message = "Could not open the input: ${it.message}"
            return
        }
        val practiceOutput = (gate ?: output!!).let { port -> recorder?.recording(port) ?: port }
        recordingInfo = mapOf(
            "breathingTime" to settings.breathingTime.toString(),
            "sustain" to settings.sustain.toString(),
            "lateAnswerToleranceMillis" to preferences.lateAnswerToleranceMillis.toString(),
            "usesHeadphones" to preferences.usesHeadphones.toString(),
            "referenceAHz" to preferences.referenceAHz.toString(),
            "midiOutputDevice" to (preferences.midiOutputDevice ?: ""),
        )

        val practice = PracticeRunner(
            scope, settings, setup.memory(), practiceOutput, input,
            lateAnswerTolerance = if (microphone) lateAnswerTolerance else Duration.ZERO,
            onStep = { recorder?.step(it) },
            onEvaluation = { recorder?.evaluation(it) },
        )
        runner = practice
        running = true
        statusJob = scope.launch { practice.status.collect { status = it } }
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
        practice.stop()
        statusJob?.cancel()
        status = practice.status.value
        finishRecording()
        closePorts()
        refreshSetupState()
        saveNow()
        running = false
    }

    /** Opens the MIDI output (tuned) and the MIDI keyboard; returns the keyboard's messages. */
    private fun openMidiInput(): Flow<MidiMessage> {
        openOutput()
        val inputName = preferences.midiInputDevice?.takeIf { it in inputDevices } ?: defaultDevice(inputDevices)
            ?: error("No MIDI input device found. Connect your keyboard and choose it in Preferences.")
        val input = services.midi.openInput(inputName)
        closers += input::close
        return input.messages
    }

    /** Opens the MIDI output (tuned) and the microphone; returns the recognised notes as note-ons. */
    private fun openMicrophoneInput(): Flow<MidiMessage> {
        val midiOutput = openOutput()
        val audio = services.audio.open(preferences.audioInputDevice)
        closers += audio::close
        val ownSound = if (preferences.usesHeadphones) null else OwnSoundGate(midiOutput)
        gate = ownSound
        // The tolerance counts from the key stroke; the note arrives only once it is recognised.
        lateAnswerTolerance = preferences.lateAnswerToleranceMillis.milliseconds +
            NoteTracker(audio.sampleRate).detectionDelay
        val recording = if (preferences.recordMicrophone) {
            services.recordings?.let { SessionRecorder(it.create(), audio.sampleRate, listOf("microphone")) }
        } else {
            null
        }
        recorder = recording
        return audio.blocks
            .onEach { recording?.audio(listOf(it)) }
            .detectNotes(
                audio.sampleRate, preferences.referenceAHz,
                onNote = { recording?.detected(it) },
                onLevel = { microphoneLevel = it },
            )
            .flowOn(Dispatchers.Default)
            // Evaluated where the exercise runs, which is also where the gate sees the notes played.
            .filter { ownSound?.accepts(it) ?: true }
            .onEach { recording?.accepted(it) }
    }

    /** Writes the header and the log of a recorded session; the audio has stopped by now. */
    private suspend fun finishRecording() {
        val recording = recorder ?: return
        recorder = null
        runCatching { withContext(Dispatchers.Default) { recording.finish(recordingInfo) } }
            .onSuccess { message = "Recording saved: ${recording.name}" }
            .onFailure { message = "Could not save the recording: ${it.message}" }
    }

    private fun openOutput(): MidiOutputPort {
        refreshDevices()
        val outputName = preferences.midiOutputDevice?.takeIf { it in outputDevices } ?: defaultDevice(outputDevices)
            ?: error("No MIDI output device found.")
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
    fun startMicTest() {
        if (running || micTesting) return
        val audio = runCatching { services.audio.open(preferences.audioInputDevice) }.getOrElse {
            message = "Could not open the microphone: ${it.message}"
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
                )
                .flowOn(Dispatchers.Default)
                .collect()
        }
    }

    // --------------------------------------------------------------- MIDI test

    /** Opens the chosen MIDI devices and shows the keys played in Preferences. */
    fun startMidiTest() {
        if (running || midiTesting) return
        refreshDevices()
        val input = runCatching {
            val name = preferences.midiInputDevice?.takeIf { it in inputDevices } ?: defaultDevice(inputDevices)
                ?: error("No MIDI input device found.")
            services.midi.openInput(name)
        }.getOrElse {
            message = "Could not open the MIDI input: ${it.message}"
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
                ?: error("No MIDI output device found.")
            services.midi.openOutput(name)
        }.getOrElse {
            message = "Could not open the MIDI output: ${it.message}"
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
    fun setRecordMicrophone(record: Boolean) = updatePreferences { it.copy(recordMicrophone = record) }
    val recordingsLocation: String? get() = services.recordings?.location
    fun setLateAnswerTolerance(millis: Int) =
        updatePreferences { it.copy(lateAnswerToleranceMillis = millis.coerceIn(0, MAX_LATE_ANSWER_TOLERANCE_MILLIS)) }

    fun selectAudioInputDevice(name: String?) {
        val wasTesting = micTesting
        stopMicTest()
        updatePreferences { it.copy(audioInputDevice = name) }
        if (wasTesting) startMicTest()
    }

    /** The device used when none is chosen: the first one that is not Java's built-in sequencer. */
    fun defaultDevice(devices: List<String>): String? =
        devices.firstOrNull { it != JAVA_SEQUENCER } ?: devices.firstOrNull()

    fun showMessage(text: String) {
        message = text
    }

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
            message = "Something went wrong: ${e.message ?: e::class.simpleName}"
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
        runCatching { repository.save(setup) }.onFailure { message = "Could not save '${setup.name}': ${it.message}" }
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
