package com.megamaced.nccollectives.ui.screen.page

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.domain.model.PageTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A collective with more tags than fit pushed "Create tag" and Done off the
 * bottom of the sheet, with nothing to scroll.
 */
@RunWith(AndroidJUnit4::class)
class TagPickerSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun manyTags_leaveDoneOnScreen() {
        compose.setContent {
            TagPickerSheet(
                available = (1L..60L).map { PageTag(id = it, name = "tag $it") },
                selectedTagNames = emptySet(),
                isLoading = false,
                onToggle = { _, _ -> },
                onCreate = {},
                onBrowse = {},
                onDismiss = {},
            )
        }
        compose.waitForIdle()

        compose.onNodeWithText("Done").assertIsDisplayed()
    }
}
