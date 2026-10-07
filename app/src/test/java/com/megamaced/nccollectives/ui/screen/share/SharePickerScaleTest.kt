package com.megamaced.nccollectives.ui.screen.share

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.PageListItem
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.share.SharePayload
import com.megamaced.nccollectives.share.SharePayloadHolder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The share screen listed every page of the chosen collective as a chip in
 * one scrolling column, with the Create button below the last of them, and
 * held each page in full — its cached body included — to draw a title.
 * A collective of a few hundred pages meant scrolling past all of them to
 * share anything, and every body in memory while doing it.
 */
@RunWith(AndroidJUnit4::class)
class SharePickerScaleTest {
    @get:Rule
    val compose = createComposeRule()

    private val pages = mockk<PageRepository>(relaxed = true)
    private val collectives = mockk<CollectiveRepository>(relaxed = true)

    @Test
    fun aLargeCollective_leavesTheCreateButtonInReachAndLoadsNoBodies() {
        every { collectives.observeCollectives() } returns flowOf(listOf(collective()))
        coEvery { pages.refresh(7) } returns ApiResult.Success(Unit)
        every { pages.observePageList(7) } returns flowOf((1L..300L).map { listItem(it) })
        val holder = SharePayloadHolder().apply { publish(SharePayload(text = "a shared note")) }
        val viewModel = ShareCaptureViewModel(
            ApplicationProvider.getApplicationContext<Context>(),
            holder,
            pages,
            collectives,
            mockk<AttachmentRepository>(relaxed = true),
        )

        compose.setContent { ShareCaptureScreen(PaddingValues(), onDismiss = {}, viewModel = viewModel) }
        compose.waitForIdle()

        compose.onNodeWithText("Create page").assertIsDisplayed()
        verify(exactly = 0) { pages.observePages(any()) }
    }

    private fun listItem(id: Long) =
        PageListItem(
            id = id,
            collectiveId = 7,
            parentId = if (id == 1L) 0 else 1,
            title = "Page $id",
            emoji = null,
            tags = emptyList(),
            subpageOrder = emptyList(),
            trashed = false,
            serverTimestamp = 0,
            lastUserDisplayName = "",
            hasDraft = false,
        )

    private fun collective() =
        Collective(
            id = 7,
            name = "Wiki",
            slug = null,
            emoji = null,
            circleId = null,
            canEdit = true,
            canShare = true,
            level = 0,
            userShowMembers = false,
            isPageShare = false,
            trashed = false,
            favoritePageIds = emptySet(),
        )
}
