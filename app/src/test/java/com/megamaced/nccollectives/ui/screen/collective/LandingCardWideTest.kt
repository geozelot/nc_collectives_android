package com.megamaced.nccollectives.ui.screen.collective

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.domain.model.Page
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * U7: the landing page is reachable on a wide screen. The tree leaves it out
 * because the card stands for it, and the card was compact-width only.
 */
@RunWith(AndroidJUnit4::class)
class LandingCardWideTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aTabletWideTree_stillOffersTheLandingPage() {
        compose.setContent {
            Box(Modifier.width(840.dp)) {
                PageTreeList(
                    nodes = emptyList(),
                    expanded = emptySet(),
                    landingPage = landing(),
                    collectiveName = "Field Guide",
                    collectiveEmoji = null,
                    recentPages = emptyList(),
                    membersStrip = MembersStripState(),
                    onToggle = {},
                    onPageClick = {},
                    onToggleFavorite = { _, _ -> },
                    onAddSubpage = {},
                    onReorder = { _, _ -> },
                    onOpenMembers = {},
                    onRetryMembers = {},
                )
            }
        }

        compose.onNodeWithText("Field Guide", substring = true).assertHasClickAction()
    }

    private fun landing() =
        Page(
            id = 1,
            collectiveId = 7,
            parentId = 0,
            title = "Readme",
            emoji = null,
            tags = emptyList(),
            subpageOrder = emptyList(),
            isFullWidth = false,
            trashed = false,
            serverTimestamp = 0,
            size = 0,
            fileName = "Readme.md",
            filePath = "",
            collectivePath = ".Collectives/Field Guide",
            linkedPageIds = emptyList(),
            lastUserDisplayName = "",
            bodyMd = "Welcome",
            draftBodyMd = null,
        )
}
