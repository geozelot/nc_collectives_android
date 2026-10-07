package com.megamaced.nccollectives.integration

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import com.megamaced.nccollectives.domain.model.SaveOutcome
import com.megamaced.nccollectives.sync.EditFlushWorker
import com.megamaced.nccollectives.util.bodyFingerprint
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B-99: the native editor saves against the body it was opened on.
 *
 * The editor is seeded once, but its save used to take its `If-Match` from
 * the page row at save time. Anything that moved the page row while the
 * editor was open therefore became the base of a write that never included
 * it. That might be a share appended to the page, or the upload worker
 * repointing a link. The save matched, and the other write was gone.
 *
 * The editor now says what it started from (a fingerprint of the body it was
 * seeded with). A save over a page that has moved since is parked as a
 * conflict draft, the same way a 412 is. That only works if a draft, once
 * parked, outlives the other writes to the page, so the rule from B-97
 * becomes general: only the banner's Replace and Discard clear a draft.
 */
@RunWith(AndroidJUnit4::class)
class EditorBaseIntegrationTest {
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
    fun aSaveOverAPageThatMovedWhileTheEditorWasOpen_isParkedNotWritten() =
        runTest {
            env.seedPage(id = 12, bodyMd = "v1", bodyEtag = "etag-1")
            val openedOn = bodyFingerprint("v1")
            // A share lands while the editor is open.
            dispatcher.on(".md", OcsResponses.webDav(204, etag = "\"etag-2\""), method = "PUT")
            env.pageRepository.appendToPage(12, "shared snippet")
            val putsBefore = dispatcher.requestsWithMethod("PUT").size

            val outcome = env.pageRepository.saveBody(12, "v1, edited", basedOn = openedOn)

            assertEquals(SaveOutcome.Conflict, outcome)
            assertEquals("nothing written over the share", putsBefore, dispatcher.requestsWithMethod("PUT").size)
            val row = env.db.pageDao().getById(12)
            assertTrue("the share is still the page", row?.bodyMd.orEmpty().contains("shared snippet"))
            assertEquals("the edit is a draft the banner offers", "v1, edited", row?.draftBodyMd)
            assertEquals(
                "CONFLICTED",
                env.db
                    .editQueueDao()
                    .forPage(12)
                    ?.status,
            )
        }

    @Test
    fun aSaveOverAnUnmovedPage_writesAsBefore() =
        runTest {
            env.seedPage(id = 12, bodyMd = "v1", bodyEtag = "etag-1")
            dispatcher.on(".md", OcsResponses.webDav(204, etag = "\"etag-2\""), method = "PUT")

            val outcome = env.pageRepository.saveBody(12, "v1, edited", basedOn = bodyFingerprint("v1"))

            assertEquals(SaveOutcome.Saved, outcome)
            assertEquals("\"etag-1\"", dispatcher.requestsWithMethod("PUT").single().getHeader("If-Match"))
        }

    @Test
    fun aSaveOverAQueuedAppend_parksTheEditAndKeepsTheQueuedText() =
        runTest {
            // The share was captured offline, so it sits in the queue rather
            // than on the server. The editor's save must not replace that row.
            env.seedPage(id = 12, bodyMd = "v1", bodyEtag = "etag-1")
            env.db.editQueueDao().upsert(queued(pageId = 12, body = "v1\n\nshared snippet"))

            val outcome = env.pageRepository.saveBody(12, "v1, edited", basedOn = bodyFingerprint("v1"))

            assertEquals(SaveOutcome.Conflict, outcome)
            assertTrue(dispatcher.requestsWithMethod("PUT").isEmpty())
            assertEquals("v1\n\nshared snippet", env.db.editQueueDao().pendingBody(12))
            assertEquals(
                "v1, edited",
                env.db
                    .pageDao()
                    .getById(12)
                    ?.draftBodyMd,
            )
        }

    @Test
    fun aMovedPageThatAlreadyHasADraft_refusesRatherThanOverwriteIt() =
        runTest {
            // There is one draft slot. Parking the editor's text would destroy
            // the draft already there, so the save is refused and the editor
            // keeps the text on screen.
            env.seedPage(id = 12, bodyMd = "v2", bodyEtag = "etag-2", draftBodyMd = "an earlier draft")
            env.db.editQueueDao().upsert(queued(pageId = 12, body = "an earlier draft", status = "CONFLICTED"))

            val outcome = env.pageRepository.saveBody(12, "v1, edited", basedOn = bodyFingerprint("v1"))

            assertTrue("was $outcome", outcome is SaveOutcome.Error)
            assertEquals(
                "an earlier draft",
                env.db
                    .pageDao()
                    .getById(12)
                    ?.draftBodyMd,
            )
            assertTrue(dispatcher.requestsWithMethod("PUT").isEmpty())
        }

    @Test
    fun discardingADraftBesideAQueuedEdit_keepsTheQueuedEdit() =
        runTest {
            env.seedPage(id = 12, bodyMd = "v1", bodyEtag = "etag-1", draftBodyMd = "v1, edited")
            env.db.editQueueDao().upsert(queued(pageId = 12, body = "v1\n\nshared snippet"))

            env.pageRepository.discardDraft(12)

            assertNull(
                env.db
                    .pageDao()
                    .getById(12)
                    ?.draftBodyMd,
            )
            assertEquals("the queued share is not the draft", "v1\n\nshared snippet", env.db.editQueueDao().pendingBody(12))
        }

    @Test
    fun aFlushBesideADraft_leavesTheDraft() =
        runTest {
            env.seedPage(id = 12, bodyMd = "v1", bodyEtag = "etag-1", draftBodyMd = "v1, edited")
            env.db.editQueueDao().upsert(queued(pageId = 12, body = "v1\n\nshared snippet"))
            dispatcher
                .on(".md", OcsResponses.webDav(200, etag = "\"etag-1\"").setBody("v1"), method = "GET")
                .on(".md", OcsResponses.webDav(204, etag = "\"etag-2\""), method = "PUT")

            worker().doWork()

            val row = env.db.pageDao().getById(12)
            assertEquals("v1\n\nshared snippet", row?.bodyMd)
            assertEquals("the flush resolved nothing about the draft", "v1, edited", row?.draftBodyMd)
        }

    private fun queued(
        pageId: Long,
        body: String,
        status: String = "PENDING",
    ) = EditQueueEntity(pageId = pageId, baseEtag = "etag-1", newBodyMd = body, queuedAt = 1L, status = status)

    private fun worker(): EditFlushWorker =
        TestListenableWorkerBuilder<EditFlushWorker>(env.context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        EditFlushWorker(
                            appContext = appContext,
                            params = workerParameters,
                            pageDao = env.db.pageDao(),
                            editQueueDao = env.db.editQueueDao(),
                            bodyService = env.bodyService,
                            database = env.db,
                            accountGeneration = env.accountGeneration,
                            sessionManager = env.sessionManager,
                        )
                },
            ).build()
}
