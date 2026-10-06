package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import com.megamaced.nccollectives.domain.model.SaveOutcome
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B-97: a conflict draft is the user's own text, parked because a write lost
 * an etag race. The ConflictBanner's Replace and Discard are what resolve it.
 * Before this, any successful save to the page resolved it too, by deleting
 * it. That included writes the user never connected to the conflict: a share
 * appended to the page, or the upload worker repointing an attachment link.
 */
@RunWith(AndroidJUnit4::class)
class ConflictDraftIntegrationTest {
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
    fun aShareAppendedToAPageWithAConflictDraft_leavesTheDraftAlone() =
        runTest {
            seedConflict()
            dispatcher.on(".md", OcsResponses.webDav(204, etag = "\"etag-3\""), method = "PUT")

            val outcome = env.pageRepository.appendToPage(12, "shared snippet")

            assertEquals(SaveOutcome.Saved, outcome)
            assertConflictIntact()
        }

    @Test
    fun anOnlineEditorSave_leavesAnUnrelatedConflictDraftAlone() =
        runTest {
            // The editor edits the server's body, not the draft, so saving it
            // says nothing about whether the user is done with their draft.
            seedConflict()
            dispatcher.on(".md", OcsResponses.webDav(204, etag = "\"etag-3\""), method = "PUT")

            env.pageRepository.saveBody(12, "server body, edited")

            assertConflictIntact()
            assertEquals(
                "server body, edited",
                env.db
                    .pageDao()
                    .getById(12)
                    ?.bodyMd,
            )
        }

    private suspend fun seedConflict() {
        env.seedPage(id = 12, bodyMd = "server body", bodyEtag = "etag-2", draftBodyMd = "my parked words")
        env.db.editQueueDao().upsert(
            EditQueueEntity(pageId = 12, baseEtag = "etag-2", newBodyMd = "my parked words", queuedAt = 1L, status = "CONFLICTED"),
        )
    }

    private suspend fun assertConflictIntact() {
        assertEquals(
            "the parked draft must survive",
            "my parked words",
            env.db
                .pageDao()
                .getById(12)
                ?.draftBodyMd,
        )
        assertEquals(
            "and so must the marker the banner and the B-19 guard key on",
            "CONFLICTED",
            env.db
                .editQueueDao()
                .forPage(12)
                ?.status,
        )
    }
}
