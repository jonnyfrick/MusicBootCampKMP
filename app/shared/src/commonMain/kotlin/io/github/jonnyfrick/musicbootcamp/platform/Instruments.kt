package io.github.jonnyfrick.musicbootcamp.platform

import io.github.jonnyfrick.musicbootcamp.core.audio.Flac
import io.github.jonnyfrick.musicbootcamp.core.audio.PianoSynth
import io.github.jonnyfrick.musicbootcamp.core.audio.SampleSet
import io.github.jonnyfrick.musicbootcamp.core.audio.Sampler
import io.github.jonnyfrick.musicbootcamp.core.audio.SoftwareSynth
import io.github.jonnyfrick.musicbootcamp.core.audio.SwitchingSynth
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
    private const val RECORDED_EVERY = 3
    private val PIANO_NOTES = 21..108 step RECORDED_EVERY

    @Volatile
    private var piano: SampleSet? = null

    @Volatile
    private var complete = false

    val isPianoSampled: Boolean get() = piano != null

    /**
     * Reads and decodes the samples; call from a background dispatcher. Safe to call again.
     * The ones for [first] (the exercise's range) come first and are usable at once, so a phone's
     * browser needs to decode only a third of them before the exercise sounds sampled.
     * [decode] turns a file into samples (a platform may have a faster decoder than [Flac][io.github.jonnyfrick.musicbootcamp.core.audio.Flac]).
     */
    suspend fun load(first: IntRange? = null, decode: suspend (ByteArray) -> DecodedAudio = ::decodeFlac) {
        if (complete) return
        val order = loadingOrder(first)
        val decoded = mutableMapOf<Int, FloatArray>()
        var rate = 0
        for (notes in order) {
            for (note in notes) {
                val audio = decode(Res.readBytes("files/piano/${note.toString().padStart(3, '0')}.flac"))
                rate = audio.sampleRate
                decoded[note] = audio.samples
            }
            piano = SampleSet(rate, decoded.toMap())
        }
        complete = true
    }

    /** The recorded notes in the batches they are decoded in: those around [first] first, then the rest. */
    internal fun loadingOrder(first: IntRange?): List<List<Int>> {
        val wanted = first?.let { (it.first - RECORDED_EVERY)..(it.last + RECORDED_EVERY) }
        return PIANO_NOTES.partition { wanted != null && it in wanted }.let { (needed, rest) -> listOf(needed, rest) }.filter { it.isNotEmpty() }
    }

    /**
     * The app's piano for an output of [sampleRate]: sampled if the samples are loaded; else
     * synthetic until they are, then sampled from the next note on (in a browser, decoding them
     * takes a few seconds, and an exercise may well start before).
     */
    fun piano(sampleRate: Int): SoftwareSynth =
        piano?.let { Sampler(it, sampleRate) } ?: SwitchingSynth(PianoSynth(sampleRate)) { piano?.let { Sampler(it, sampleRate) } }
}

/** Decoded mono audio, -1..1. */
class DecodedAudio(val sampleRate: Int, val samples: FloatArray)

/** The common FLAC decoder, in Kotlin, on every platform. */
fun decodeFlac(bytes: ByteArray): DecodedAudio {
    val audio = Flac.decode(bytes)
    val scale = 1f / (1 shl (audio.bitsPerSample - 1))
    val channel = audio.channels[0]
    return DecodedAudio(audio.sampleRate, FloatArray(channel.size) { channel[it] * scale })
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
