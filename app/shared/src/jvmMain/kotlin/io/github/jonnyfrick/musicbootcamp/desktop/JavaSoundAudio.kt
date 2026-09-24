package io.github.jonnyfrick.musicbootcamp.desktop

import io.github.jonnyfrick.musicbootcamp.platform.AudioInputBackend
import io.github.jonnyfrick.musicbootcamp.platform.AudioInputPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine

/**
 * Microphone input via `javax.sound.sampled`: 44.1 kHz, 16 bit, mono.
 *
 * On macOS the first recording triggers the microphone permission prompt for the app
 * that started the JVM (e.g. the terminal); if it was denied, the input stays silent.
 */
class JavaSoundAudioInput : AudioInputBackend {
    override val unavailableReason: String? = null

    private val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
    private val lineInfo = DataLine.Info(TargetDataLine::class.java, format)

    override fun devices(): List<String> = mixers().map { it.mixerInfo.name }

    override fun open(name: String?): AudioInputPort {
        val mixer = name?.let { wanted -> mixers().firstOrNull { it.mixerInfo.name == wanted } }
        val line = try {
            (mixer?.getLine(lineInfo) ?: AudioSystem.getLine(lineInfo)) as TargetDataLine
        } catch (e: IllegalArgumentException) {
            throw LineUnavailableException("No microphone input supports 44.1 kHz mono: ${e.message}")
        }
        line.open(format, BLOCK_FRAMES * 2 * 4)
        return JavaSoundAudioPort(line)
    }

    private fun mixers(): List<Mixer> = AudioSystem.getMixerInfo()
        .map { AudioSystem.getMixer(it) }
        .filter { runCatching { it.isLineSupported(lineInfo) }.getOrDefault(false) }

    private class JavaSoundAudioPort(private val line: TargetDataLine) : AudioInputPort {
        override val sampleRate: Int = SAMPLE_RATE

        override val blocks: Flow<FloatArray> = flow {
            val bytes = ByteArray(BLOCK_FRAMES * 2)
            line.flush()
            line.start()
            try {
                while (currentCoroutineContext().isActive && line.isOpen) {
                    val read = line.read(bytes, 0, bytes.size)
                    if (read <= 0) continue
                    val samples = FloatArray(read / 2) { i ->
                        val value = (bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)
                        value / 32768f
                    }
                    emit(samples)
                }
            } finally {
                line.stop()
            }
        }.flowOn(Dispatchers.IO)

        override fun close() {
            line.stop()
            line.close()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val BLOCK_FRAMES = 512
    }
}
