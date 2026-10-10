package io.github.jonnyfrick.musicbootcamp.core.model

import io.github.jonnyfrick.musicbootcamp.core.pitch.InstrumentProfile
import kotlinx.serialization.Serializable

/**
 * The instrument the player answers on with the microphone: how the recognition listens
 * ([profile]) and, for transposing instruments, how far written notes lie above sounding ones.
 * The exercise always works in sounding pitch (the player hears and plays by ear); the
 * transposition is only an option for how notes are shown.
 */
@Serializable
enum class PlayerInstrument(val profile: InstrumentProfile, val transposition: Int = 0) {
    PIANO(InstrumentProfile.PIANO),
    FLUTE(InstrumentProfile.FLUTE),
    OBOE(InstrumentProfile.WIND),
    CLARINET_B_FLAT(InstrumentProfile.CLARINET, 2),
    ALTO_SAXOPHONE(InstrumentProfile.WIND, 9),
    TENOR_SAXOPHONE(InstrumentProfile.WIND, 14),
    TRUMPET_B_FLAT(InstrumentProfile.WIND, 2),
    HORN_F(InstrumentProfile.WIND, 7),
    TROMBONE(InstrumentProfile.WIND),

    /** Any other instrument with held tones (strings, voice): the general profile for those. */
    OTHER_SUSTAINED(InstrumentProfile.WIND),
}
