package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * Text for the user that is translated only when shown, so the controller does not depend on
 * the UI's locale. Arguments may be [UiText] themselves (e.g. an error inside a message).
 */
sealed interface UiText {
    class Resource(val id: StringResource, vararg val args: Any) : UiText

    /** Text that has no translation, e.g. a platform's error message or a device name. */
    class Raw(val text: String) : UiText

    /** Several texts shown as sentences one after the other. */
    class Joined(val parts: List<UiText>) : UiText

    suspend fun text(): String = when (this) {
        is Resource -> getString(id, *args.map { if (it is UiText) it.text() else it }.toTypedArray())
        is Raw -> text
        is Joined -> parts.map { it.text() }.joinToString(" ")
    }
}

internal fun text(id: StringResource, vararg args: Any): UiText = UiText.Resource(id, *args)

/** An error whose message is meant for the user as it is. */
class UserError(val text: UiText) : Exception()

/** What to tell the user about [this] error. */
internal fun Throwable.toUiText(): UiText = (this as? UserError)?.text ?: UiText.Raw(message ?: this::class.simpleName.orEmpty())

@Composable
internal fun UiText.resolve(): String = produceState(initialValue = (this as? UiText.Raw)?.text.orEmpty(), this) { value = text() }.value
