package com.megamaced.nccollectives.ui.screen.trash

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A screen reader moving through a list heard "Restore, Delete forever,
 * Restore, Delete forever" — every row's buttons said the same thing, and
 * the one that destroys a page for good didn't say which page.
 */
@RunWith(AndroidJUnit4::class)
class TrashRowLabelsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun eachRowsActionsNameTheirPage() {
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                TrashRow(emoji = "📄", title = "Plans", subtitle = null, onRestore = {}, onPurge = {})
                TrashRow(emoji = "📄", title = "Notes", subtitle = null, onRestore = {}, onPurge = {})
            }
        }

        compose.onNodeWithContentDescription("Restore Plans").assertExists()
        compose.onNodeWithContentDescription("Delete Notes forever").assertExists()
        compose.onAllNodesWithContentDescription("Restore").assertCountEquals(0)
    }
}
