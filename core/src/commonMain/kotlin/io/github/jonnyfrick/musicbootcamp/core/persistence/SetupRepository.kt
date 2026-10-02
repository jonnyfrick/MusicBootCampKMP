package io.github.jonnyfrick.musicbootcamp.core.persistence

import io.github.jonnyfrick.musicbootcamp.core.pitch.DetectionParameters
import io.github.jonnyfrick.musicbootcamp.core.pitch.LearnedTemplates
import kotlinx.serialization.json.Json

/** Minimal text-file storage the platforms provide (a directory on desktop). */
interface DocumentStore {
    suspend fun read(name: String): String?
    suspend fun write(name: String, content: String)
    suspend fun delete(name: String)
    suspend fun list(): List<String>
}

/** Keeps everything in memory — for platforms without persistent storage yet, and for tests. */
class InMemoryDocumentStore : DocumentStore {
    private val documents = mutableMapOf<String, String>()
    override suspend fun read(name: String): String? = documents[name]
    override suspend fun write(name: String, content: String) {
        documents[name] = content
    }

    override suspend fun delete(name: String) {
        documents.remove(name)
    }

    override suspend fun list(): List<String> = documents.keys.sorted()
}

/**
 * Stores setups as `setup-<name>.json` and the preferences as `preferences.json`,
 * all as JSON with a `version` field for future format changes.
 */
class SetupRepository(private val store: DocumentStore) {

    suspend fun setupNames(): List<String> =
        store.list()
            .filter { it.startsWith(SETUP_PREFIX) && it.endsWith(SUFFIX) }
            .map { it.removePrefix(SETUP_PREFIX).removeSuffix(SUFFIX) }
            .sortedBy { it.lowercase() }

    /** Loads the setup called [name]; null when it does not exist. */
    suspend fun load(name: String): Setup? {
        val text = store.read(fileName(name)) ?: return null
        val document = json.decodeFromString(SetupDocument.serializer(), text)
        val setup = Setup.fromDocument(document)
        // Format 1 had one late-answer tolerance for all setups, in the preferences: each setup
        // takes it over the first time it is loaded (and is saved in the current format from then on).
        if (document.version < 2) {
            setup.settings = setup.settings.copy(lateAnswerToleranceMillis = loadPreferences().lateAnswerToleranceMillis)
        }
        return setup
    }

    suspend fun save(setup: Setup) {
        require(setup.name == sanitizeName(setup.name)) { "Setup name '${setup.name}' is not a valid file name" }
        store.write(fileName(setup.name), json.encodeToString(SetupDocument.serializer(), setup.toDocument()))
    }

    suspend fun delete(name: String) = store.delete(fileName(name))

    suspend fun loadPreferences(): AppPreferences {
        val stored = store.read(PREFERENCES)?.let { runCatching { json.decodeFromString(AppPreferences.serializer(), it) }.getOrNull() }
            ?: AppPreferences()
        // Detection parameters tuned against older defaults start over from the current ones.
        return if (stored.detectionParametersRevision >= DETECTION_PARAMETERS_REVISION) stored
        else stored.copy(detectionParameters = DetectionParameters(), detectionParametersRevision = DETECTION_PARAMETERS_REVISION)
    }

    /** The player's piano, as measured by calibration (chord recognition); empty if never done. */
    suspend fun loadTemplates(): LearnedTemplates =
        store.read(TEMPLATES)?.let { runCatching { json.decodeFromString(LearnedTemplates.serializer(), it) }.getOrNull() }
            ?: LearnedTemplates()

    suspend fun saveTemplates(templates: LearnedTemplates) {
        store.write(TEMPLATES, json.encodeToString(LearnedTemplates.serializer(), templates))
    }

    suspend fun savePreferences(preferences: AppPreferences) {
        store.write(PREFERENCES, json.encodeToString(AppPreferences.serializer(), preferences))
    }

    companion object {
        private const val SETUP_PREFIX = "setup-"
        private const val SUFFIX = ".json"
        private const val PREFERENCES = "preferences.json"
        private const val TEMPLATES = "piano-templates.json"

        internal val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Makes [name] usable as a file name: unsafe characters become `_`. */
        fun sanitizeName(name: String): String =
            name.trim().map { if (it.isLetterOrDigit() || it in "-_ ()") it else '_' }.joinToString("").ifEmpty { "setup" }

        private fun fileName(setupName: String): String = SETUP_PREFIX + sanitizeName(setupName) + SUFFIX
    }
}
