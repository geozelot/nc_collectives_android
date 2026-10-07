package com.megamaced.nccollectives.ui.screen.page

import androidx.lifecycle.SavedStateHandle
import com.megamaced.nccollectives.data.ServerVersionTracker
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.auth.TokenStore
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.repository.DirectEditingRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * H6: the collaborative editor survives an Activity recreation.
 *
 * The ViewModel is nav-scoped and outlives the activity, so a recreation
 * leaves it holding the `directediting` URL the first WebView already spent.
 * The rebuilt WebView used to load that URL into an error page.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageEditWebViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val directEditing = mockk<DirectEditingRepository>()
    private val pages = mockk<PageRepository>()
    private val versions = mockk<ServerVersionTracker>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { pages.getPage(PAGE) } returns page()
        coEvery { versions.serverVersionChanged() } returns false
        coEvery { directEditing.openSession(any()) } returnsMany listOf(
            ApiResult.Success("https://cloud.example/direct/first"),
            ApiResult.Success("https://cloud.example/direct/second"),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aSessionUrl_isHandedOutOnce() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            val url = (viewModel.uiState.value as PageEditWebUiState.Loaded).url

            assertTrue("the first WebView gets it", viewModel.claimUrl(url))
            assertFalse("a rebuilt one doesn't: the token is spent", viewModel.claimUrl(url))
        }

    @Test
    fun aSpentUrl_opensAFreshSession() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.claimUrl("https://cloud.example/direct/first")

            viewModel.onUrlSpent()
            advanceUntilIdle()

            val state = viewModel.uiState.value as PageEditWebUiState.Loaded
            assertEquals("https://cloud.example/direct/second", state.url)
            assertTrue("and that one is the new WebView's", viewModel.claimUrl(state.url))
            coVerify(exactly = 2) { directEditing.openSession(any()) }
        }

    private fun viewModel() =
        PageEditWebViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Destination.PageEditWeb.ARG_PAGE_ID to PAGE)),
            tokenStore = mockk<TokenStore>(relaxed = true),
            directEditingRepository = directEditing,
            pageRepository = pages,
            serverVersionTracker = versions,
        )

    private fun page() =
        Page(
            id = PAGE,
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
            bodyMd = "body",
            draftBodyMd = null,
        )

    private companion object {
        const val PAGE = 41L
    }
}
