package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.jonnyfrick.musicbootcamp.core.midi.NoteNames
import io.github.jonnyfrick.musicbootcamp.core.persistence.OPTIMIZATION_STEP_RANGE
import io.github.jonnyfrick.musicbootcamp.core.pitch.DetectionParameters
import kotlin.math.roundToInt

/** One tunable detection parameter: a slider from [range] in [step]s. */
private class ParameterSlider(
    val label: String,
    val hint: String,
    val range: ClosedFloatingPointRange<Double>,
    val step: Double,
    val get: (DetectionParameters) -> Double,
    val set: (DetectionParameters, Double) -> DetectionParameters,
)

private val parameterSliders = listOf(
    ParameterSlider("Noise gate", "Minimum level of a stroke (RMS, full scale 1).", 0.002..0.05, 0.002,
        { it.noiseGate }, { p, v -> p.copy(noiseGate = v) }),
    ParameterSlider("Onset ratio", "How much a stroke must raise the level above the hops before it.", 1.2..4.0, 0.1,
        { it.onsetRatio }, { p, v -> p.copy(onsetRatio = v) }),
    ParameterSlider("Min. clarity", "How periodic a window must be for its pitch to count.", 0.5..0.95, 0.05,
        { it.minClarity }, { p, v -> p.copy(minClarity = v) }),
    ParameterSlider("Confirm windows", "Analysis windows that must agree on the note.", 1.0..4.0, 1.0,
        { it.confirmFrames.toDouble() }, { p, v -> p.copy(confirmFrames = v.roundToInt()) }),
    ParameterSlider("Peak threshold", "Lower: fewer octave-down errors, more octave-up errors.", 0.7..1.0, 0.02,
        { it.peakThreshold }, { p, v -> p.copy(peakThreshold = v) }),
    ParameterSlider("Over-subtraction", "Safety factor on the app's predicted own sound.", 1.0..4.0, 0.25,
        { it.overSubtraction }, { p, v -> p.copy(overSubtraction = v) }),
    ParameterSlider("Reverb decay", "Slowest decay of the own sound per 11.6 ms (the room's reverberation).", 0.3..0.9, 0.05,
        { it.reverbDecay }, { p, v -> p.copy(reverbDecay = v) }),
    ParameterSlider("Level rise", "Over the app's sound, a stroke must raise the total level by this factor.", 1.0..2.5, 0.05,
        { it.rawRise }, { p, v -> p.copy(rawRise = v) }),
    ParameterSlider("Own-sound share", "Where the app's note starts, a stroke must exceed this share of it.", 0.0..1.5, 0.05,
        { it.ownSoundShare }, { p, v -> p.copy(ownSoundShare = v) }),
    ParameterSlider("Fallback margin", "Until the own sound is learned (or if it is too quiet to learn), strokes must be " +
        "this many times louder than the microphone while the app plays.", 1.0..6.0, 0.25,
        { it.fallbackMargin }, { p, v -> p.copy(fallbackMargin = v) }),
    ParameterSlider("Follow-up rise", "Within 400 ms after a recognised note, a new stroke over the app's sound must " +
        "raise the level by this factor (against beating).", 1.0..5.0, 0.25,
        { it.followUpRise }, { p, v -> p.copy(followUpRise = v) }),
)

/** Preferences → Microphone: runs of a fixed length, always recorded, with the detection parameters editable. */
@Composable
internal fun OptimizationSettings(controller: AppController) {
    val preferences = controller.preferences
    val running = controller.running
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = preferences.optimizationMode, onCheckedChange = controller::setOptimizationMode, enabled = !running)
        Spacer(Modifier.width(12.dp))
        Text("Optimization mode")
    }
    Hint(
        "For tuning the recognition: every exercise stops by itself after a number of notes and is recorded; " +
            "afterwards the Practice tab lists each step, so you can report where you played something else.",
    )
    if (!preferences.optimizationMode) return

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Stop after", Modifier.width(140.dp))
        Slider(
            value = preferences.optimizationSteps.toFloat(),
            onValueChange = { controller.setOptimizationSteps(it.roundToInt()) },
            valueRange = OPTIMIZATION_STEP_RANGE.first.toFloat()..OPTIMIZATION_STEP_RANGE.last.toFloat(),
            enabled = !running,
            modifier = Modifier.weight(1f),
        )
        Text("${preferences.optimizationSteps} notes", Modifier.width(90.dp))
    }

    val parameters = preferences.detectionParameters
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = parameters.echoCancellation,
            onCheckedChange = { controller.setDetectionParameters(parameters.copy(echoCancellation = it)) },
            enabled = !running,
        )
        Spacer(Modifier.width(12.dp))
        Text("Remove the app's own sound")
    }
    Hint("Off: while the app plays a note, that note and its octaves are ignored instead (for comparison).")
    for (slider in parameterSliders) {
        val value = slider.get(parameters)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(slider.label, Modifier.width(140.dp))
            Slider(
                value = value.toFloat(),
                onValueChange = { raw ->
                    val snapped = slider.range.start + ((raw - slider.range.start) / slider.step).roundToInt() * slider.step
                    controller.setDetectionParameters(slider.set(parameters, snapped.coerceIn(slider.range)))
                },
                valueRange = slider.range.start.toFloat()..slider.range.endInclusive.toFloat(),
                enabled = !running,
                modifier = Modifier.weight(1f),
            )
            Text(format(value), Modifier.width(90.dp))
        }
        Hint(slider.hint)
    }
    TextButton(onClick = { controller.setDetectionParameters(DetectionParameters()) }, enabled = !running) {
        Text("Reset to defaults")
    }
}

/** Practice tab after a run in optimization mode: each step with what was recognised. */
@Composable
internal fun RunSummaryCard(summary: RunSummary) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Last run, step by step", style = MaterialTheme.typography.titleMedium)
            Hint("Report the numbers of the steps where you played something other than the given note (or nothing).")
            for (step in summary.steps) {
                val given = step.given.joinToString(" ") { NoteNames.displayName(it) }
                val heard = step.detected.joinToString(" ") { NoteNames.displayName(it) }.ifEmpty { "–" }
                val result = when (step.correct) {
                    true -> "✓"
                    false -> "✗"
                    null -> " "
                }
                Text(
                    "${step.number.toString().padStart(2)}  $result  given ${given.padEnd(4)} heard $heard",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Hint("Recording: ${summary.recording}")
        }
    }
}

private fun format(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else ((value * 1000).roundToInt() / 1000.0).toString()
