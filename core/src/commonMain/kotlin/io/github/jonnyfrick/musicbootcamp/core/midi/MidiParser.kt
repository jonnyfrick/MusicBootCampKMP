package io.github.jonnyfrick.musicbootcamp.core.midi

/**
 * Turns a raw MIDI byte stream (as Android's MIDI API delivers it, in chunks of any length) into
 * [MidiMessage]s: channel messages with running status, possibly split across chunks. System
 * exclusive and system common messages are skipped; real-time bytes (clock, active sensing) may
 * appear anywhere and are ignored.
 */
class MidiParser {
    private var status = 0
    private val data = IntArray(2)
    private var count = 0
    private var inSysex = false

    fun parse(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<MidiMessage> = buildList {
        for (i in offset until offset + length) {
            val byte = bytes[i].toInt() and 0xFF
            when {
                byte >= 0xF8 -> Unit // real-time: does not interrupt anything
                byte == 0xF0 -> {
                    inSysex = true
                    status = 0
                }
                byte == 0xF7 -> inSysex = false
                byte >= 0xF1 -> { // system common: cancels running status
                    inSysex = false
                    status = 0
                }
                byte >= 0x80 -> {
                    inSysex = false
                    status = byte
                    count = 0
                }
                inSysex || status == 0 -> Unit
                else -> {
                    data[count++] = byte
                    if (count == dataBytes(status)) {
                        add(MidiMessage(status, data[0], if (count == 2) data[1] else 0))
                        count = 0 // running status: the next data bytes reuse it
                    }
                }
            }
        }
    }

    /** Bytes of the MIDI stream for [message]. */
    fun encode(message: MidiMessage): ByteArray =
        if (dataBytes(message.status) == 1) byteArrayOf(message.status.toByte(), message.data1.toByte())
        else byteArrayOf(message.status.toByte(), message.data1.toByte(), message.data2.toByte())

    private fun dataBytes(status: Int) = when (status and 0xF0) {
        0xC0, 0xD0 -> 1 // program change, channel pressure
        else -> 2
    }
}
