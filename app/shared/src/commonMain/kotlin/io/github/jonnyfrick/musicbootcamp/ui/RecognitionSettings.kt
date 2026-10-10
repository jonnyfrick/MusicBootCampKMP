package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.jonnyfrick.musicbootcamp.core.persistence.InputSource
import io.github.jonnyfrick.musicbootcamp.core.persistence.OPTIMIZATION_STEP_RANGE
import io.github.jonnyfrick.musicbootcamp.core.pitch.ChordDetectionParameters
import io.github.jonnyfrick.musicbootcamp.core.pitch.ChordMethod
import io.github.jonnyfrick.musicbootcamp.core.pitch.DetectionParameters
import io.github.jonnyfrick.musicbootcamp.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** One tunable detection parameter of type [P]: a slider over [range] in [step]s. */
private class Parameter<P>(
    val label: StringResource,
    val hint: StringResource,
    val range: ClosedFloatingPointRange<Double>,
    val step: Double,
    val get: (P) -> Double,
    val set: (P, Double) -> P,
)

private val noteParameters = listOf<Parameter<DetectionParameters>>(
    Parameter(Res.string.p_noise_gate, Res.string.p_noise_gate_hint, 0.002..0.05, 0.002, { it.noiseGate }, { p, v -> p.copy(noiseGate = v) }),
    Parameter(Res.string.p_onset_ratio, Res.string.p_onset_ratio_hint, 1.2..4.0, 0.1, { it.onsetRatio }, { p, v -> p.copy(onsetRatio = v) }),
    Parameter(Res.string.p_min_clarity, Res.string.p_min_clarity_hint, 0.5..0.95, 0.05, { it.minClarity }, { p, v -> p.copy(minClarity = v) }),
    Parameter(Res.string.p_confirm_windows, Res.string.p_confirm_windows_hint, 1.0..4.0, 1.0,
        { it.confirmFrames.toDouble() }, { p, v -> p.copy(confirmFrames = v.roundToInt()) }),
    Parameter(Res.string.p_peak_threshold, Res.string.p_peak_threshold_hint, 0.7..1.0, 0.02, { it.peakThreshold }, { p, v -> p.copy(peakThreshold = v) }),
    Parameter(Res.string.p_over_subtraction, Res.string.p_over_subtraction_hint, 1.0..4.0, 0.25,
        { it.overSubtraction }, { p, v -> p.copy(overSubtraction = v) }),
    Parameter(Res.string.p_reverb_decay, Res.string.p_reverb_decay_hint, 0.3..0.9, 0.05, { it.reverbDecay }, { p, v -> p.copy(reverbDecay = v) }),
    Parameter(Res.string.p_level_rise, Res.string.p_level_rise_hint, 1.0..2.5, 0.05, { it.rawRise }, { p, v -> p.copy(rawRise = v) }),
    Parameter(Res.string.p_own_sound_share, Res.string.p_own_sound_share_hint, 0.0..1.5, 0.05,
        { it.ownSoundShare }, { p, v -> p.copy(ownSoundShare = v) }),
    Parameter(Res.string.p_fallback_margin, Res.string.p_fallback_margin_hint, 1.0..6.0, 0.25,
        { it.fallbackMargin }, { p, v -> p.copy(fallbackMargin = v) }),
    Parameter(Res.string.p_follow_up_rise, Res.string.p_follow_up_rise_hint, 1.0..5.0, 0.25,
        { it.followUpRise }, { p, v -> p.copy(followUpRise = v) }),
)

private val chordParameters = listOf<Parameter<ChordDetectionParameters>>(
    Parameter(Res.string.p_window_end, Res.string.p_window_end_hint, 200.0..600.0, 25.0,
        { it.windowEndMillis.toDouble() }, { p, v -> p.copy(windowEndMillis = v.roundToInt()) }),
    Parameter(Res.string.p_chord_spread, Res.string.p_chord_spread_hint, 20.0..200.0, 10.0,
        { it.chordSpreadMillis.toDouble() }, { p, v -> p.copy(chordSpreadMillis = v.roundToInt()) }),
    Parameter(Res.string.p_note_penalty, Res.string.p_note_penalty_hint, 0.0..0.1, 0.005, { it.notePenalty }, { p, v -> p.copy(notePenalty = v) }),
    Parameter(Res.string.p_min_note_share, Res.string.p_min_note_share_hint, 0.01..0.3, 0.01, { it.minNoteShare }, { p, v -> p.copy(minNoteShare = v) }),
    Parameter(Res.string.p_given_bias, Res.string.p_given_bias_hint, 0.0..0.1, 0.005, { it.givenBias }, { p, v -> p.copy(givenBias = v) }),
    Parameter(Res.string.p_background_weight, Res.string.p_background_weight_hint, 0.0..2.0, 0.1,
        { it.backgroundWeight }, { p, v -> p.copy(backgroundWeight = v) }),
    Parameter(Res.string.p_min_new_share, Res.string.p_min_new_share_hint, 0.0..0.8, 0.05, { it.minNewShare }, { p, v -> p.copy(minNewShare = v) }),
    Parameter(Res.string.p_whitening, Res.string.p_whitening_hint, 2.0..14.0, 1.0, { it.whitenSemitones }, { p, v -> p.copy(whitenSemitones = v) }),
    Parameter(Res.string.p_presence, Res.string.p_presence_hint, 1.1..3.0, 0.1, { it.presence }, { p, v -> p.copy(presence = v) }),
    Parameter(Res.string.p_missing_penalty, Res.string.p_missing_penalty_hint, 0.0..0.2, 0.01,
        { it.missingPenalty }, { p, v -> p.copy(missingPenalty = v) }),
    Parameter(Res.string.p_chance_weight, Res.string.p_chance_weight_hint, 0.0..1.0, 0.05, { it.chanceWeight }, { p, v -> p.copy(chanceWeight = v) }),
    Parameter(Res.string.p_noise_gate, Res.string.p_noise_gate_hint, 0.002..0.05, 0.002,
        { it.strokes.noiseGate }, { p, v -> p.copy(strokes = p.strokes.copy(noiseGate = v)) }),
    Parameter(Res.string.p_onset_ratio, Res.string.p_onset_ratio_hint, 1.2..4.0, 0.1,
        { it.strokes.onsetRatio }, { p, v -> p.copy(strokes = p.strokes.copy(onsetRatio = v)) }),
)

/**
 * Settings → Recognition: recording, the optimization mode (runs of a fixed length, always
 * recorded) and, in it, the detection parameters for the exercise's number of voices.
 */
@Composable
internal fun RecognitionSettings(controller: AppController) {
    val preferences = controller.preferences
    val running = controller.running
    if (preferences.inputSource != InputSource.MICROPHONE) SettingHint(stringResource(Res.string.recognition_microphone_only))

    SettingsSection(null) {
        controller.recordingsLocation?.let { location ->
            SwitchSetting(
                title = stringResource(Res.string.record_exercises),
                checked = preferences.recordMicrophone,
                onCheckedChange = controller::setRecordMicrophone,
                enabled = !running,
                supporting = stringResource(Res.string.record_exercises_hint, location),
            )
        }
        SwitchSetting(
            title = stringResource(Res.string.optimization_mode),
            checked = preferences.optimizationMode,
            onCheckedChange = controller::setOptimizationMode,
            enabled = !running,
            supporting = stringResource(Res.string.optimization_mode_hint),
        )
        if (preferences.optimizationMode) {
            IntSliderSetting(
                title = stringResource(Res.string.stop_after),
                value = preferences.optimizationSteps,
                range = OPTIMIZATION_STEP_RANGE,
                onChange = controller::setOptimizationSteps,
                enabled = !running,
                valueText = stringResource(Res.string.value_notes, preferences.optimizationSteps),
                fineSteps = true,
            )
        }
    }
    if (!preferences.optimizationMode) return

    val voices = controller.settings.mode.voices
    if (voices > 1) {
        ChordSettings(controller, voices)
    } else {
        NoteSettings(controller)
    }
}

@Composable
private fun NoteSettings(controller: AppController) {
    val running = controller.running
    val parameters = controller.preferences.detectionParameters
    SettingsSection(stringResource(Res.string.section_parameters)) {
        SwitchSetting(
            title = stringResource(Res.string.remove_own_sound),
            checked = parameters.echoCancellation,
            onCheckedChange = { controller.setDetectionParameters(parameters.copy(echoCancellation = it)) },
            enabled = !running,
            supporting = stringResource(Res.string.remove_own_sound_hint),
        )
        ParameterSliders(noteParameters, parameters, enabled = !running, onChange = controller::setDetectionParameters)
        ButtonRow {
            TextButton(onClick = { controller.setDetectionParameters(DetectionParameters()) }, enabled = !running) {
                Text(stringResource(Res.string.reset_defaults))
            }
        }
    }
}

/** The chord recognition's parameters for [voices] voices, and the calibration of the piano. */
@Composable
private fun ChordSettings(controller: AppController, voices: Int) {
    val running = controller.running || controller.calibrating
    val parameters = controller.preferences.chordParameters(voices)
    fun set(changed: ChordDetectionParameters) = controller.setChordDetectionParameters(voices, changed)
    SettingsSection(stringResource(Res.string.section_parameters)) {
        SettingHint(stringResource(Res.string.chords_for_voices, voices))
        SwitchSetting(
            title = stringResource(Res.string.harmonic_method),
            checked = parameters.method == ChordMethod.HARMONIC,
            onCheckedChange = { set(parameters.copy(method = if (it) ChordMethod.HARMONIC else ChordMethod.TEMPLATES)) },
            enabled = !running,
            supporting = stringResource(Res.string.harmonic_method_hint),
        )
        SwitchSetting(
            title = stringResource(Res.string.remove_own_sound),
            checked = parameters.strokes.echoCancellation,
            onCheckedChange = { set(parameters.copy(strokes = parameters.strokes.copy(echoCancellation = it))) },
            enabled = !running,
        )
        ParameterSliders(chordParameters, parameters, enabled = !running, onChange = ::set)
        ButtonRow {
            TextButton(onClick = { set(ChordDetectionParameters()) }, enabled = !running) { Text(stringResource(Res.string.reset_defaults)) }
        }
    }
    CalibrationSettings(controller)
}

@Composable
private fun <P> ParameterSliders(parameters: List<Parameter<P>>, values: P, enabled: Boolean, onChange: (P) -> Unit) {
    for (parameter in parameters) {
        val value = parameter.get(values)
        SliderSetting(
            title = stringResource(parameter.label),
            value = value,
            range = parameter.range,
            step = parameter.step,
            valueText = format(value),
            onChange = { onChange(parameter.set(values, it)) },
            enabled = enabled,
            supporting = stringResource(parameter.hint),
        )
    }
}

/** Learning the player's piano: every note of the range once. */
@Composable
private fun CalibrationSettings(controller: AppController) {
    val settings = controller.settings
    val learned = controller.learnedTemplates.notes.size
    SettingsSection(stringResource(Res.string.section_calibration)) {
        SettingHint(
            (if (learned == 0) stringResource(Res.string.calibration_none) else stringResource(Res.string.calibration_some, learned)) + " " +
                stringResource(Res.string.calibration_hint, controller.noteName(settings.lowLimit), controller.noteName(settings.highLimit)),
        )
        if (controller.calibrating) {
            val (done, total) = controller.calibrationProgress
            Card(Modifier.fillMaxWidth().padding(horizontal = SettingPadding, vertical = 8.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        controller.calibrationTarget?.let { stringResource(Res.string.calibration_play, controller.noteName(it)) }
                            ?: stringResource(Res.string.calibration_done),
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Text(stringResource(Res.string.calibration_progress, done, total), style = MaterialTheme.typography.bodyMedium)
                    controller.calibrationHeard?.let { heard ->
                        Text(
                            stringResource(Res.string.calibration_heard, heard.joinToString(" ") { controller.noteName(it) }),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (controller.microphoneLevel * 5).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.width(160.dp).padding(top = 8.dp),
                    )
                }
            }
            ButtonRow {
                OutlinedButton(onClick = controller::stopCalibration) { Text(stringResource(Res.string.stop_calibration)) }
            }
        } else {
            ButtonRow {
                OutlinedButton(onClick = controller::startCalibration, enabled = !controller.running) { Text(stringResource(Res.string.calibrate)) }
                if (learned > 0) {
                    TextButton(onClick = { controller.forgetCalibration() }, enabled = !controller.running) {
                        Text(stringResource(Res.string.forget_calibration))
                    }
                }
            }
        }
    }
}

private fun format(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else ((value * 1000).roundToInt() / 1000.0).toString()
