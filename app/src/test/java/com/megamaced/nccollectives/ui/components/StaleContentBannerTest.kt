package com.megamaced.nccollectives.ui.components

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A refresh that failed over a list already on screen said nothing: the
 * error arm only covers an empty list, and the screens never showed the
 * message anywhere else. Offline, the user couldn't tell the list in front
 * of them was stale — or that pulling to refresh had failed.
 */
@RunWith(AndroidJUnit4::class)
class StaleContentBannerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aFailedRefreshOverContent_saysSoAboveIt() {
        var retried = 0
        compose.setContent {
            ListStateSwitch(
                isLoading = false,
                error = "Couldn't reach the server. Check your connection.",
                isEmpty = false,
                onRetry = { retried++ },
                empty = {},
            ) { Text("Wiki") }
        }

        compose.onNodeWithText("Wiki").assertIsDisplayed()
        compose.onNodeWithText("Couldn't reach the server", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun withoutAnError_thereIsNoBanner() {
        compose.setContent {
            ListStateSwitch(isLoading = false, error = null, isEmpty = false, onRetry = {}, empty = {}) { Text("Wiki") }
        }

        compose.onNodeWithText("Retry").assertDoesNotExist()
    }
}
