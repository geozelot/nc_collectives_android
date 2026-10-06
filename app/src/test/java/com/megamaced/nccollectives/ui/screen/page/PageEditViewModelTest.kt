package com.megamaced.nccollectives.ui.screen.page

import androidx.lifecycle.SavedStateHandle
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.SaveOutcome
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * B-94: the native editor must not offer to save a page whose body never
 * loaded.
 *
 * With no cached body and a failed fetch, the editor used to open on an
 * empty, editable field with Save enabled. The page row had no ETag, so the
 * save went out with no `If-Match`: a blind overwrite of the whole page, for
 * every collaborator, with whatever the user had typed into the blank field.
 * And because `hasUnsavedChanges` keys on a loaded body, Back threw that
 * typing away without asking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageEditViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val pages = mockk<PageRepository>()
    private val attachments = mockk<AttachmentRepository>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { pages.saveBody(any(), any()) } returns SaveOutcome.Saved
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aBodyThatNeverLoaded_offersNoEditorAndNoSave() =
        runTest(dispatcher) {
            coEvery { pages.getPage(PAGE) } returns page(bodyMd = null)
            coEvery { pages.refreshBodyIfChanged(PAGE) } returns ApiResult.NetworkError(IOException("offline"))

            val viewModel = viewModel()
            advanceUntilIdle()

            assertFalse("there is nothing to edit", viewModel.uiState.value.canEdit)
            assertNotNull("and the user is told why", viewModel.uiState.value.loadError)

            viewModel.save()
            advanceUntilIdle()
            coVerify(exactly = 0) { pages.saveBody(any(), any()) }
        }

    @Test
    fun retrying_loadsTheBodyAndOpensTheEditor() =
        runTest(dispatcher) {
            coEvery { pages.getPage(PAGE) } returnsMany listOf(page(bodyMd = null), page(bodyMd = null), page(bodyMd = "# fetched"))
            coEvery { pages.refreshBodyIfChanged(PAGE) } returnsMany listOf(
                ApiResult.NetworkError(IOException("offline")),
                ApiResult.Success(true),
            )

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.retryLoad()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.canEdit)
            assertEquals("# fetched", viewModel.draftBody.value)
        }

    @Test
    fun aCachedBody_staysEditableOffline() =
        runTest(dispatcher) {
            // The offline case the editor exists for: the save queues for
            // EditFlushWorker against the cached body's ETag.
            coEvery { pages.getPage(PAGE) } returns page(bodyMd = "# cached")
            coEvery { pages.refreshBodyIfChanged(PAGE) } returns ApiResult.NetworkError(IOException("offline"))

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onBodyChanged("# cached, edited")
            viewModel.save()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.canEdit)
            coVerify { pages.saveBody(PAGE, "# cached, edited") }
        }

    private fun viewModel() =
        PageEditViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Destination.PageEdit.ARG_PAGE_ID to PAGE)),
            repository = pages,
            attachmentRepository = attachments,
        )

    private fun page(bodyMd: String?) =
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
            bodyMd = bodyMd,
            draftBodyMd = null,
        )

    private companion object {
        const val PAGE = 41L
    }
}
