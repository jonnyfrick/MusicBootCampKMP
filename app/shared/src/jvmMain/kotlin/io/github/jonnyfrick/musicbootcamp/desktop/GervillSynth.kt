package io.github.jonnyfrick.musicbootcamp.desktop

import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.platform.PlayedAudioBuffer
import io.github.jonnyfrick.musicbootcamp.platform.RenderedOutputPort
import io.github.jonnyfrick.musicbootcamp.platform.RenderedSynth
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage
import javax.sound.midi.Synthesizer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

/**
 * Java's software synthesizer Gervill, rendered by the app instead of playing on its own, so
 * the samples sent to the loudspeaker are known and can be removed from the microphone signal.
 *
 * Needs `--add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED` (set for `run` and the
 * packaged app in `app/desktopApp/build.gradle.kts`); without it [open] throws.
 */
class GervillSynth : RenderedSynth {
    override val deviceName = "Gervill"

    override fun open(sampleRate: Int): RenderedOutputPort = GervillPort(sampleRate)
}

private class GervillPort(sampleRate: Int) : RenderedOutputPort {
    private val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)

    // A new instance of its own: the "Gervill" MIDI device may be open already, playing by itself.
    private val synth = Class.forName("com.sun.media.sound.SoftSynthesizer").getConstructor().newInstance() as Synthesizer
    private val stream = Class.forName("com.sun.media.sound.AudioSynthesizer")
        .getMethod("openStream", AudioFormat::class.java, Map::class.java)
        .invoke(synth, format, null) as AudioInputStream
    private val receiver: Receiver = synth.receiver
    private val line: SourceDataLine = AudioSystem.getSourceDataLine(format).apply {
        open(format, LINE_BUFFER_FRAMES * 2)
        start()
    }

    // What was rendered; guarded by `this`.
    private val played = PlayedAudioBuffer()
    private val block = FloatArray(BLOCK_FRAMES)

    @Volatile private var running = true
    private val renderer = thread(name = "Gervill renderer", isDaemon = true) { render() }

    override fun send(message: MidiMessage) {
        receiver.send(ShortMessage(message.status, message.data1, message.data2), -1)
    }

    override fun playedAudio(frames: Int): FloatArray = synchronized(this) {
        played.read(frames, line.longFramePosition, LEAD_FRAMES, RESYNC_FRAMES)
    }

    private fun render() {
        val bytes = ByteArray(BLOCK_FRAMES * 2)
        try {
            while (running) {
                var filled = 0
                while (filled < bytes.size) {
                    val read = stream.read(bytes, filled, bytes.size - filled)
                    if (read < 0) return
                    filled += read
                }
                synchronized(this) {
                    for (i in 0 until BLOCK_FRAMES) {
                        val value = (bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)
                        block[i] = value / 32768f
                    }
                    played.append(block)
                }
                line.write(bytes, 0, bytes.size) // blocks while the line's buffer is full: paces the rendering
            }
        } catch (_: Exception) {
            // Closed while rendering.
        }
    }

    override fun close() {
        running = false
        runCatching { receiver.send(ShortMessage(ShortMessage.CONTROL_CHANGE, 0, 123, 0), -1) } // all notes off
        line.stop()
        line.flush()
        renderer.join(1_000)
        line.close()
        runCatching { stream.close() }
        synth.close()
    }

    private companion object {
        const val BLOCK_FRAMES = 512
        /** ~93 ms at 44.1 kHz: enough to play without dropouts, and not much later than Gervill on its own. */
        const val LINE_BUFFER_FRAMES = 4096
        const val LEAD_FRAMES = 1024
        const val RESYNC_FRAMES = 4096
    }
}
