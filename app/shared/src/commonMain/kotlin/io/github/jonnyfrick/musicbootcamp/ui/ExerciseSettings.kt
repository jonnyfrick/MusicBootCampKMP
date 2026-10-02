package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
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
import io.github.jonnyfrick.musicbootcamp.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun modeLabel(mode: PracticeMode): String = stringResource(
    when (mode) {
        PracticeMode.MONOPHONIC -> Res.string.mode_monophonic
        PracticeMode.TWO_VOICES_PURE_RANDOM -> Res.string.mode_two_voices
        PracticeMode.HOMOPHONIC_MODES -> Res.string.mode_homophonic
    },
)

/**
 * The exercise of the current setup (Java: Options → Random, `RandomOptionsDialog`), in the
 * Practice screen's sheet or side pane. Changes apply immediately. Sliders keep every value in
 * its allowed range, so the Java dialogs' corrections ("You are not a bat!") cannot occur.
 */
@Composable
internal fun ExerciseSettings(controller: AppController) {
    val settings = controller.settings
    val enabled = !controller.running
    fun update(transform: (PracticeSettings) -> PracticeSettings) = controller.updateSettings(transform)

    if (!enabled) SettingHint(stringResource(Res.string.stop_to_change), color = MaterialTheme.colorScheme.error)

    SettingsSection(stringResource(Res.string.section_mode)) {
        RadioSetting(
            options = PracticeMode.entries,
            selected = settings.mode,
            label = { modeLabel(it) },
            enabled = { enabled && it.isImplemented },
            onSelect = { mode -> update { it.copy(mode = mode) } },
        )
        SettingHint(stringResource(Res.string.mode_hint))
    }

    SettingsSection(stringResource(Res.string.section_tempo)) {
        SliderSetting(
            title = stringResource(Res.string.breathing_time),
            value = settings.breathingTime.toDouble(),
            range = SettingsRules.MIN_BREATHING_TIME.toDouble()..SettingsRules.MAX_BREATHING_TIME.toDouble(),
            step = 0.1,
            valueText = stringResource(Res.string.value_seconds, settings.breathingTime.toString()),
            onChange = { value -> update { it.copy(breathingTime = ((value * 10).roundToInt() / 10.0).toFloat()) } },
            enabled = enabled,
            supporting = stringResource(Res.string.breathing_time_hint),
            fineSteps = true,
        )
        IntSliderSetting(
            title = stringResource(Res.string.late_answers),
            value = settings.lateAnswerToleranceMillis,
            range = 0..SettingsRules.MAX_LATE_ANSWER_TOLERANCE_MILLIS,
            step = 50,
            onChange = { value -> update { it.copy(lateAnswerToleranceMillis = value) } },
            enabled = enabled,
            valueText = stringResource(Res.string.value_ms, settings.lateAnswerToleranceMillis),
            supporting = stringResource(Res.string.late_answers_hint),
        )
        IntSliderSetting(
            title = stringResource(Res.string.sustain),
            value = settings.sustain,
            range = 0..SettingsRules.MAX_SUSTAIN,
            onChange = { value -> update { it.copy(sustain = value) } },
            enabled = enabled,
            valueText = "${settings.sustain} %",
            supporting = stringResource(Res.string.sustain_hint),
            fineSteps = true,
        )
        IntSliderSetting(
            title = stringResource(Res.string.velocity),
            value = settings.midiOutVelocity,
            range = 1..127,
            onChange = { value -> update { it.copy(midiOutVelocity = value) } },
            enabled = enabled,
            supporting = stringResource(Res.string.velocity_hint),
            fineSteps = true,
        )
    }

    SettingsSection(stringResource(Res.string.section_range)) {
        RangeSetting(settings, enabled) { low, high -> update { it.withRange(low, high) } }
    }

    SettingsSection(stringResource(Res.string.section_intervals)) {
        SettingHint(stringResource(Res.string.intervals_hint))
        PracticeSettings.INTERVAL_LABELS.forEachIndexed { index, label ->
            IntSliderSetting(
                title = label,
                value = settings.intervalPriorities[index],
                range = 0..SettingsRules.MAX_INTERVAL_PRIORITY,
                onChange = { value ->
                    update { it.copy(intervalPriorities = it.intervalPriorities.toMutableList().also { list -> list[index] = value }) }
                },
                enabled = enabled,
            )
        }
        SettingsRules.intervalsProblem(settings.intervalPriorities)?.let {
            SettingHint(stringResource(Res.string.intervals_problem), color = MaterialTheme.colorScheme.error)
        }
    }

    SettingsSection(stringResource(Res.string.section_sequences)) {
        val percent = (settings.learnedProbability * 100).roundToInt()
        IntSliderSetting(
            title = stringResource(Res.string.memorized_share),
            value = percent,
            range = 0..100,
            step = 10,
            onChange = { value -> update { it.copy(learnedProbability = value / 100.0) } },
            enabled = enabled,
            valueText = "$percent %",
            supporting = stringResource(Res.string.memorized_hint, controller.memoryCounts.sum()),
        )
    }

    SettingsSection(stringResource(Res.string.section_direction)) {
        RadioSetting(
            options = Direction.entries,
            selected = settings.direction,
            label = {
                stringResource(
                    when (it) {
                        Direction.LINEAR -> Res.string.direction_linear
                        Direction.RANDOM -> Res.string.direction_random
                        Direction.ALTERNATING -> Res.string.direction_alternating
                    },
                )
            },
            enabled = { enabled },
            onSelect = { direction -> update { it.copy(direction = direction) } },
        )
        SettingHint(stringResource(Res.string.direction_hint))
    }
}

/** The range as one slider with two thumbs; it never gets smaller than [SettingsRules.MIN_RANGE]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeSetting(settings: PracticeSettings, enabled: Boolean, onChange: (Int, Int) -> Unit) {
    val lowest = SettingsRules.LOWEST_NOTE
    val highest = SettingsRules.HIGHEST_NOTE
    val minRange = SettingsRules.MIN_RANGE
    Column(Modifier.fillMaxWidth().padding(horizontal = SettingPadding, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.range, NoteNames.displayName(settings.lowLimit), NoteNames.displayName(settings.highLimit)),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${settings.lowLimit} – ${settings.highLimit}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        RangeSlider(
            value = settings.lowLimit.toFloat()..settings.highLimit.toFloat(),
            onValueChange = { range ->
                var low = range.start.roundToInt()
                var high = range.endInclusive.roundToInt()
                // The thumb that moved pushes the other one along.
                if (high - low < minRange) {
                    if (low != settings.lowLimit) high = low + minRange else low = high - minRange
                }
                low = low.coerceIn(lowest, highest - minRange)
                high = high.coerceIn(low + minRange, highest)
                if (low != settings.lowLimit || high != settings.highLimit) onChange(low, high)
            },
            valueRange = lowest.toFloat()..highest.toFloat(),
            enabled = enabled,
        )
        Text(
            stringResource(Res.string.range_hint, minRange, NoteNames.displayName(settings.startPosition)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Java: the start position always follows the range. */
private fun PracticeSettings.withRange(low: Int, high: Int) =
    copy(lowLimit = low, highLimit = high, startPosition = SettingsRules.startPosition(low, high))
