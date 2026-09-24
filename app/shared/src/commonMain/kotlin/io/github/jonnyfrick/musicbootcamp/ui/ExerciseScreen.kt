package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jonnyfrick.musicbootcamp.core.midi.NoteNames
import io.github.jonnyfrick.musicbootcamp.core.model.Direction
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeMode
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeSettings
import io.github.jonnyfrick.musicbootcamp.core.model.SettingsRules

internal fun modeLabel(mode: PracticeMode) = when (mode) {
    PracticeMode.MONOPHONIC -> "Monophonic"
    PracticeMode.TWO_VOICES_PURE_RANDOM -> "2 voices pure random"
    PracticeMode.HOMOPHONIC_MODES -> "Homophonic modes (not implemented)"
}

/** Java: Options → Random (`RandomOptionsDialog`). Changes apply immediately. */
@Composable
internal fun ExerciseScreen(controller: AppController) {
    val settings = controller.settings
    val enabled = !controller.running
    fun update(transform: (PracticeSettings) -> PracticeSettings) = controller.updateSettings(transform)

    if (!enabled) Hint("Stop the exercise to change its settings.")

    SectionTitle("Mode")
    RadioGroup(
        options = PracticeMode.entries,
        selected = settings.mode,
        label = ::modeLabel,
        enabled = { enabled && it.isImplemented },
        onSelect = { mode -> update { it.copy(mode = mode) } },
    )
    Hint("Each mode keeps its own learned sequences.")

    SectionTitle("Direction")
    RadioGroup(
        options = Direction.entries,
        selected = settings.direction,
        label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
        enabled = { enabled },
        onSelect = { direction -> update { it.copy(direction = direction) } },
    )
    Hint("Stored with the setup; the exercise does not use it yet (neither did the Java version).")

    SectionTitle("Timing")
    LabeledRow {
        CommitTextField(
            label = "Breathing time (s)",
            value = settings.breathingTime.toString(),
            enabled = enabled,
            modifier = Modifier.width(180.dp),
        ) { input ->
            val checked = SettingsRules.checkBreathingTime(input, settings.breathingTime)
            checked.message?.let(controller::showMessage)
            update { it.copy(breathingTime = checked.value) }
            checked.value.toString()
        }
        Hint("${SettingsRules.MIN_BREATHING_TIME} – ${SettingsRules.MAX_BREATHING_TIME} s between two steps")
    }
    IntSlider("Sustain", settings.sustain, 0..SettingsRules.MAX_SUSTAIN, enabled, valueText = { "$it %" }) { value ->
        update { it.copy(sustain = value) }
    }
    IntSlider("Velocity", settings.midiOutVelocity, 1..127, enabled) { value ->
        update { it.copy(midiOutVelocity = value) }
    }

    SectionTitle("Range")
    LabeledRow {
        CommitTextField("From", settings.lowLimit.toString(), enabled, Modifier.width(110.dp)) { input ->
            val checked = SettingsRules.checkLowLimit(input, settings.highLimit, settings.lowLimit)
            checked.message?.let(controller::showMessage)
            update { it.withRange(checked.value, it.highLimit) }
            checked.value.toString()
        }
        Text(NoteNames.displayName(settings.lowLimit), Modifier.width(110.dp))
        CommitTextField("To", settings.highLimit.toString(), enabled, Modifier.width(110.dp)) { input ->
            val checked = SettingsRules.checkHighLimit(input, settings.lowLimit, settings.highLimit)
            checked.message?.let(controller::showMessage)
            update { it.withRange(it.lowLimit, checked.value) }
            checked.value.toString()
        }
        Text(NoteNames.displayName(settings.highLimit))
    }
    Hint("MIDI note numbers. Starting at ${NoteNames.displayName(settings.startPosition)} (${settings.startPosition}).")

    SectionTitle("Intervals")
    Hint("How often each interval is chosen for a random step (0 = never).")
    PracticeSettings.INTERVAL_LABELS.forEachIndexed { index, label ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            IntSlider(label, settings.intervalPriorities[index], 0..SettingsRules.MAX_INTERVAL_PRIORITY, enabled) { value ->
                update { it.copy(intervalPriorities = it.intervalPriorities.toMutableList().also { list -> list[index] = value }) }
            }
        }
    }
    SettingsRules.intervalsProblem(settings.intervalPriorities)?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
    }
}

/** Java: the start position always follows the range. */
private fun PracticeSettings.withRange(low: Int, high: Int) =
    copy(lowLimit = low, highLimit = high, startPosition = SettingsRules.startPosition(low, high))
