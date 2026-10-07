package com.megamaced.nccollectives.ui.screen.share

import android.content.Context
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.share.SharePayload
import com.megamaced.nccollectives.share.SharePayloadHolder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * U1: a share the user backed out of stays cancelled. Left in the holder,
 * and so in the activity's saved state, it re-opened the share screen on the
 * next rotation or process restore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareCaptureCancelTest {
    private val dispatcher = StandardTestDispatcher()
    private val holder = SharePayloadHolder()
    private val collectives = mockk<CollectiveRepository>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { collectives.observeCollectives() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun cancelling_consumesTheShare() =
        runTest(dispatcher) {
            holder.publish(SharePayload(text = "declined"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.cancel()

            assertNull(holder.payload.value)
        }

    @Test
    fun cancelling_leavesANewerShareAlone() =
        runTest(dispatcher) {
            // Issue #25's rule for consume(): only the payload this screen
            // shows, never one that arrived after it.
            holder.publish(SharePayload(text = "declined"))
            val viewModel = viewModel()
            advanceUntilIdle()
            val newer = SharePayload(text = "arrived meanwhile")
            holder.publish(newer)

            viewModel.cancel()

            assertEquals(newer, holder.payload.value)
        }

    private fun viewModel() =
        ShareCaptureViewModel(
            context = mockk<Context>(relaxed = true),
            sharePayloadHolder = holder,
            pageRepository = mockk<PageRepository>(relaxed = true),
            collectiveRepository = collectives,
            attachmentRepository = mockk<AttachmentRepository>(relaxed = true),
        )
}
