package io.github.jonnyfrick.musicbootcamp.android

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import io.github.jonnyfrick.musicbootcamp.platform.BUILT_IN_PIANO
import io.github.jonnyfrick.musicbootcamp.platform.Instruments
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.platform.PlayedAudioBuffer
import io.github.jonnyfrick.musicbootcamp.platform.RenderedOutputPort
import io.github.jonnyfrick.musicbootcamp.platform.RenderedSynth
import kotlin.concurrent.thread

/**
 * The app's own piano ([Instruments.piano]) on the loudspeaker: Android has no real-time synthesizer to send
 * MIDI to. Rendered by the app, so its sound can be removed from the microphone signal.
 */
class AndroidPianoSynth : RenderedSynth {
    override val deviceName = BUILT_IN_PIANO

    override fun open(sampleRate: Int): RenderedOutputPort = AndroidSynthPort(sampleRate)
}

private class AndroidSynthPort(sampleRate: Int) : RenderedOutputPort {
    private val synth = Instruments.piano(sampleRate)
    private val played = PlayedAudioBuffer()
    private val lock = Any()

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(
            maxOf(
                AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT),
                BUFFER_FRAMES * 4,
            ),
        )
        // No low-latency mode: its buffer of a few milliseconds runs dry (audible as tearing)
        // whenever the renderer has to wait for the processor, e.g. while a chord is analysed.
        // A fifth of a second later for every note does not matter: the exercise's clock is the
        // app's, and the delay to the microphone is measured anyway.
        .build()
        .apply { play() }

    @Volatile private var running = true
    private val renderer = thread(name = "Piano renderer", isDaemon = true) { render() }

    override fun send(message: MidiMessage) = synchronized(lock) { synth.send(message) }

    override fun playedAudio(frames: Int): FloatArray = synchronized(lock) {
        // The play head counts frames since play() as an unsigned 32-bit number.
        played.read(frames, track.playbackHeadPosition.toLong() and 0xFFFFFFFFL, LEAD_FRAMES, RESYNC_FRAMES)
    }

    override fun diagnostics(): Map<String, String> = synchronized(lock) {
        mapOf("outputUnderruns" to track.underrunCount.toString(), "referenceResyncs" to played.resyncs.toString())
    }

    private fun render() {
        // Ahead of everything but the system's own audio, so the analysis cannot starve the sound.
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = FloatArray(BLOCK_FRAMES)
        try {
            while (running) {
                synchronized(lock) {
                    synth.render(block)
                    played.append(block)
                }
                // Blocks while the track's buffer is full: paces the rendering.
                track.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING)
            }
        } catch (_: IllegalStateException) {
            // Released while rendering.
        }
    }

    override fun close() {
        running = false
        runCatching { track.pause() }
        runCatching { track.flush() }
        renderer.join(1_000)
        track.release()
    }

    private companion object {
        const val BLOCK_FRAMES = 256
        /** ~190 ms at 44.1 kHz: room for the renderer to be late without a dropout. */
        const val BUFFER_FRAMES = 8192
        const val LEAD_FRAMES = 1024
        const val RESYNC_FRAMES = 4096
    }
}
