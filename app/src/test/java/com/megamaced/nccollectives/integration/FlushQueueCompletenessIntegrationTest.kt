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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two gaps in how queued edits reach the server.
 *
 * A forced write — "Replace with my draft", and the queue row it leaves
 * offline — went out with no precondition at all. If the page had been
 * renamed or moved on the server meanwhile, the PUT didn't fail: it created
 * a new file at the old path, a ghost page beside the real one.
 *
 * And a run flushed at most 100 rows, the query's limit, with nothing to
 * schedule the rest — they waited for the next save or foreground.
 */
@RunWith(AndroidJUnit4::class)
class FlushQueueCompletenessIntegrationTest {
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
    fun aForcedWrite_onlyReplacesAFileThatIsThere() =
        runTest {
            env.seedPage(id = 17, bodyMd = "server", bodyEtag = "e1", draftBodyMd = "draft")
            dispatcher.on(".md", OcsResponses.webDav(204, etag = "\"e2\""), method = "PUT")

            assertEquals(SaveOutcome.Saved, env.pageRepository.replaceWithDraft(17, "draft"))

            assertEquals("*", dispatcher.requestsWithMethod("PUT").single().getHeader("If-Match"))
        }

    @Test
    fun aForcedWriteToAPageThatMovedAway_writesNoGhost() =
        runTest {
            env.seedPage(id = 17, bodyMd = "server", bodyEtag = "e1", draftBodyMd = "draft")
            // `If-Match: *` and nothing at that path any more.
            dispatcher.on(".md", OcsResponses.webDav(412), method = "PUT")

            val outcome = env.pageRepository.replaceWithDraft(17, "draft")

            assertEquals(SaveOutcome.Conflict, outcome)
            assertEquals(
                "the draft is still the user's",
                "draft",
                env.db
                    .pageDao()
                    .getById(17)
                    ?.draftBodyMd,
            )
        }

    @Test
    fun aQueueLongerThanOneBatch_isFlushedInOneRun() =
        runTest {
            for (id in 1L..150L) {
                env.seedPage(id = id, bodyMd = "server", bodyEtag = "e1")
                env.db.editQueueDao().upsert(
                    EditQueueEntity(pageId = id, baseEtag = "e1", newBodyMd = "edit $id", queuedAt = id, status = "PENDING"),
                )
            }
            dispatcher
                .on(".md", OcsResponses.webDav(200, etag = "\"e1\"").setBody("server"), method = "GET")
                .on(".md", OcsResponses.webDav(204, etag = "\"e2\""), method = "PUT")

            worker().doWork()

            assertTrue(
                "${env.db.editQueueDao().pendingEntries(500).size} edits left behind",
                env.db
                    .editQueueDao()
                    .pendingEntries(500)
                    .isEmpty(),
            )
            assertEquals(150, dispatcher.requestsWithMethod("PUT").size)
        }

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
                        )
                },
            ).build()
}
