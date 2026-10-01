@file:OptIn(ExperimentalForeignApi::class)

package io.github.jonnyfrick.musicbootcamp.ios

import io.github.jonnyfrick.musicbootcamp.core.audio.PianoSynth
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.platform.AudioInputBackend
import io.github.jonnyfrick.musicbootcamp.platform.AudioInputPort
import io.github.jonnyfrick.musicbootcamp.platform.PlayedAudioBuffer
import io.github.jonnyfrick.musicbootcamp.platform.RenderedOutputPort
import io.github.jonnyfrick.musicbootcamp.platform.RenderedSynth
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetoothA2DP
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.AVAudioSourceNode
import platform.AVFAudio.setActive
import platform.Foundation.NSLock
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The one `AVAudioEngine` the microphone and the piano share, running while either is in use.
 * With the microphone, the audio session records and plays in "measurement" mode, which switches
 * off the processing meant for calls; without it, the session only plays.
 */
internal class IosAudioEngine {
    val engine = AVAudioEngine()
    private val session = AVAudioSession.sharedInstance()
    private var users = 0
    private var recording = false

    val mayRecord: Boolean get() = session.recordPermission == AVAudioSessionRecordPermissionGranted

    /**
     * Sets the session up for playing, or for recording too, before the nodes concerned are
     * touched. While in use it only ever adds recording (the piano may already be playing).
     */
    fun configure(record: Boolean) {
        val wanted = record || (users > 0 && recording)
        if (users > 0 && wanted == recording) return
        if (engine.running) engine.stop() // a new category changes the route under a running engine; acquire() restarts it
        if (wanted) {
            session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                mode = AVAudioSessionModeMeasurement,
                options = AVAudioSessionCategoryOptionDefaultToSpeaker or AVAudioSessionCategoryOptionAllowBluetoothA2DP,
                error = null,
            )
        } else {
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
        }
        recording = wanted
        session.setActive(true, error = null)
    }

    fun acquire() {
        users++
        if (!engine.running) {
            engine.prepare()
            if (!engine.startAndReturnError(null)) {
                users--
                throw IllegalStateException("The audio engine could not be started.")
            }
        }
    }

    fun release() {
        if (--users > 0) return
        users = 0
        engine.stop()
        recording = false
        session.setActive(false, error = null)
    }

    suspend fun requestRecording(): Boolean = suspendCoroutine { continuation ->
        session.requestRecordPermission { granted -> continuation.resume(granted) }
    }
}

/** The microphone: a tap on the engine's input node. */
internal class IosAudioInput(private val audio: IosAudioEngine) : AudioInputBackend {
    override val unavailableReason: String? = null

    override val hasAccess: Boolean get() = audio.mayRecord

    override suspend fun requestAccess(): Boolean = hasAccess || audio.requestRecording()

    // iOS chooses the input (built-in, headset, USB); the app records what the system routes to it.
    override fun devices(): List<String> = emptyList()

    override fun open(name: String?): AudioInputPort {
        if (!hasAccess) throw IllegalStateException("No access to the microphone.")
        return IosAudioPort(audio)
    }
}

private class IosAudioPort(private val audio: IosAudioEngine) : AudioInputPort {
    private val received = Channel<FloatArray>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val input = run {
        audio.configure(record = true)
        audio.engine.inputNode
    }
    private val format = input.outputFormatForBus(0u)

    override val sampleRate: Int = format.sampleRate.toInt()

    init {
        input.installTapOnBus(0u, bufferSize = TAP_FRAMES, format = format) { buffer, _ ->
            val channel = buffer?.floatChannelData?.get(0)
            if (buffer != null && channel != null) {
                received.trySend(FloatArray(buffer.frameLength.toInt()) { channel[it] })
            }
        }
        audio.acquire()
    }

    override val blocks: Flow<FloatArray> = received.receiveAsFlow()

    override fun close() {
        input.removeTapOnBus(0u)
        audio.release()
        received.close()
    }

    private companion object {
        /** A wish only: iOS delivers blocks of its own size, often about 100 ms. */
        const val TAP_FRAMES = 1024u
    }
}

/** The app's own piano on the loudspeaker; rendered by the app, so its sound can be removed from the microphone signal. */
internal class IosPianoSynth(private val audio: IosAudioEngine) : RenderedSynth {
    override val deviceName = "MusicBootCamp Piano"

    // The hardware decides the sample rate; microphone and synthesizer share one engine.
    override fun open(sampleRate: Int): RenderedOutputPort = IosSynthPort(audio)
}

private class IosSynthPort(private val audio: IosAudioEngine) : RenderedOutputPort {
    private val lock = NSLock()
    private val played = PlayedAudioBuffer()
    private var block = FloatArray(4096)
    private var rendered = 0L
    private var lastFrames = 0

    private val format: AVAudioFormat
    private val synth: PianoSynth
    private val node: AVAudioSourceNode

    init {
        audio.configure(record = false)
        val sampleRate = audio.engine.outputNode.outputFormatForBus(0u).sampleRate.takeIf { it > 0 } ?: 48_000.0
        format = AVAudioFormat(standardFormatWithSampleRate = sampleRate, channels = 1u)
        synth = PianoSynth(sampleRate.toInt())
        // Called on the audio thread for every block the loudspeaker needs.
        node = AVAudioSourceNode(format = format) { _, _, frameCount, bufferList ->
            val frames = frameCount.toInt()
            val out = bufferList?.pointed?.mBuffers?.get(0)?.mData?.reinterpret<FloatVar>()
            lock.lock()
            if (block.size < frames) block = FloatArray(frames)
            synth.render(block, 0, frames)
            played.append(block, 0, frames)
            rendered += frames
            lastFrames = frames
            if (out != null) for (i in 0 until frames) out[i] = block[i]
            lock.unlock()
            0
        }
        audio.engine.attachNode(node)
        audio.engine.connect(node, audio.engine.mainMixerNode, format)
        audio.acquire()
    }

    override fun send(message: MidiMessage) {
        lock.lock()
        synth.send(message)
        lock.unlock()
    }

    // The block just rendered is on its way to the loudspeaker; the microphone hears it later.
    override fun playedAudio(frames: Int): FloatArray {
        lock.lock()
        val result = played.read(frames, rendered - lastFrames, LEAD_FRAMES, RESYNC_FRAMES)
        lock.unlock()
        return result
    }

    override fun close() {
        audio.engine.disconnectNodeOutput(node)
        audio.engine.detachNode(node)
        audio.release()
    }

    private companion object {
        const val LEAD_FRAMES = 1024
        const val RESYNC_FRAMES = 16_384
    }
}
