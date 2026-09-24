package io.github.jonnyfrick.musicbootcamp.core.legacy

import io.github.jonnyfrick.musicbootcamp.core.learning.LearnedSequences
import io.github.jonnyfrick.musicbootcamp.core.model.Direction
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeMode
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeSettings
import io.github.jonnyfrick.musicbootcamp.core.model.SequenceElement
import io.github.jonnyfrick.musicbootcamp.core.persistence.Setup

/**
 * Reads the files of the Java version (one-way import). Their XML is hand-written:
 * one tag per line, lists encoded as numbered tags (`<seq_0>`, `<cn_0>`), no schema,
 * so it is parsed line by line exactly like the Java code did.
 */
object LegacyImport {

    /** Everything a Java settings file contains. */
    data class LegacySettings(
        val settings: PracticeSettings,
        /** Voice count written in the file; the new model derives it from the mode. */
        val numberOfVoices: Int?,
        val referenceAHz: Double?,
        val midiInputDevice: String?,
        val midiOutputDevice: String?,
        /** The `current_settings_file_path_` entry, which decides the learned-sequences file. */
        val currentSettingsFilePath: String?,
        val warnings: List<String>,
    ) {
        /** Java: `"learned_sequences_" + current_settings_file_path_` (default: `default_settings.xml`). */
        val learnedSequencesFileName: String
            get() = LEARNED_SEQUENCES_PREFIX + (currentSettingsFilePath ?: DEFAULT_SETTINGS_FILE)
    }

    data class ImportResult(
        val setup: Setup,
        val legacySettings: LegacySettings,
        /** Null when the learned-sequences file could not be found. */
        val learnedSequencesFileName: String?,
        val warnings: List<String>,
    )

    const val DEFAULT_SETTINGS_FILE = "default_settings.xml"
    const val LEARNED_SEQUENCES_PREFIX = "learned_sequences_"

    /**
     * Imports a Java setup: the settings file plus the learned-sequences file it
     * points to. [readSibling] reads a file relative to the settings file's folder.
     * The learned sequences go into the memory of the setup's mode.
     */
    fun importSetup(name: String, settingsXml: String, readSibling: (String) -> String?): ImportResult {
        val legacy = parseSettings(settingsXml)
        val warnings = legacy.warnings.toMutableList()
        val setup = Setup(name, legacy.settings)

        val learnedFile = legacy.learnedSequencesFileName
        val learnedXml = readSibling(learnedFile)
        if (learnedXml == null) {
            warnings += "No learned sequences found ($learnedFile)."
        } else {
            val learned = parseLearnedSequences(learnedXml)
            val memory = setup.memory(legacy.settings.mode)
            learned.byPriority().forEachIndexed { level, sequences -> sequences.forEach { memory.insert(it, level) } }
        }
        return ImportResult(setup, legacy, learnedFile.takeIf { learnedXml != null }, warnings)
    }

    fun parseSettings(xml: String): LegacySettings {
        val values = parseFlatTags(xml)
        val warnings = mutableListOf<String>()
        val defaults = PracticeSettings()

        fun <T> read(key: String, default: T, convert: (String) -> T?): T {
            val raw = values[key]
            if (raw == null) {
                warnings += "Missing parameter $key in settings file, using the default."
                return default
            }
            return convert(raw) ?: default.also { warnings += "Unreadable value '$raw' for $key, using the default." }
        }

        val mode = read("mode_", defaults.mode) { PracticeMode.fromLegacyId(it) }
        val priorities = List(PracticeSettings.INTERVAL_COUNT) { i ->
            read("interval_priorities_index_$i", defaults.intervalPriorities[i]) { it.toIntOrNull() }
        }
        val settings = PracticeSettings(
            mode = mode,
            direction = read("direction_", defaults.direction) { Direction.fromLegacyId(it) },
            breathingTime = read("breathing_time_", defaults.breathingTime) { it.toFloatOrNull() },
            sustain = read("sustain_", defaults.sustain) { it.toIntOrNull() },
            midiOutVelocity = read("midi_out_velocity_", defaults.midiOutVelocity) { it.toIntOrNull() },
            lowLimit = read("low_limit_", defaults.lowLimit) { it.toIntOrNull() },
            highLimit = read("high_limit_", defaults.highLimit) { it.toIntOrNull() },
            startPosition = read("start_position_", defaults.startPosition) { it.toIntOrNull() },
            intervalPriorities = priorities,
            learnedProbability = read("learned_prob_", defaults.learnedProbability) { it.toDoubleOrNull() },
            memorySize = read("memory_size_", defaults.memorySize) { it.toIntOrNull() },
            learnNewSequences = read("learn_new_sequences_", defaults.learnNewSequences) { it.equals("true", ignoreCase = true) },
            transpositionsProbability = read("transpositions_prob_", defaults.transpositionsProbability) { it.toDoubleOrNull() },
        )
        return LegacySettings(
            settings = settings,
            numberOfVoices = values["number_of_voices_"]?.toIntOrNull(),
            // Older files have no reference pitch: standard tuning, like the Java version.
            referenceAHz = values["reference_a_hz_"]?.toDoubleOrNull(),
            midiInputDevice = values["midi_in_device_"],
            midiOutputDevice = values["midi_out_device_"],
            currentSettingsFilePath = values["current_settings_file_path_"],
            warnings = warnings,
        )
    }

    /**
     * Java: `LearnedSequencesDataStructure.LoadSequencesFromFile`. Sequences are filed
     * by their first note (not by the enclosing `midi_number_sorted_N` tag) and keep
     * the priority of the enclosing `priority_N` tag.
     */
    fun parseLearnedSequences(xml: String): LearnedSequences {
        val memory = LearnedSequences()
        var priority = 0
        var sequence = mutableListOf<SequenceElement>()

        for (line in xml.lineSequence()) {
            val tag = identifier(line) ?: continue
            when {
                tag.length > "priority".length && tag.startsWith("priority", ignoreCase = true) ->
                    priority = tag.substring("priority_".length).toIntOrNull() ?: priority

                tag.length > "/seq".length && tag.startsWith("/seq", ignoreCase = true) -> {
                    if (sequence.isNotEmpty()) memory.insert(sequence, priority.coerceIn(0, LearnedSequences.PRIORITY_LEVELS - 1))
                    sequence = mutableListOf()
                }

                tag.length > "cn".length && tag.startsWith("cn", ignoreCase = true) -> {
                    val value = value(line) ?: continue
                    sequence += if (value.startsWith("[")) {
                        SequenceElement.Chord(value.removePrefix("[").removeSuffix("]").split(",").map { it.trim().toInt() })
                    } else {
                        SequenceElement.Note(value.trim().toInt())
                    }
                }
            }
        }
        return memory
    }

    /** Java settings files are read into one flat tag → value table, whatever the nesting. */
    internal fun parseFlatTags(xml: String): Map<String, String> {
        val values = mutableMapOf<String, String>()
        for (line in xml.lineSequence()) {
            val tag = identifier(line) ?: continue
            val value = value(line) ?: continue // opening/closing lines of nested blocks
            values[tag] = value
        }
        return values
    }

    /** Text between `<` and the first `>` of a line. */
    private fun identifier(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("<")) return null
        val end = trimmed.indexOf('>')
        if (end < 1) return null
        return trimmed.substring(1, end)
    }

    /** Text between the first `>` and the following `</`, or null if the line has no closing tag. */
    private fun value(line: String): String? {
        val trimmed = line.trim()
        val start = trimmed.indexOf('>')
        if (start < 0) return null
        val end = trimmed.indexOf("</", start)
        if (end < 0) return null
        return trimmed.substring(start + 1, end)
    }
}
