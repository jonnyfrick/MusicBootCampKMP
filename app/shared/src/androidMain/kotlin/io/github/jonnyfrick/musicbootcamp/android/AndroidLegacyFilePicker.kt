package io.github.jonnyfrick.musicbootcamp.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.jonnyfrick.musicbootcamp.platform.LegacyFilePicker
import io.github.jonnyfrick.musicbootcamp.platform.LegacySelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder

/**
 * Imports a setup of the Java version on Android. The system's file dialog only gives access to
 * the files the user picks, not to their neighbours, so the settings XML and its learned-sequences
 * XML are picked together ([pickFiles] opens the dialog for several files); the settings file is
 * the one that is not called `learned_sequences_…`.
 */
class AndroidLegacyFilePicker(
    private val context: Context,
    private val pickFiles: suspend () -> List<Uri>,
) : LegacyFilePicker {
    override suspend fun pickSettingsFile(): LegacySelection? {
        val picked = pickFiles()
        if (picked.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            val files = picked.associateBy { displayName(it) }
            val settingsName = files.keys.firstOrNull { !isSequences(it) && it.endsWith(".xml", ignoreCase = true) }
                ?: files.keys.firstOrNull { !isSequences(it) }
                ?: throw IllegalArgumentException("Choose the settings XML too, not only the learned sequences.")
            LegacySelection(
                suggestedName = settingsName.substringBeforeLast('.').removePrefix("settings_").ifEmpty { settingsName },
                settingsXml = read(files.getValue(settingsName)),
                // Java stored the path relative to its working directory, URL-encoded (e.g. %20).
                readSibling = { relative ->
                    listOf(relative, URLDecoder.decode(relative, "UTF-8"))
                        .map { it.substringAfterLast('/').substringAfterLast('\\') }
                        .firstNotNullOfOrNull { files[it] }
                        ?.let(::read)
                },
            )
        }
    }

    private fun isSequences(name: String) = name.startsWith("learned_sequences_") || name.startsWith("canonical_learned_sequences_")

    private fun read(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("Could not read ${displayName(uri)}")

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
}
