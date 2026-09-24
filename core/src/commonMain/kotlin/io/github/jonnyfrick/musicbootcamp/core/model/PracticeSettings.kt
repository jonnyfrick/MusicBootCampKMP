package io.github.jonnyfrick.musicbootcamp.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class PracticeMode(
    /** Identifier used in the Java version's settings files. */
    val legacyId: String,
    val voices: Int,
    val isImplemented: Boolean,
) {
    MONOPHONIC("monophonic", 1, true),
    TWO_VOICES_PURE_RANDOM("two_voices_pure_random", 2, true),

    /** Selectable in the Java UI but never implemented there either. */
    HOMOPHONIC_MODES("homophonic_modes", 0, false),
    ;

    companion object {
        fun fromLegacyId(id: String): PracticeMode? = entries.firstOrNull { it.legacyId.equals(id, ignoreCase = true) }
    }
}

/** Stored and editable like in the Java version, but not used by the engine there either. */
@Serializable
enum class Direction(val legacyId: String) {
    LINEAR("linear"),
    RANDOM("random"),
    ALTERNATING("alternating"),
    ;

    companion object {
        fun fromLegacyId(id: String): Direction? = entries.firstOrNull { it.legacyId.equals(id, ignoreCase = true) }
    }
}

/**
 * Everything that shapes an exercise (Java: `ReplayParameters` minus the
 * learned sequences, which live in [io.github.jonnyfrick.musicbootcamp.core.learning.LearnedSequences]).
 */
@Serializable
data class PracticeSettings(
    val mode: PracticeMode = PracticeMode.MONOPHONIC,
    val direction: Direction = Direction.LINEAR,
    /** Seconds between two exercise steps. */
    val breathingTime: Float = 3.0f,
    /** How long a note sounds, in percent of [breathingTime]. */
    val sustain: Int = 50,
    val midiOutVelocity: Int = 60,
    val lowLimit: Int = 48,
    val highLimit: Int = 72,
    val startPosition: Int = 60,
    /**
     * Weight of each interval (index 0 = minor second … index 10 = major seventh),
     * 0 = never, 10 = most often.
     */
    val intervalPriorities: List<Int> = List(INTERVAL_COUNT) { 5 },
    /** Probability (0..1) of embedding a memorised sequence instead of a random step. */
    val learnedProbability: Double = 0.0,
    /** How many preceding steps are remembered when a mistake is stored. */
    val memorySize: Int = 5,
    val learnNewSequences: Boolean = false,
    /** Stored like in the Java version; no algorithm uses it yet. */
    val transpositionsProbability: Double = 0.0,
) {
    init {
        require(intervalPriorities.size == INTERVAL_COUNT) { "Expected $INTERVAL_COUNT interval priorities" }
    }

    /** Java: `breathing_time_ * sustain_ * 1000 / 100` in float arithmetic, truncated. */
    val noteDurationMillis: Long get() = (breathingTime * sustain * 1000 / 100).toLong()

    /** Java: `Math.round(breathing_time_ * 1000)` on a float. */
    val stepPeriodMillis: Long get() = javaRound(breathingTime * 1000)

    companion object {
        const val INTERVAL_COUNT = 11

        /** Labels of the interval sliders, index = semitones - 1. */
        val INTERVAL_LABELS = listOf("b2", "2", "b3", "3", "4", "b5", "5", "b6", "6", "7", "j7")
    }
}

/** `java.lang.Math.round(float)`: rounds half up (Kotlin's `roundToInt` rounds half away from zero). */
internal fun javaRound(value: Float): Long = kotlin.math.floor(value + 0.5f).toLong()

/** `java.lang.Math.round(double)`. */
internal fun javaRound(value: Double): Long = kotlin.math.floor(value + 0.5).toLong()
