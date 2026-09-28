package io.github.jonnyfrick.musicbootcamp.core.pitch

import io.github.jonnyfrick.musicbootcamp.core.persistence.SetupRepository
import kotlinx.serialization.Serializable

/**
 * The tunable parts of the pitch detection (Preferences → optimization mode). The defaults
 * are what the tests were tuned with; recordings store the values they were made with.
 */
@Serializable
data class DetectionParameters(
    /** Minimum RMS level (full scale = 1) of a stroke; below that everything is ignored. */
    val noiseGate: Double = 0.01,
    /** How much a stroke must raise the level above the hops before it. */
    val onsetRatio: Double = 2.0,
    /** Minimum periodicity (0..1) of a window for its pitch to count. */
    val minClarity: Double = 0.75,
    /** Analysis windows that must agree on the note before it is reported. */
    val confirmFrames: Int = 2,
    /** The first autocorrelation peak reaching this share of the highest one is the period. */
    val peakThreshold: Double = 0.9,
    /** Remove the app's own sound when a reference is available (else input matching it is ignored). */
    val echoCancellation: Boolean = true,
    /** Safety factor on the predicted power of the app's own sound. */
    val overSubtraction: Double = 2.0,
    /** Slowest decay per hop (11.6 ms) of the predicted own sound: the room's reverberation. */
    val reverbDecay: Double = 0.6,
    /** Over the app's sound, a stroke must raise the total level by this factor too. */
    val rawRise: Double = 1.5,
    /** Where the app's note starts, a stroke must exceed this share of its predicted level. */
    val ownSoundShare: Double = 0.5,
    /**
     * Before the app's sound can be removed (or where it is too quiet to measure), a stroke must be
     * this many times louder than the microphone usually is while the app plays; 0 = off (then
     * only the app's current note is ignored meanwhile, as without echo cancellation).
     */
    val fallbackMargin: Double = 0.0,
    /**
     * With the app's sound in the microphone, a stroke within 400 ms after a recognised note must
     * raise the total level by this factor (what remains of that note beats with the app's sound).
     */
    val followUpRise: Double = 2.5,
    /** Quantile of microphone/reference the loudspeaker's gains follow; lower resists the player's notes more. */
    val gainQuantile: Double = 0.5,
)

/** As stored in recordings (`info["detectionParameters"]`). */
fun DetectionParameters.toJson(): String = SetupRepository.json.encodeToString(this)

/** Missing fields take their defaults, so partial overrides like `{"rawRise":1.3}` work too. */
fun detectionParametersFromJson(text: String): DetectionParameters = SetupRepository.json.decodeFromString(text)
