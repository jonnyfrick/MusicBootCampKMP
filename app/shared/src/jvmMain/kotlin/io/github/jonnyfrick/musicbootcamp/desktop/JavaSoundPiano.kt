package io.github.jonnyfrick.musicbootcamp.desktop

import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.platform.BUILT_IN_PIANO
import io.github.jonnyfrick.musicbootcamp.platform.Instruments
import io.github.jonnyfrick.musicbootcamp.platform.PlayedAudioBuffer
import io.github.jonnyfrick.musicbootcamp.platform.RenderedOutputPort
import io.github.jonnyfrick.musicbootcamp.platform.RenderedSynth
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

/**
 * The app's own piano ([Instruments.piano]: recorded samples) on the desktop's default audio
 * output, as a MIDI output next to Gervill. Rendered by the app, so its sound can be removed
 * from the microphone signal, and the same instrument as on phones and in the browser.
 */
class JavaSoundPiano : RenderedSynth {
    override val deviceName = BUILT_IN_PIANO

    override fun open(sampleRate: Int): RenderedOutputPort = JavaSoundPianoPort(sampleRate)
}

private class JavaSoundPianoPort(sampleRate: Int) : RenderedOutputPort {
    private val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
    private val synth = Instruments.piano(sampleRate)
    private val played = PlayedAudioBuffer() // guarded by `this`, like the synth
    private val line: SourceDataLine = AudioSystem.getSourceDataLine(format).apply {
        open(format, LINE_BUFFER_FRAMES * 2)
        start()
    }

    @Volatile private var running = true
    private val renderer = thread(name = "Piano renderer", isDaemon = true) { render() }

    override fun send(message: MidiMessage) = synchronized(this) { synth.send(message) }

    override fun playedAudio(frames: Int): FloatArray = synchronized(this) {
        played.read(frames, line.longFramePosition, LEAD_FRAMES, RESYNC_FRAMES)
    }

    override fun diagnostics(): Map<String, String> = synchronized(this) { mapOf("referenceResyncs" to played.resyncs.toString()) }

    private fun render() {
        val block = FloatArray(BLOCK_FRAMES)
        val bytes = ByteArray(BLOCK_FRAMES * 2)
        try {
            while (running) {
                synchronized(this) {
                    synth.render(block)
                    played.append(block)
                }
                for (i in block.indices) {
                    val value = (block[i] * 32767f).toInt()
                    bytes[2 * i] = value.toByte()
                    bytes[2 * i + 1] = (value shr 8).toByte()
                }
                line.write(bytes, 0, bytes.size) // blocks while the line's buffer is full: paces the rendering
            }
        } catch (_: Exception) {
            // Closed while rendering.
        }
    }

    override fun close() {
        running = false
        line.stop()
        line.flush()
        renderer.join(1_000)
        line.close()
    }

    private companion object {
        const val BLOCK_FRAMES = 512
        /** ~93 ms at 44.1 kHz, as for Gervill: enough to play without dropouts. */
        const val LINE_BUFFER_FRAMES = 4096
        const val LEAD_FRAMES = 1024
        const val RESYNC_FRAMES = 4096
    }
}
