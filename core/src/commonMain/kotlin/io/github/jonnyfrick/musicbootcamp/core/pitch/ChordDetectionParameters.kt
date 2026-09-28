package io.github.jonnyfrick.musicbootcamp.core.pitch

import io.github.jonnyfrick.musicbootcamp.core.persistence.SetupRepository
import kotlinx.serialization.Serializable

/**
 * The tunable parts of the chord recognition (several voices from the microphone), kept apart
 * from the single-note detection and stored per number of voices.
 */
/** How chords are told apart. */
@Serializable
enum class ChordMethod {
    /** How the spectrum's shape matches note templates (partial strengths; the calibration fits one microphone position). */
    TEMPLATES,

    /** Which partials are there, after levelling out the room's colouring (robust to the microphone position). */
    HARMONIC,
}

@Serializable
data class ChordDetectionParameters(
    val method: ChordMethod = ChordMethod.HARMONIC,
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
    /** HARMONIC: width (semitones) of the envelope the spectrum is divided by to level out the room. */
    val whitenSemitones: Double = 7.0,
    /** HARMONIC: partials per note looked at at most (all notes up to the same frequency, about 5 kHz). */
    val partials: Int = 24,
    /** HARMONIC: a partial counts as present above this multiple of its surroundings. */
    val presence: Double = 1.6,
    /** HARMONIC: score lost per expected partial that is missing (e.g. the lower octave's odd partials). */
    val missingPenalty: Double = 0.06,
    /** HARMONIC: score lost per share of clear peaks that no note of the chord explains. */
    val unexplainedPenalty: Double = 0.5,
    /** HARMONIC: how much of the chance to catch peaks (the share of the spectrum a chord's partials cover) is taken off. */
    val chanceWeight: Double = 0.25,
)

fun ChordDetectionParameters.toJson(): String = SetupRepository.json.encodeToString(this)

fun chordDetectionParametersFromJson(text: String): ChordDetectionParameters = SetupRepository.json.decodeFromString(text)
