package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jonnyfrick.musicbootcamp.core.learning.LearnedSequences
import io.github.jonnyfrick.musicbootcamp.core.legacy.LegacyImport
import io.github.jonnyfrick.musicbootcamp.core.midi.Tuning
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeSettings
import io.github.jonnyfrick.musicbootcamp.core.persistence.AppPreferences
import io.github.jonnyfrick.musicbootcamp.core.persistence.Setup
import io.github.jonnyfrick.musicbootcamp.core.persistence.SetupRepository
import io.github.jonnyfrick.musicbootcamp.core.practice.PracticeRunner
import io.github.jonnyfrick.musicbootcamp.core.practice.PracticeStatus
import io.github.jonnyfrick.musicbootcamp.platform.MidiInputPort
import io.github.jonnyfrick.musicbootcamp.platform.MidiOutputPort
import io.github.jonnyfrick.musicbootcamp.platform.PlatformServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    private var runner: PracticeRunner? = null
    private var ports: Pair<MidiInputPort, MidiOutputPort>? = null
    private var statusJob: Job? = null
    private var saveJob: Job? = null

    fun load() {
        scope.launch {
            preferences = repository.loadPreferences()
            val names = repository.setupNames()
            val initial = preferences.lastSetup?.takeIf { it in names } ?: names.firstOrNull()
            setup = initial?.let { runCatching { repository.load(it) }.getOrNull() } ?: Setup(DEFAULT_SETUP_NAME).also { repository.save(it) }
            refreshSetupState()
            setupNames = repository.setupNames()
            refreshDevices()
            ready = true
        }
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
        scope.launch {
            saveNow()
            val loaded = runCatching { repository.load(name) }.getOrElse {
                message = "Could not open setup '$name': ${it.message}"
                null
            } ?: return@launch
            setup = loaded
            refreshSetupState()
            updatePreferences { it.copy(lastSetup = name) }
        }
    }

    /** Java: "Save Setup As". Copies settings and learned sequences under a new name. */
    fun saveSetupAs(name: String) {
        if (running) return
        val cleanName = uniqueName(SetupRepository.sanitizeName(name))
        scope.launch {
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
        scope.launch {
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
        scope.launch {
            val selection = runCatching { picker.pickSettingsFile() }.getOrElse {
                message = "Import failed: ${it.message}"
                null
            } ?: return@launch
            saveNow()
            val name = uniqueName(SetupRepository.sanitizeName(selection.suggestedName))
            val result = runCatching { LegacyImport.importSetup(name, selection.settingsXml, selection.readSibling) }
                .getOrElse {
                    message = "Import failed: ${it.message}"
                    return@launch
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
        val problem = when {
            services.midi.unavailableReason != null -> services.midi.unavailableReason
            !settings.mode.isImplemented -> "The mode '${settings.mode.legacyId}' is not implemented (it was not in the Java version either)."
            settings.intervalPriorities.all { it == 0 } -> "Give at least one interval a weight above 0."
            else -> null
        }
        if (problem != null) {
            message = problem
            return
        }

        // Learned sequences are saved when the exercise stops; no background save may touch them meanwhile.
        saveJob?.cancel()
        val opened = runCatching { openPorts() }.getOrElse {
            message = "Could not open MIDI devices: ${it.message}"
            return
        }
        ports = opened
        val (input, output) = opened
        Tuning.messages(preferences.referenceAHz).forEach(output::send)

        val practice = PracticeRunner(scope, settings, setup.memory(), output, input.messages)
        runner = practice
        running = true
        statusJob = scope.launch { practice.status.collect { status = it } }
        practice.start()
    }

    /** Java: "Stop" in the run dialog. */
    fun stopPractice() {
        val practice = runner ?: return
        runner = null
        scope.launch { stopAndSave(practice) }
    }

    /** Stops a running exercise and writes everything to disk; call before the app exits. */
    suspend fun shutdown() {
        val practice = runner
        runner = null
        if (practice != null) stopAndSave(practice) else saveNow()
    }

    private suspend fun stopAndSave(practice: PracticeRunner) {
        practice.stop()
        statusJob?.cancel()
        status = practice.status.value
        ports?.let { (input, output) ->
            input.close()
            output.close()
        }
        ports = null
        refreshSetupState()
        saveNow()
        running = false
    }

    private fun openPorts(): Pair<MidiInputPort, MidiOutputPort> {
        refreshDevices()
        val inputName = preferences.midiInputDevice?.takeIf { it in inputDevices } ?: defaultDevice(inputDevices)
            ?: error("No MIDI input device found. Connect your keyboard and choose it in Preferences.")
        val outputName = preferences.midiOutputDevice?.takeIf { it in outputDevices } ?: defaultDevice(outputDevices)
            ?: error("No MIDI output device found.")
        val output = services.midi.openOutput(outputName)
        val input = runCatching { services.midi.openInput(inputName) }.getOrElse {
            output.close()
            throw it
        }
        return input to output
    }

    // ------------------------------------------------------------- preferences

    fun refreshDevices() {
        inputDevices = runCatching { services.midi.inputDevices() }.getOrDefault(emptyList())
        outputDevices = runCatching { services.midi.outputDevices() }.getOrDefault(emptyList())
    }

    fun selectInputDevice(name: String) = updatePreferences { it.copy(midiInputDevice = name) }
    fun selectOutputDevice(name: String) = updatePreferences { it.copy(midiOutputDevice = name) }

    /** Java: Preferences → Kammerton A. Applies immediately to a running exercise. */
    fun setReferenceA(hz: Double) {
        val clamped = hz.coerceIn(Tuning.MIN_A_HZ, Tuning.MAX_A_HZ)
        updatePreferences { it.copy(referenceAHz = clamped) }
        ports?.second?.let { output -> Tuning.messages(clamped).forEach(output::send) }
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
        val snapshot = preferences
        scope.launch { repository.savePreferences(snapshot) }
    }

    private fun refreshSetupState() {
        setupName = setup.name
        settings = setup.settings
        memoryCounts = setup.memory().countsByPriority()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
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
        private const val JAVA_SEQUENCER = "Real Time Sequencer"
        private const val AUTOSAVE_DELAY_MILLIS = 800L
    }
}
