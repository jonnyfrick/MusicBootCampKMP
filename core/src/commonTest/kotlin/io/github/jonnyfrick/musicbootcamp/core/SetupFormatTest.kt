package io.github.jonnyfrick.musicbootcamp.core

import io.github.jonnyfrick.musicbootcamp.core.model.PracticeSettings
import io.github.jonnyfrick.musicbootcamp.core.persistence.InMemoryDocumentStore
import io.github.jonnyfrick.musicbootcamp.core.persistence.Setup
import io.github.jonnyfrick.musicbootcamp.core.persistence.SetupRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Setup files of older formats are brought up to date when they are loaded. */
class SetupFormatTest {
    @Test
    fun aFormat1SetupTakesOverTheLateAnswerToleranceOfThePreferences() = runTest {
        val store = InMemoryDocumentStore()
        // Format 1: the tolerance was one value in the preferences, the settings had none.
        store.write("preferences.json", """{"version":1,"lateAnswerToleranceMillis":400}""")
        store.write("setup-Old.json", """{"version":1,"name":"Old","settings":{"breathingTime":0.8}}""")
        val repository = SetupRepository(store)

        val setup = repository.load("Old")!!
        assertEquals(400, setup.settings.lateAnswerToleranceMillis)
        assertEquals(0.8f, setup.settings.breathingTime)

        repository.save(setup)
        assertTrue(""""version":2""" in store.read("setup-Old.json")!!, "saved in the current format")
        // From then on the setup's own value counts, whatever the preferences still say.
        setup.settings = setup.settings.copy(lateAnswerToleranceMillis = 250)
        repository.save(setup)
        assertEquals(250, repository.load("Old")!!.settings.lateAnswerToleranceMillis)
    }

    @Test
    fun aNewSetupStartsWithTheDefaultTolerance() = runTest {
        val repository = SetupRepository(InMemoryDocumentStore())
        repository.save(Setup("New"))
        assertEquals(PracticeSettings.DEFAULT_LATE_ANSWER_TOLERANCE_MILLIS, repository.load("New")!!.settings.lateAnswerToleranceMillis)
    }
}
