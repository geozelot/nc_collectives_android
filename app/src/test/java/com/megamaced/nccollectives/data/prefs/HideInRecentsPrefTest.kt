package com.megamaced.nccollectives.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * S9: hiding content from Recents is a choice about this device. Neither an
 * account switch nor a sign-out may quietly undo it: the screen shown right
 * after a sign-out is the login screen, and the next one is whatever the
 * next account opens.
 */
class HideInRecentsPrefTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun itIsOffUntilTheUserTurnsItOn() =
        runTest(UnconfinedTestDispatcher()) {
            val prefs = prefs()
            assertFalse(prefs.flow.first().hideInRecents)

            prefs.setHideInRecents(true)

            assertTrue(prefs.flow.first().hideInRecents)
        }

    @Test
    fun itSurvivesAnAccountSwitchAndASignOut() =
        runTest(UnconfinedTestDispatcher()) {
            val prefs = prefs()
            prefs.setHideInRecents(true)

            prefs.clearAccountScoped()
            assertTrue(prefs.flow.first().hideInRecents)

            prefs.clearAll()
            assertTrue(prefs.flow.first().hideInRecents)
        }

    private fun kotlinx.coroutines.test.TestScope.prefs() =
        UserPreferences(
            PreferenceDataStoreFactory.create(
                scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
                produceFile = { folder.newFile("user_prefs.preferences_pb").also { it.delete() } },
            ),
        )
}
