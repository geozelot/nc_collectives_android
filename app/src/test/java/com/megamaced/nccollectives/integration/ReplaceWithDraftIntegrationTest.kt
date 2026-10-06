package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import com.megamaced.nccollectives.domain.model.SaveOutcome
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B-98: "Replace with my draft" while offline.
 *
 * The force-write is queued, and the draft used to stay on the page row
 * beside it. So the ConflictBanner kept offering Replace and Discard for a
 * conflict the user had already resolved. Meanwhile the page went on showing
 * the queued body, and further offline edits coalesced into the force row.
 * Discard then deleted that row along with everything coalesced into it, and
 * Replace re-queued the old draft over it.
 */
@RunWith(AndroidJUnit4::class)
class ReplaceWithDraftIntegrationTest {
    private lateinit var env: IntegrationEnvironment
    private lateinit var dispatcher: RoutingDispatcher

    @Before
    fun setUp() {
        env = IntegrationEnvironment.create()
        dispatcher = RoutingDispatcher()
        env.server.dispatcher = dispatcher
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun anOfflineReplace_movesTheDraftIntoTheQueue() =
        runTest {
            seedConflict()
            offline()

            val outcome = env.pageRepository.replaceWithDraft(12, "my parked words")

            assertEquals(SaveOutcome.Queued, outcome)
            assertNull(
                "the conflict is resolved: nothing is left for the banner to offer",
                env.db
                    .pageDao()
                    .getById(12)
                    ?.draftBodyMd,
            )
            val row = env.db.editQueueDao().forPage(12)
            assertEquals("my parked words", row?.newBodyMd)
            assertEquals("PENDING", row?.status)
            assertTrue("the user chose to overwrite", row?.forceWrite == true)
        }

    @Test
    fun editsAfterAnOfflineReplace_areNotUndoneByTheStaleDraft() =
        runTest {
            seedConflict()
            offline()
            env.pageRepository.replaceWithDraft(12, "my parked words")

            // The editor reads the queued body back, so a further offline save
            // contains the replaced text plus the new edit.
            env.pageRepository.saveBody(12, "my parked words, and more")

            assertEquals("my parked words, and more", env.db.editQueueDao().pendingBody(12))
            assertNull(
                env.db
                    .pageDao()
                    .getById(12)
                    ?.draftBodyMd,
            )
        }

    private suspend fun seedConflict() {
        env.seedPage(id = 12, bodyMd = "server body", bodyEtag = "etag-2", draftBodyMd = "my parked words")
        env.db.editQueueDao().upsert(
            EditQueueEntity(pageId = 12, baseEtag = "etag-2", newBodyMd = "my parked words", queuedAt = 1L, status = "CONFLICTED"),
        )
    }

    /** Nothing listening any more: every request fails to connect. */
    private fun offline() {
        env.server.shutdown()
    }
}
