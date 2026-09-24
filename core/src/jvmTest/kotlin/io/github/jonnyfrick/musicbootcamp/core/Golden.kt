package io.github.jonnyfrick.musicbootcamp.core

import io.github.jonnyfrick.musicbootcamp.core.model.Direction
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeMode
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeSettings
import io.github.jonnyfrick.musicbootcamp.core.model.RandomSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Access to the fixtures written by `GoldenMasterGenerator` in the Java project
 * (branch `golden_master_harness`), plus the XML files they were generated from.
 */
object Golden {
    fun text(name: String): String =
        Golden::class.java.getResource("/golden/$name")?.readText()
            ?: error("Missing golden fixture $name")

    fun textOrNull(name: String): String? = Golden::class.java.getResource("/golden/$name")?.readText()

    fun json(name: String): JsonElement = Json.parseToJsonElement(text(name))

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun ints(element: JsonElement): List<Int> = element.jsonArray.map { it.jsonPrimitive.int }

    fun practiceSettings(parameters: JsonObject) = PracticeSettings(
        mode = PracticeMode.fromLegacyId(parameters.string("mode"))!!,
        direction = Direction.fromLegacyId(parameters.string("direction"))!!,
        breathingTime = parameters.getValue("breathingTime").jsonPrimitive.float,
        sustain = parameters.int("sustain"),
        midiOutVelocity = parameters.int("midiOutVelocity"),
        lowLimit = parameters.int("lowLimit"),
        highLimit = parameters.int("highLimit"),
        startPosition = parameters.int("startPosition"),
        intervalPriorities = ints(parameters.getValue("intervalPriorities")),
        learnedProbability = parameters.getValue("learnedProb").jsonPrimitive.double,
        memorySize = parameters.int("memorySize"),
        learnNewSequences = parameters.getValue("learnNewSequences").jsonPrimitive.boolean,
        transpositionsProbability = parameters.getValue("transpositionsProb").jsonPrimitive.double,
    )

    fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    /** Asserts two canonical memory dumps match, reporting the first differing line. */
    fun assertSameLines(expected: String, actual: String, what: String) {
        if (expected == actual) return
        val e = expected.lines()
        val a = actual.lines()
        val index = e.indices.firstOrNull { it >= a.size || e[it] != a[it] } ?: e.size
        fail("$what differs at line ${index + 1} (expected ${e.size} lines, got ${a.size}):\n" +
            "expected: ${e.getOrNull(index)}\nactual:   ${a.getOrNull(index)}")
    }
}

/** Hands out exactly the random numbers the Java run consumed, checking each call matches. */
class ReplayRandomSource : RandomSource {
    private val queue = ArrayDeque<Pair<Int, Int>>()

    fun enqueue(draws: JsonArray) {
        draws.forEach { draw -> Golden.ints(draw).let { queue.addLast(it[0] to it[1]) } }
    }

    override fun nextInt(): Int = take(0)
    override fun nextInt(bound: Int): Int = take(bound)

    private fun take(bound: Int): Int {
        val (expectedBound, value) = queue.removeFirstOrNull()
            ?: fail("Kotlin drew a random number (bound $bound) that Java never drew")
        assertEquals(expectedBound, bound, "Random draw with a different bound than Java (0 = unbounded)")
        return value
    }

    fun assertAllConsumed(context: String) {
        assertEquals(0, queue.size, "$context: Java drew ${queue.size} more random number(s)")
    }
}
