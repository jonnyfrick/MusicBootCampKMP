package io.github.jonnyfrick.musicbootcamp.core

import io.github.jonnyfrick.musicbootcamp.core.learning.LearnedSequences
import io.github.jonnyfrick.musicbootcamp.core.legacy.LegacyImport
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.midi.NoteNames
import io.github.jonnyfrick.musicbootcamp.core.midi.Tuning
import io.github.jonnyfrick.musicbootcamp.core.model.KotlinRandomSource
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeMode
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeSettings
import io.github.jonnyfrick.musicbootcamp.core.model.SequenceElement.Chord
import io.github.jonnyfrick.musicbootcamp.core.model.SequenceElement.Note
import io.github.jonnyfrick.musicbootcamp.core.model.SettingsRules
import io.github.jonnyfrick.musicbootcamp.core.persistence.InMemoryDocumentStore
import io.github.jonnyfrick.musicbootcamp.core.persistence.Setup
import io.github.jonnyfrick.musicbootcamp.core.persistence.SetupRepository
import io.github.jonnyfrick.musicbootcamp.core.practice.PracticeSession
import io.github.jonnyfrick.musicbootcamp.core.practice.SingleNoteCorrector
import io.github.jonnyfrick.musicbootcamp.core.practice.TwoVoicesCorrector
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Platform-independent checks; the exact Java equivalence is covered by the JVM golden master tests. */
class CoreTest {

    @Test
    fun singleNoteCorrectorCountsOnlyTheFirstKeyPress() {
        val corrector = SingleNoteCorrector(memorySize = 3)
        corrector.addGiven(60)
        corrector.resetRecorded()
        corrector.addRecorded(60)
        corrector.addRecorded(61)
        assertTrue(corrector.correct())

        listOf(62, 64, 65).forEach { given ->
            corrector.addGiven(given)
            corrector.resetRecorded()
            corrector.addRecorded(given + 1)
            assertFalse(corrector.correct())
        }
        // Only the last three given notes are remembered, oldest first.
        assertEquals(listOf(Note(62), Note(64), Note(65)), corrector.predecessors())
    }

    @Test
    fun twoVoiceCorrectorAcceptsUnisonAndKeepsNewestFirst() {
        val corrector = TwoVoicesCorrector(memorySize = 5)
        assertTrue(corrector.correct(), "nothing given yet")

        corrector.addGiven(60); corrector.addGiven(60)
        corrector.addRecorded(60)
        assertTrue(corrector.correct(), "a single key press counts for a unison")

        corrector.resetGiven(); corrector.addGiven(62); corrector.addGiven(67)
        corrector.addRecorded(67); corrector.addRecorded(62)
        assertTrue(corrector.correct(), "order of the key presses does not matter")

        corrector.resetGiven(); corrector.addGiven(64); corrector.addGiven(69)
        corrector.addRecorded(64); corrector.addRecorded(70)
        assertFalse(corrector.correct())

        assertEquals(listOf(Chord(listOf(64, 69)), Chord(listOf(62, 67)), Chord(listOf(60, 60))), corrector.predecessors())
    }

    @Test
    fun practisedSequencesMoveDownOnePriorityLevel() {
        val memory = LearnedSequences()
        memory.insert(listOf(Note(60), Note(62)), 0)
        assertEquals(listOf(1, 0, 0, 0, 0), memory.countsByPriority())

        // Draw until level 0 is picked (level 0 has the biggest weight).
        val random = KotlinRandomSource(Random(1))
        var taken = memory.takeAndDowngrade(60, random)
        while (taken == null) taken = memory.takeAndDowngrade(60, random)

        assertEquals(listOf(Note(60), Note(62)), taken)
        assertEquals(listOf(0, 1, 0, 0, 0), memory.countsByPriority())
    }

    @Test
    fun sessionPlaysTheStartNoteFirstAndStaysInRange() {
        val settings = PracticeSettings(mode = PracticeMode.MONOPHONIC, lowLimit = 48, highLimit = 72, startPosition = 60)
        val sent = mutableListOf<MidiMessage>()
        val session = PracticeSession(settings, LearnedSequences(), KotlinRandomSource(Random(7))) { sent += it }

        assertEquals(listOf(60), session.step().given)
        assertEquals(MidiMessage.noteOn(60, settings.midiOutVelocity), sent.last())
        repeat(500) {
            val note = session.step().given.single()
            assertTrue(note in 48..72, "note $note outside the range")
        }
    }

    /**
     * A sequence learned with a wider range (here up to 90) used to lead the exercise out
     * of the current range; the random steps then could not find back and drifted away.
     */
    @Test
    fun learnedSequencesOutsideTheRangeDoNotLeadTheExerciseAway() {
        for (seed in 1..20) {
            val settings = PracticeSettings(
                mode = PracticeMode.MONOPHONIC, lowLimit = 48, highLimit = 72, startPosition = 60, learnedProbability = 1.0,
            )
            val memory = LearnedSequences()
            memory.insert(listOf(Note(60), Note(70), Note(80), Note(90)), 0)
            memory.insert(listOf(Note(60), Note(64), Note(67)), 0)
            val session = PracticeSession(settings, memory, KotlinRandomSource(Random(seed))) { }

            val notes = List(300) { session.step().given.single() }
            assertTrue(notes.all { it in 48..72 }, "seed $seed left the range: ${notes.filter { it !in 48..72 }.take(10)}")
            assertTrue("[60, 70, 80, 90]" in memory.canonicalText(), "the unplayable sequence is kept for a wider range later")
        }
    }

    @Test
    fun randomStepsFindBackIntoTheRange() {
        // E.g. after the range was narrowed: the reference note lies above the new range.
        val settings = PracticeSettings(mode = PracticeMode.TWO_VOICES_PURE_RANDOM, lowLimit = 48, highLimit = 72, startPosition = 95)
        for (seed in 1..20) {
            val session = PracticeSession(settings, LearnedSequences(), KotlinRandomSource(Random(seed))) { }
            val chords = List(200) { session.step().given }
            val stray = chords.drop(50).firstOrNull { chord -> chord.any { it !in 48..72 } }
            assertNull(stray, "seed $seed: still outside the range after 50 steps")
        }
    }

    @Test
    fun rangeRulesMatchTheJavaDialog() {
        assertEquals(108, SettingsRules.checkHighLimit("120", lowLimit = 48, previous = 72).value)
        assertEquals(72, SettingsRules.checkHighLimit("60", lowLimit = 48, previous = 80).value)
        assertEquals(80, SettingsRules.checkHighLimit("abc", lowLimit = 48, previous = 80).value)
        assertEquals(12, SettingsRules.checkLowLimit("3", highLimit = 96, previous = 24).value)
        assertNull(SettingsRules.checkLowLimit("36", highLimit = 96, previous = 24).message)
        assertEquals(66, SettingsRules.startPosition(36, 96))
        assertEquals(10.0f, SettingsRules.checkBreathingTime("25", 3f).value)
    }

    @Test
    fun tuningAndNoteNames() {
        assertEquals(8192, Tuning.pitchBendValue(440.0))
        assertEquals(8514, Tuning.pitchBendValue(442.0))
        assertEquals("great C", NoteNames.displayName(36))
        assertEquals("A'", NoteNames.displayName(69))
    }

    @Test
    fun setupsSurviveAJsonRoundTrip() = runTest {
        val repository = SetupRepository(InMemoryDocumentStore())
        val setup = Setup("two voices", PracticeSettings(mode = PracticeMode.TWO_VOICES_PURE_RANDOM, learnedProbability = 0.8))
        setup.memory().insert(listOf(Chord(listOf(48, 65)), Chord(listOf(54, 64))), 0)
        setup.memory().insert(listOf(Chord(listOf(50, 60)), Note(52)), 3)
        setup.memory(PracticeMode.MONOPHONIC).insert(listOf(Note(60), Note(67)), 1)

        repository.save(setup)
        val loaded = repository.load("two voices")!!

        assertEquals(setup.settings, loaded.settings)
        assertEquals(setup.memory().canonicalText(), loaded.memory().canonicalText())
        assertEquals(
            setup.memory(PracticeMode.MONOPHONIC).canonicalText(),
            loaded.memory(PracticeMode.MONOPHONIC).canonicalText(),
        )
        assertEquals(listOf("two voices"), repository.setupNames())
    }

    @Test
    fun legacySettingsWithMissingEntriesFallBackToDefaults() {
        val xml = """
            <replay_parameters_>
              <mode_>monophonic</mode_>
              <breathing_time_>2.5</breathing_time_>
            </replay_parameters_>
        """.trimIndent()
        val legacy = LegacyImport.parseSettings(xml)
        assertEquals(PracticeMode.MONOPHONIC, legacy.settings.mode)
        assertEquals(2.5f, legacy.settings.breathingTime)
        assertEquals(PracticeSettings().sustain, legacy.settings.sustain)
        assertNull(legacy.referenceAHz)
        assertEquals("learned_sequences_default_settings.xml", legacy.learnedSequencesFileName)
        assertTrue(legacy.warnings.isNotEmpty())
    }
}
