package com.megamaced.nccollectives.ui.screen.page

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** U6: every toolbar action is reachable, at full size, on a narrow phone. */
@RunWith(AndroidJUnit4::class)
class MarkdownToolbarWidthTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theLastAction_isReachableAtFullWidthOnA320dpScreen() {
        compose.setContent {
            androidx.compose.foundation.layout.Box(Modifier.width(320.dp)) {
                MarkdownToolbar(onAction = {}, onInsertImage = {})
            }
        }

        compose
            .onNodeWithContentDescription("Inline code")
            .performScrollTo()
            .assertIsDisplayed()
            .assertWidthIsAtLeast(40.dp)
    }
}
