package io.github.jonnyfrick.musicbootcamp.platform

import io.github.jonnyfrick.musicbootcamp.core.audio.PianoSynth
import io.github.jonnyfrick.musicbootcamp.core.audio.SampleSet
import io.github.jonnyfrick.musicbootcamp.core.audio.Sampler
import io.github.jonnyfrick.musicbootcamp.core.audio.SoftwareSynth
import io.github.jonnyfrick.musicbootcamp.resources.Res
import kotlin.concurrent.Volatile

/** The MIDI output under which every platform offers the app's own piano; stored in the preferences, so it must not change. */
const val BUILT_IN_PIANO = "MusicBootCamp Piano"

/**
 * The instruments the app plays itself. The piano's samples (Salamander Grand Piano by Alexander
 * Holm, CC BY 3.0; prepared by `tools/prepare_piano_samples.py`) are part of the app's resources
 * and decoded once, in the background, when the app starts ([load]); until then, or if that
 * fails, a note sounds from the synthetic [PianoSynth].
 */
object Instruments {
    /** The recorded notes: every minor third from A0 to C8, as the original is sampled. */
    private val PIANO_NOTES = 21..108 step 3

    @Volatile
    private var piano: SampleSet? = null

    val isPianoSampled: Boolean get() = piano != null

    /** Reads and decodes the samples; call from a background dispatcher. Safe to call again. */
    suspend fun load() {
        if (piano != null) return
        piano = SampleSet.fromFlac(PIANO_NOTES.associateWith { note -> Res.readBytes("files/piano/${note.toString().padStart(3, '0')}.flac") })
    }

    /** The app's piano for an output of [sampleRate]: sampled if the samples are loaded, else synthetic. */
    fun piano(sampleRate: Int): SoftwareSynth = piano?.let { Sampler(it, sampleRate) } ?: PianoSynth(sampleRate)
}

/**
 * [devices] plus a synthesizer of the app's own as one more MIDI output, which the platforms
 * without a system synthesizer (and the desktop, next to Gervill) offer this way.
 */
class SynthMidiBackend(
    private val devices: MidiBackend,
    private val synth: RenderedSynth,
    /** Listed first, which makes it the output chosen by default. */
    private val first: Boolean = true,
    private val sampleRate: Int = 44_100,
) : MidiBackend by devices {
    override fun outputDevices(): List<String> =
        if (first) listOf(synth.deviceName) + devices.outputDevices() else devices.outputDevices() + synth.deviceName

    override fun openOutput(name: String): MidiOutputPort = if (name == synth.deviceName) synth.open(sampleRate) else devices.openOutput(name)
}
