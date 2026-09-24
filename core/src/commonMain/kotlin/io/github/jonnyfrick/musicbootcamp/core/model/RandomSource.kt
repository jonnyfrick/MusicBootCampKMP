package io.github.jonnyfrick.musicbootcamp.core.model

import kotlin.random.Random

/**
 * The random numbers the practice algorithms consume, mirroring the two
 * `java.util.Random` calls the Java version used. Injected so tests can replay
 * the exact numbers recorded from the Java implementation.
 */
interface RandomSource {
    /** Any Int, negative values included (like `java.util.Random.nextInt()`). */
    fun nextInt(): Int

    /** A value in `0 until bound` (like `java.util.Random.nextInt(bound)`). */
    fun nextInt(bound: Int): Int
}

class KotlinRandomSource(private val random: Random = Random.Default) : RandomSource {
    override fun nextInt(): Int = random.nextInt()
    override fun nextInt(bound: Int): Int = random.nextInt(bound)
}
