package io.github.jonnyfrick.musicbootcamp.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int

/**
 * One step of a practised sequence: either a single MIDI note (monophonic mode)
 * or the notes sounding together (two-voice mode).
 *
 * Serialized compactly as `60` or `[60, 67]`.
 */
@Serializable(with = SequenceElementSerializer::class)
sealed interface SequenceElement {
    /** The pitch a sequence is filed under in the learned-sequences memory. */
    val firstPitch: Int

    /** The pitches this element makes sound, in voice order. */
    val pitches: List<Int>

    /** Same text as `java.util.ArrayList.toString()` produced in the Java version. */
    fun legacyString(): String

    data class Note(val pitch: Int) : SequenceElement {
        override val firstPitch: Int get() = pitch
        override val pitches: List<Int> get() = listOf(pitch)
        override fun legacyString(): String = pitch.toString()
    }

    data class Chord(override val pitches: List<Int>) : SequenceElement {
        init {
            require(pitches.isNotEmpty()) { "A chord needs at least one pitch" }
        }

        override val firstPitch: Int get() = pitches.first()
        override fun legacyString(): String = pitches.joinToString(", ", "[", "]")
    }
}

/** A practised sequence, oldest element first as it was stored. */
typealias LearnedSequence = List<SequenceElement>

fun LearnedSequence.legacyString(): String = joinToString(", ", "[", "]") { it.legacyString() }

internal object SequenceElementSerializer : KSerializer<SequenceElement> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SequenceElement")

    override fun serialize(encoder: Encoder, value: SequenceElement) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("SequenceElement is JSON-only")
        json.encodeJsonElement(
            when (value) {
                is SequenceElement.Note -> JsonPrimitive(value.pitch)
                is SequenceElement.Chord -> JsonArray(value.pitches.map { JsonPrimitive(it) })
            },
        )
    }

    override fun deserialize(decoder: Decoder): SequenceElement {
        val json = decoder as? JsonDecoder ?: throw SerializationException("SequenceElement is JSON-only")
        return when (val element = json.decodeJsonElement()) {
            is JsonPrimitive -> SequenceElement.Note(element.int)
            is JsonArray -> SequenceElement.Chord(element.map { (it as JsonPrimitive).int })
            else -> throw SerializationException("Expected a pitch or a list of pitches, got $element")
        }
    }
}
