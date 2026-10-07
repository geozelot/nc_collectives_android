package com.megamaced.nccollectives.ui.screen.page

import androidx.lifecycle.SavedStateHandle
import com.megamaced.nccollectives.data.ServerVersionTracker
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.auth.StoredCredentials
import com.megamaced.nccollectives.data.auth.TokenStore
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.repository.DirectEditingRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * U8: leaving the collaborative editor.
 *
 * Back while the session was still loading, or after it failed, went
 * through the full save-and-close: a collective refresh and a body fetch,
 * with Back swallowed until both returned. Nothing could have been edited,
 * and on a dead network that was two connect-and-read timeouts. And the
 * close that does have to pull the page back now has a budget.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageEditWebCloseTest {
    private val dispatcher = StandardTestDispatcher()
    private val page = mockk<Page>(relaxed = true) { every { collectiveId } returns 7L }
    private val pages = mockk<PageRepository>(relaxed = true)
    private val directEditing = mockk<DirectEditingRepository>()
    private val versions = mockk<ServerVersionTracker>()
    private val tokenStore = mockk<TokenStore>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { pages.getPage(PAGE_ID) } returns page
        coEvery { versions.serverVersionChanged() } returns false
        every { tokenStore.getCredentials() } returns
            StoredCredentials(host = "https://cloud.example.com", loginName = "alice", appPassword = "x")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun leavingWhileTheSessionLoads_closesAtOnceAndStaysClosed() =
        runTest(dispatcher) {
            val session = CompletableDeferred<ApiResult<String>>()
            coEvery { directEditing.openSession(page) } coAnswers { session.await() }
            val viewModel = viewModel()
            runCurrent()

            viewModel.leave()

            assertEquals(PageEditWebUiState.Closed, viewModel.uiState.value)
            session.complete(ApiResult.Success("https://cloud.example.com/s/1"))
            advanceUntilIdle()
            assertEquals("a late session must not reopen it", PageEditWebUiState.Closed, viewModel.uiState.value)
            coVerify(exactly = 0) { pages.refresh(any()) }
            coVerify(exactly = 0) { pages.fetchBody(any()) }
        }

    @Test
    fun leavingAfterTheSessionFailed_closesAtOnce() =
        runTest(dispatcher) {
            coEvery { directEditing.openSession(page) } returns ApiResult.HttpError(503, "Service Unavailable")
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.leave()

            assertEquals(PageEditWebUiState.Closed, viewModel.uiState.value)
            coVerify(exactly = 0) { pages.fetchBody(any()) }
        }

    @Test
    fun leavingWhileTextRuns_pullsThePageBack() =
        runTest(dispatcher) {
            coEvery { directEditing.openSession(page) } returns ApiResult.Success("https://cloud.example.com/s/1")
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.leave()
            advanceUntilIdle()

            assertEquals(PageEditWebUiState.Closed, viewModel.uiState.value)
            coVerify { pages.refresh(7L) }
            coVerify { pages.fetchBody(PAGE_ID) }
        }

    @Test
    fun closingOnADeadNetwork_givesUpAfterTheBudget() =
        runTest(dispatcher) {
            coEvery { directEditing.openSession(page) } returns ApiResult.Success("https://cloud.example.com/s/1")
            coEvery { pages.refresh(any()) } coAnswers { awaitCancellation() }
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.leave()
            advanceTimeBy(CLOSE_SYNC_BUDGET_MS - 1)
            assertEquals(PageEditWebUiState.Closing, viewModel.uiState.value)

            advanceTimeBy(2)
            assertEquals(PageEditWebUiState.Closed, viewModel.uiState.value)
        }

    private fun viewModel() =
        PageEditWebViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Destination.PageEditWeb.ARG_PAGE_ID to PAGE_ID)),
            tokenStore = tokenStore,
            directEditingRepository = directEditing,
            pageRepository = pages,
            serverVersionTracker = versions,
        )

    private companion object {
        const val PAGE_ID = 17L
    }
}
