package io.github.jonnyfrick.musicbootcamp.core.persistence

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
        return Setup.fromDocument(json.decodeFromString(SetupDocument.serializer(), text))
    }

    suspend fun save(setup: Setup) {
        require(setup.name == sanitizeName(setup.name)) { "Setup name '${setup.name}' is not a valid file name" }
        store.write(fileName(setup.name), json.encodeToString(SetupDocument.serializer(), setup.toDocument()))
    }

    suspend fun delete(name: String) = store.delete(fileName(name))

    suspend fun loadPreferences(): AppPreferences =
        store.read(PREFERENCES)?.let { runCatching { json.decodeFromString(AppPreferences.serializer(), it) }.getOrNull() }
            ?: AppPreferences()

    suspend fun savePreferences(preferences: AppPreferences) {
        store.write(PREFERENCES, json.encodeToString(AppPreferences.serializer(), preferences))
    }

    companion object {
        private const val SETUP_PREFIX = "setup-"
        private const val SUFFIX = ".json"
        private const val PREFERENCES = "preferences.json"

        internal val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Makes [name] usable as a file name: unsafe characters become `_`. */
        fun sanitizeName(name: String): String =
            name.trim().map { if (it.isLetterOrDigit() || it in "-_ ") it else '_' }.joinToString("").ifEmpty { "setup" }

        private fun fileName(setupName: String): String = SETUP_PREFIX + sanitizeName(setupName) + SUFFIX
    }
}
