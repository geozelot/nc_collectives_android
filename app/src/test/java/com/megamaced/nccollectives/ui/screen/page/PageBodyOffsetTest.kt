package com.megamaced.nccollectives.ui.screen.page

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.ui.components.MarkdownOutline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * U2: the body's offset that the page index scrolls by is a content-space
 * figure, and stays put however far the page is scrolled.
 *
 * The "viewport" coordinates it was measured against were taken after
 * `verticalScroll`, so they were the scrolled *content's*. The difference
 * was already in content space, and adding `scrollState.value` on top made
 * every jump overshoot by however far the page was scrolled when the index
 * was opened.
 */
@RunWith(AndroidJUnit4::class)
class PageBodyOffsetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theBodyOffset_doesNotMoveWithTheScroll() {
        val scroll = ScrollState(0)
        val reported = mutableListOf<Int>()
        compose.setContent {
            PageViewContent(
                page = page(),
                body = (1..200).joinToString("\n\n") { "Paragraph $it" },
                imageBaseUrl = null,
                backlinks = emptyList(),
                remoteAttachmentCount = 0,
                scrollState = scroll,
                outline = MarkdownOutline(),
                onBodyPositioned = { reported += it },
                onReplaceWithDraft = {},
                onDiscardDraft = {},
                onOpenPage = {},
                onWikiLink = {},
                onAttachmentLink = {},
                onBrowseTag = {},
            )
        }
        compose.waitForIdle()
        val atTop = reported.last()
        assertTrue("the body must be scrollable for this to mean anything", scroll.maxValue > 500)

        compose.runOnIdle { runBlocking { scroll.scrollTo(500) } }
        compose.waitForIdle()

        assertEquals(atTop, reported.last())
    }

    private fun page() =
        Page(
            id = 41,
            collectiveId = 7,
            parentId = 1,
            title = "Page",
            emoji = null,
            tags = emptyList(),
            subpageOrder = emptyList(),
            isFullWidth = false,
            trashed = false,
            serverTimestamp = 0,
            size = 0,
            fileName = "Page.md",
            filePath = "",
            collectivePath = ".Collectives/Wiki",
            linkedPageIds = emptyList(),
            lastUserDisplayName = "",
            bodyMd = null,
            draftBodyMd = null,
        )
}
