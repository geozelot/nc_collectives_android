package com.megamaced.nccollectives.integration

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import com.megamaced.nccollectives.sync.EditFlushWorker
import com.megamaced.nccollectives.sync.MAX_FLUSH_ATTEMPTS
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B-102: an edit the server can't be reached for waits. It doesn't turn into
 * a conflict.
 *
 * Issue #30 put network failures through the same ten-attempt budget as the
 * server's own refusals, because a retry that never settles is otherwise
 * invisible. Network failures happen while WorkManager's CONNECTED
 * constraint holds: a LAN- or VPN-only server seen from mobile data, a
 * server that is down. On exponential backoff, ten such attempts run out
 * within hours, and every edit made away from the server's network was
 * parked as a "conflict" with a banner over it, against a server that had
 * never refused anything.
 *
 * An attempt that never reached the server now spends nothing. Settings
 * says how many edits are waiting, which is what makes waiting acceptable.
 */
@RunWith(AndroidJUnit4::class)
class UnreachableServerIntegrationTest {
    private lateinit var env: IntegrationEnvironment

    @Before
    fun setUp() {
        env = IntegrationEnvironment.create()
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun anUnreachableServer_onTheLastAttempt_leavesTheEditWaiting() =
        runTest {
            env.seedPage(id = 12, bodyMd = "server body", bodyEtag = "etag-1")
            env.db.editQueueDao().upsert(queued().copy(attempts = MAX_FLUSH_ATTEMPTS - 1))
            env.server.shutdown()

            val result = worker().doWork()

            assertTrue("worth another run, was $result", result is ListenableWorker.Result.Retry)
            val row = env.db.editQueueDao().forPage(12)
            assertEquals("PENDING", row?.status)
            assertEquals("an attempt that never reached the server is refunded", MAX_FLUSH_ATTEMPTS - 1, row?.attempts)
            assertNull(
                "nothing is in conflict",
                env.db
                    .pageDao()
                    .getById(12)
                    ?.draftBodyMd,
            )
        }

    @Test
    fun editsWaitingToBeSent_areCounted() =
        runTest {
            env.seedPage(id = 12)
            env.seedPage(id = 13)
            env.seedPage(id = 14)
            env.db.editQueueDao().upsert(queued(pageId = 12))
            env.db.editQueueDao().upsert(queued(pageId = 13))
            // A parked conflict is waiting on the user, not on the network.
            env.db.editQueueDao().upsert(queued(pageId = 14).copy(status = "CONFLICTED"))

            assertEquals(2, env.pageRepository.observeUnsentEditCount().first())
        }

    private fun queued(pageId: Long = 12) =
        EditQueueEntity(pageId = pageId, baseEtag = "etag-1", newBodyMd = "my offline edit", queuedAt = 1L, status = "PENDING")

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
