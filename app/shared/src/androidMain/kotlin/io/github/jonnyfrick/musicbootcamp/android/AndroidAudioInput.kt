package io.github.jonnyfrick.musicbootcamp.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.jonnyfrick.musicbootcamp.core.pitch.AudioBlock
import io.github.jonnyfrick.musicbootcamp.platform.AudioInputBackend
import io.github.jonnyfrick.musicbootcamp.platform.AudioInputPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * The microphone via [AudioRecord]: 44.1 kHz (every Android device supports it), mono, float.
 *
 * Records the signal as unprocessed as the device allows: the "unprocessed" source where there is
 * one, else the one for speech recognition, which also leaves out the automatic gain control and
 * noise suppression of calls that would distort the piano.
 */
class AndroidAudioInput(
    private val context: Context,
    private val requestMicrophone: suspend () -> Boolean,
) : AudioInputBackend {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override val unavailableReason: String? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) null else "This device has no microphone."

    override val hasAccess: Boolean
        get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override suspend fun requestAccess(): Boolean = hasAccess || requestMicrophone()

    override fun devices(): List<String> = inputs().map { it.first }

    /** The usable inputs with a readable, unique name each. */
    private fun inputs(): List<Pair<String, AudioDeviceInfo>> {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in TYPES }
        return devices.map { device ->
            val kind = TYPES.getValue(device.type)
            val product = device.productName?.toString()?.takeIf { it.isNotBlank() && device.type != AudioDeviceInfo.TYPE_BUILTIN_MIC }
            val name = if (product != null) "$kind: $product" else kind
            name to device
        }.groupBy { it.first }.flatMap { (name, same) ->
            if (same.size == 1) same else same.mapIndexed { i, (_, device) -> "$name ${i + 1}" to device }
        }
    }

    @SuppressLint("MissingPermission") // checked by the controller through hasAccess before it opens
    override fun open(name: String?): AudioInputPort {
        if (!hasAccess) throw SecurityException("No permission to use the microphone.")
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .build()
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val record = AudioRecord.Builder()
            .setAudioSource(if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimum, BLOCK_FRAMES * 4 * 8))
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("The microphone could not be opened.")
        }
        name?.let { wanted -> inputs().firstOrNull { it.first == wanted }?.let { record.setPreferredDevice(it.second) } }
        return AndroidAudioPort(record)
    }

    private class AndroidAudioPort(private val record: AudioRecord) : AudioInputPort {
        override val sampleRate: Int = SAMPLE_RATE

        override val blocks: Flow<FloatArray> = read { it }

        override fun blocksWith(reference: (frames: Int) -> FloatArray): Flow<AudioBlock> =
            read { samples -> AudioBlock(samples, reference(samples.size)) }

        /** Reads blocks on an IO thread; [convert] runs right after each read, on that thread. */
        private fun <T> read(convert: (FloatArray) -> T): Flow<T> = flow {
            val buffer = FloatArray(BLOCK_FRAMES)
            record.startRecording()
            try {
                while (currentCoroutineContext().isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read <= 0) continue
                    emit(convert(buffer.copyOf(read)))
                }
            } finally {
                runCatching { record.stop() }
            }
        }.flowOn(Dispatchers.IO)

        override fun close() {
            runCatching { record.stop() }
            record.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val BLOCK_FRAMES = 512

        /** Inputs that can hear a piano, with how they are called in the list. */
        val TYPES = mapOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC to "Built-in microphone",
            AudioDeviceInfo.TYPE_WIRED_HEADSET to "Headset",
            AudioDeviceInfo.TYPE_USB_DEVICE to "USB",
            AudioDeviceInfo.TYPE_USB_HEADSET to "USB headset",
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO to "Bluetooth",
            AudioDeviceInfo.TYPE_LINE_ANALOG to "Line in",
            AudioDeviceInfo.TYPE_LINE_DIGITAL to "Digital in",
        )
    }
}
