package com.megamaced.nccollectives.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Replace page" overwrites whatever the server holds — including whatever
 * someone else wrote since the user started editing — and it sat one button
 * from "Discard", which already asked first. Now both ask.
 */
@RunWith(AndroidJUnit4::class)
class ConflictBannerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun replacingThePage_asksFirst() {
        var replaced = 0
        compose.setContent { ConflictBanner(draft = "my draft", onReplace = { replaced++ }, onDiscard = {}) }

        compose.onNodeWithText("Replace page").performClick()
        compose.waitForIdle()
        assertEquals("nothing replaced before the user confirms", 0, replaced)

        compose.onNodeWithText("Replace with my draft").performClick()
        compose.waitForIdle()
        assertEquals(1, replaced)
    }

    @Test
    fun backingOutOfReplace_replacesNothing() {
        var replaced = 0
        compose.setContent { ConflictBanner(draft = "my draft", onReplace = { replaced++ }, onDiscard = {}) }

        compose.onNodeWithText("Replace page").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        assertEquals(0, replaced)
    }
}
