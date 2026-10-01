package io.github.jonnyfrick.musicbootcamp.android

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import io.github.jonnyfrick.musicbootcamp.core.audio.PianoSynth
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.platform.PlayedAudioBuffer
import io.github.jonnyfrick.musicbootcamp.platform.RenderedOutputPort
import io.github.jonnyfrick.musicbootcamp.platform.RenderedSynth
import kotlin.concurrent.thread

/**
 * The app's own [PianoSynth] on the loudspeaker: Android has no real-time synthesizer to send
 * MIDI to. Rendered by the app, so its sound can be removed from the microphone signal.
 */
class AndroidPianoSynth : RenderedSynth {
    override val deviceName = NAME

    override fun open(sampleRate: Int): RenderedOutputPort = AndroidSynthPort(sampleRate)

    companion object {
        /** Stored as the MIDI output in the preferences, so it must not change. */
        const val NAME = "MusicBootCamp Piano"
    }
}

private class AndroidSynthPort(sampleRate: Int) : RenderedOutputPort {
    private val synth = PianoSynth(sampleRate)
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
        .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) }
        .build()
        .apply { play() }

    @Volatile private var running = true
    private val renderer = thread(name = "Piano renderer", isDaemon = true, priority = Thread.MAX_PRIORITY) { render() }

    override fun send(message: MidiMessage) = synchronized(lock) { synth.send(message) }

    override fun playedAudio(frames: Int): FloatArray = synchronized(lock) {
        // The play head counts frames since play() as an unsigned 32-bit number.
        played.read(frames, track.playbackHeadPosition.toLong() and 0xFFFFFFFFL, LEAD_FRAMES, RESYNC_FRAMES)
    }

    private fun render() {
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
        const val BUFFER_FRAMES = 2048
        const val LEAD_FRAMES = 1024
        const val RESYNC_FRAMES = 4096
    }
}
