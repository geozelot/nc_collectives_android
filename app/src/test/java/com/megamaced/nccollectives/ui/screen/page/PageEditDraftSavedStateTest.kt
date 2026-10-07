package com.megamaced.nccollectives.ui.screen.page

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.EditorDraftSpill
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * U17: the native editor's draft across process death, without putting a
 * long page through the Binder.
 *
 * The draft used to sit in the ViewModel's `SavedStateHandle` *and* in the
 * screen's `rememberSaveable`, so a page of about 250,000 characters made a
 * megabyte of saved state — UTF-16, twice — and the app crashed with
 * `TransactionTooLargeException` whenever it went to the background, which
 * the camera button does. Real Bundles, Parcels and files here, because the
 * size is the point.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PageEditDraftSavedStateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val spill = EditorDraftSpill(context)
    private val pages = mockk<PageRepository>(relaxed = true)
    private val attachments = mockk<AttachmentRepository>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val page = mockk<Page>(relaxed = true) { every { bodyMd } returns "the server's body" }
        coEvery { pages.getPage(PAGE_ID) } returns page
        coEvery { pages.refreshBodyIfChanged(PAGE_ID) } returns ApiResult.Success(false)
        coEvery { attachments.attachmentsBaseUrl(PAGE_ID) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        EditorDraftSpill.clearAll(context)
    }

    @Test
    fun aShortDraft_comesBackAfterProcessDeath() {
        val handle = freshHandle()
        viewModel(handle).onBodyChanged("my edit")

        val restored = viewModel(afterProcessDeath(handle))

        assertEquals("my edit", restored.draftBody.value)
    }

    @Test
    fun aLongDraft_staysOutOfTheBundleAndStillComesBack() {
        val long = "x".repeat(300_000)
        val handle = freshHandle()
        viewModel(handle).onBodyChanged(long)

        val saved = handle.savedStateProvider().saveState()

        assertTrue("saved state was ${parcelledSize(saved)} bytes", parcelledSize(saved) < 10_000)
        assertEquals(long, viewModel(SavedStateHandle.createHandle(saved, null)).draftBody.value)
    }

    @Test
    fun aParkedDraftThatWasWiped_isSeededFromThePageNotLeftEmpty() {
        val handle = freshHandle()
        viewModel(handle).onBodyChanged("x".repeat(300_000))
        val saved = handle.savedStateProvider().saveState()
        EditorDraftSpill.clearAll(context)

        val restored = viewModel(SavedStateHandle.createHandle(saved, null))

        assertEquals("an empty editor could be saved over the page", "the server's body", restored.draftBody.value)
    }

    @Test
    fun theScreenSavesTheCaretAndNotTheText() {
        val saver = caretOnlySaver { "restored" }
        val scope = SaverScope { true }

        val saved = with(saver) { scope.save(TextFieldValue("a".repeat(100_000), TextRange(3, 5))) }

        assertEquals(listOf(3, 5), saved)
        val restored = saver.restore(saved!!)
        assertEquals("restored", restored?.text)
        assertEquals(TextRange(3, 5), restored?.selection)
        assertEquals("clamped to the text", TextRange(8, 8), caretOnlySaver { "restored" }.restore(listOf(50, 90))?.selection)
    }

    private fun freshHandle() = SavedStateHandle(mapOf(Destination.PageEdit.ARG_PAGE_ID to PAGE_ID))

    /** What the system hands back after killing the process: the saved Bundle, re-parcelled. */
    private fun afterProcessDeath(handle: SavedStateHandle): SavedStateHandle {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(handle.savedStateProvider().saveState())
            parcel.setDataPosition(0)
            return SavedStateHandle.createHandle(parcel.readBundle(javaClass.classLoader), null)
        } finally {
            parcel.recycle()
        }
    }

    private fun parcelledSize(bundle: Bundle): Int {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            return parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }

    private fun viewModel(handle: SavedStateHandle) = PageEditViewModel(handle, pages, attachments, spill)

    private companion object {
        const val PAGE_ID = 17L
    }
}
