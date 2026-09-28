package io.github.jonnyfrick.musicbootcamp.core.pitch

import io.github.jonnyfrick.musicbootcamp.core.persistence.SetupRepository
import kotlinx.serialization.Serializable

/**
 * The tunable parts of the chord recognition (several voices from the microphone), kept apart
 * from the single-note detection and stored per number of voices.
 */
@Serializable
data class ChordDetectionParameters(
    /** Stroke detection and removal of the app's own sound, as for single notes. */
    val strokes: DetectionParameters = DetectionParameters(),
    /** The analysis window after a stroke, in milliseconds: skips the hammer noise, averages the rest. */
    val windowStartMillis: Int = 30,
    val windowEndMillis: Int = 300,
    /** Strokes this close together are one chord (fingers never land exactly together). */
    val chordSpreadMillis: Int = 80,
    /** Added to the relative misfit per note of a hypothesis: prefers the simpler explanation. */
    val notePenalty: Double = 0.02,
    /** A note must carry at least this share of a hypothesis' energy, else it is not really there. */
    val minNoteShare: Double = 0.06,
    /** Subtracted from the score of the given chord(s): above 0 leans towards "played correctly". */
    val givenBias: Double = 0.0,
    /** How much of the spectrum right before the stroke (the previous chord ringing) is removed. */
    val backgroundWeight: Double = 1.0,
    /** A stroke whose remaining spectrum is weaker than this (RMS) is ignored. */
    val minLevel: Double = 0.003,
    /**
     * Share of the window's level that must be new (not ringing before the stroke, not the app's):
     * beating of close notes looks like a stroke to the level-based detection, but brings nothing new.
     */
    val minNewShare: Double = 0.3,
)

fun ChordDetectionParameters.toJson(): String = SetupRepository.json.encodeToString(this)

fun chordDetectionParametersFromJson(text: String): ChordDetectionParameters = SetupRepository.json.decodeFromString(text)
