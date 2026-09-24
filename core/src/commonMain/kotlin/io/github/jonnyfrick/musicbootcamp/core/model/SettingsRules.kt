package io.github.jonnyfrick.musicbootcamp.core.model

/** A corrected value plus the hint the Java dialogs showed when they had to correct it. */
data class Checked<T>(val value: T, val message: String? = null)

/** Input rules of the Java options dialogs (`RandomOptionsDialog`, `RunDialog`, `PreferencesDialog`). */
object SettingsRules {
    const val HIGHEST_NOTE = 108
    const val LOWEST_NOTE = 12
    const val MIN_RANGE = 24

    const val MIN_BREATHING_TIME = 0.1f
    const val MAX_BREATHING_TIME = 10.0f

    const val MAX_INTERVAL_PRIORITY = 10
    const val MAX_SUSTAIN = 100

    /** Java: `syncHighLimitLabel`. */
    fun checkHighLimit(input: String, lowLimit: Int, previous: Int): Checked<Int> {
        var high = input.trim().toIntOrNull() ?: return Checked(previous, "This is not a correctly entered number!")
        var message: String? = null
        if (high > HIGHEST_NOTE) {
            high = HIGHEST_NOTE
            message = "You are not a bat! The high limit was set to $high."
        }
        if (high - lowLimit < MIN_RANGE) {
            high = lowLimit + MIN_RANGE
            message = "This range is too small. It has to be $MIN_RANGE half steps at least. The high limit was set to $high."
        }
        return Checked(high, message)
    }

    /** Java: `syncLowLimitLabel`. */
    fun checkLowLimit(input: String, highLimit: Int, previous: Int): Checked<Int> {
        var low = input.trim().toIntOrNull() ?: return Checked(previous, "This is not a correctly entered number!")
        var message: String? = null
        if (low < LOWEST_NOTE) {
            low = LOWEST_NOTE
            message = "You are not an elephant! The low limit was set to $LOWEST_NOTE."
        }
        if (highLimit - low < MIN_RANGE) {
            low = highLimit - MIN_RANGE
            message = "This range is too small. It has to be $MIN_RANGE half steps at least. The low limit was set to $low."
        }
        return Checked(low, message)
    }

    /** Java: `syncStartPosition` — the middle of the range (integer division). */
    fun startPosition(lowLimit: Int, highLimit: Int): Int = (highLimit - lowLimit) / 2 + lowLimit

    /** Java: `syncBreathingTimeSlider`. */
    fun checkBreathingTime(input: String, previous: Float): Checked<Float> {
        val value = input.trim().toFloatOrNull() ?: return Checked(previous, "This is not a correctly entered number!")
        return when {
            value > MAX_BREATHING_TIME -> Checked(MAX_BREATHING_TIME, "Too slow, buddy! Breathing time was set to $MAX_BREATHING_TIME seconds.")
            value < MIN_BREATHING_TIME -> Checked(MIN_BREATHING_TIME, "You can't be serious! Breathing time was set to $MIN_BREATHING_TIME seconds.")
            else -> Checked(value)
        }
    }

    /** New: at least one interval must be possible, otherwise random steps cannot move. */
    fun intervalsProblem(priorities: List<Int>): String? =
        if (priorities.all { it == 0 }) "Give at least one interval a weight above 0." else null
}
