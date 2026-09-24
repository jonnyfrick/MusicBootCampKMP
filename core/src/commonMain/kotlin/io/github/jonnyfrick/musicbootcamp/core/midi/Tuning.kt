package io.github.jonnyfrick.musicbootcamp.core.midi

import kotlin.math.floor
import kotlin.math.ln

/**
 * Fine tuning of the whole output relative to concert pitch A4 (Kammerton A),
 * realised with a global pitch bend (Java: `BootCampMidiInterface.applyTuning`).
 */
object Tuning {
    const val STANDARD_A_HZ = 440.0
    const val MIN_A_HZ = 415.0
    const val MAX_A_HZ = 466.0
    const val STEP_HZ = 0.5

    private const val PITCH_BEND_RANGE_SEMITONES = 2
    private const val MIDI_CHANNELS = 16
    private const val PITCH_BEND_CENTER = 8192

    /** cents = 1200 · log2(referenceAHz / 440); non-positive input means standard tuning. */
    fun cents(referenceAHz: Double): Double {
        val reference = if (referenceAHz <= 0.0) STANDARD_A_HZ else referenceAHz
        return 1200.0 * (ln(reference / STANDARD_A_HZ) / ln(2.0))
    }

    /** 14-bit pitch bend value for [referenceAHz], clamped to the programmed ±2 semitone range. */
    fun pitchBendValue(referenceAHz: Double): Int {
        val maxCents = PITCH_BEND_RANGE_SEMITONES * 100.0
        val clamped = cents(referenceAHz).coerceIn(-maxCents, maxCents)
        val bend = floor(PITCH_BEND_CENTER + (clamped / maxCents) * PITCH_BEND_CENTER + 0.5).toInt()
        return bend.coerceIn(0, 16383)
    }

    /**
     * For every channel: set the pitch bend sensitivity via RPN 0, null the RPN again
     * and send the pitch bend itself.
     */
    fun messages(referenceAHz: Double): List<MidiMessage> {
        val bend = pitchBendValue(referenceAHz)
        val lsb = bend and 0x7F
        val msb = (bend shr 7) and 0x7F
        return (0 until MIDI_CHANNELS).flatMap { channel ->
            listOf(
                MidiMessage.controlChange(101, 0, channel), // RPN MSB = 0
                MidiMessage.controlChange(100, 0, channel), // RPN LSB = 0
                MidiMessage.controlChange(6, PITCH_BEND_RANGE_SEMITONES, channel), // data entry MSB (semitones)
                MidiMessage.controlChange(38, 0, channel), // data entry LSB (cents)
                MidiMessage.controlChange(101, 127, channel), // null the RPN
                MidiMessage.controlChange(100, 127, channel),
                MidiMessage.pitchBend(lsb, msb, channel),
            )
        }
    }
}
