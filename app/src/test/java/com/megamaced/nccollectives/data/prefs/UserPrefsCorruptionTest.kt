package com.megamaced.nccollectives.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A preferences file that doesn't parse — a write cut short by a crash or a
 * full disk — made every read throw `CorruptionException`. The first read
 * is the theme, at launch, so the app crashed on every start until the user
 * cleared its data and lost the accounts with it.
 */
class UserPrefsCorruptionTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun anUnreadablePreferencesFile_startsOverFromDefaults() =
        runTest(UnconfinedTestDispatcher()) {
            val file = folder.newFile("user_prefs.preferences_pb").apply { writeBytes(TRUNCATED_ENTRY) }
            val store = PreferenceDataStoreFactory.create(
                corruptionHandler = userPrefsCorruptionHandler,
                scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
                produceFile = { file },
            )

            val prefs = store.data.first()

            assertNull(prefs[stringPreferencesKey("theme_mode")])
        }

    private companion object {
        /** A map entry announcing 127 bytes and stopping after one: a write cut short. */
        val TRUNCATED_ENTRY = byteArrayOf(0x0a, 0x7f, 0x01)
    }
}
