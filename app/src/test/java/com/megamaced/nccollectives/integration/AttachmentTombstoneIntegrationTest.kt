package com.megamaced.nccollectives.integration

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.megamaced.nccollectives.data.db.entity.AttachmentEntity
import com.megamaced.nccollectives.sync.AttachmentUploadWorker
import com.megamaced.nccollectives.sync.MAX_UPLOAD_ATTEMPTS
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B-103: a cancelled attachment's tombstone settles, however the server
 * answers.
 *
 * A tombstone (a `DELETING` row) is the user's request to remove an upload
 * whose bytes may already be on the server (issue #35). Any failure to
 * delete used to keep it and ask WorkManager for another run, with no budget
 * and no reading of the answer. A server that refuses the DELETE (403, no
 * permission on the attachment folder) therefore kept the upload worker
 * retrying for as long as the app was installed, and every retry re-ran the
 * whole upload queue.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentTombstoneIntegrationTest {
    private lateinit var env: IntegrationEnvironment
    private lateinit var dispatcher: RoutingDispatcher
    private val key = AttachmentEntity.key(12, "photo.jpg")

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
    fun aDeleteTheServerRefuses_dropsTheTombstone() =
        runTest {
            seedTombstone()
            dispatcher.on("/remote.php/dav", OcsResponses.webDav(403), method = "DELETE")

            val result = worker().doWork()

            assertTrue("nothing to retry, was $result", result is ListenableWorker.Result.Success)
            assertNull(
                "the server keeps the file; the next listing shows it, which is the truth",
                env.db.attachmentDao().getById(key),
            )
            assertTrue("and the staged bytes go with the row", !env.stagedFile(12, "photo.jpg").exists())
        }

    @Test
    fun aDeleteThatFailsTransiently_spendsAnAttemptAndRetries() =
        runTest {
            seedTombstone()
            dispatcher.on("/remote.php/dav", OcsResponses.webDav(503), method = "DELETE")

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Retry)
            assertEquals(
                1,
                env.db
                    .attachmentDao()
                    .getById(key)
                    ?.attempts,
            )
        }

    @Test
    fun aDeleteOutOfBudget_dropsTheTombstone() =
        runTest {
            seedTombstone(attempts = MAX_UPLOAD_ATTEMPTS - 1)
            dispatcher.on("/remote.php/dav", OcsResponses.webDav(503), method = "DELETE")

            worker().doWork()

            assertNull(env.db.attachmentDao().getById(key))
        }

    @Test
    fun aDeleteThatNeverReachedTheServer_spendsNothing() =
        runTest {
            seedTombstone()
            env.server.shutdown()

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Retry)
            assertEquals(
                0,
                env.db
                    .attachmentDao()
                    .getById(key)
                    ?.attempts,
            )
        }

    private suspend fun seedTombstone(attempts: Int = 0) {
        env.seedPage(id = 12)
        env.seedStagedUpload(pageId = 12, fileName = "photo.jpg", status = AttachmentEntity.STATUS_DELETING, attempts = attempts)
    }

    private fun worker(): AttachmentUploadWorker =
        TestListenableWorkerBuilder<AttachmentUploadWorker>(env.context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        AttachmentUploadWorker(
                            appContext = appContext,
                            params = workerParameters,
                            pageDao = env.db.pageDao(),
                            attachmentDao = env.db.attachmentDao(),
                            bodyService = env.bodyService,
                            attachmentRepository = env.attachmentRepository,
                            pageRepository = env.pageRepository,
                            accountGeneration = env.accountGeneration,
                            sessionManager = env.sessionManager,
                            database = env.db,
                        )
                },
            ).build()
}
