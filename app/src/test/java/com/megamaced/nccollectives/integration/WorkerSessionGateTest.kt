package com.megamaced.nccollectives.integration

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.megamaced.nccollectives.data.auth.AuthState
import com.megamaced.nccollectives.data.auth.SessionManager
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import com.megamaced.nccollectives.sync.AttachmentUploadWorker
import com.megamaced.nccollectives.sync.EditFlushWorker
import com.megamaced.nccollectives.sync.FullSync
import com.megamaced.nccollectives.sync.SyncOutcome
import com.megamaced.nccollectives.sync.SyncWorker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B-105: the workers do nothing unless a session is authenticated.
 *
 * The account wipe cancels the work it can see. But it isn't a barrier
 * against work that starts *after* it begins:
 *
 *  - Sign-out's `clearAll()` resets the sync cadence, and `SyncScheduler`'s
 *    cadence collector then re-enqueues the periodic sync mid-wipe.
 *  - `ProcessLifecycleOwner.onStart` enqueues a sync and both flushes on
 *    every foreground, whatever the session is doing.
 *
 * A run that starts once the wipe has bumped the account generation passes
 * the issue #20 guard, while the outgoing credential is still in the store.
 * It then writes the outgoing account's data into the freshly cleared
 * database, and the next cold sign-in doesn't wipe.
 */
@RunWith(AndroidJUnit4::class)
class WorkerSessionGateTest {
    private lateinit var env: IntegrationEnvironment
    private lateinit var dispatcher: RoutingDispatcher
    private val authState = MutableStateFlow<AuthState>(AuthState.Switching)
    private val session = mockk<SessionManager>(relaxed = true)
    private val fullSync = mockk<FullSync>()

    @Before
    fun setUp() {
        env = IntegrationEnvironment.create()
        dispatcher = RoutingDispatcher()
        env.server.dispatcher = dispatcher
        every { session.authState } returns authState
        coEvery { fullSync.run() } returns SyncOutcome.Success
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun aSyncDuringASwitch_doesNothing() =
        runTest {
            worker<SyncWorker>().doWork()

            coVerify(exactly = 0) { fullSync.run() }
        }

    @Test
    fun aSyncWithALiveSession_runs() =
        runTest {
            authState.value = AuthState.Authenticated

            worker<SyncWorker>().doWork()

            coVerify(exactly = 1) { fullSync.run() }
        }

    @Test
    fun aFlushAfterSignOut_sendsNothing() =
        runTest {
            authState.value = AuthState.Unauthenticated
            env.seedPage(id = 12, bodyMd = "server body", bodyEtag = "etag-1")
            env.db.editQueueDao().upsert(
                EditQueueEntity(pageId = 12, baseEtag = "etag-1", newBodyMd = "edit", queuedAt = 1L, status = "PENDING"),
            )

            val result = worker<EditFlushWorker>().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertTrue("no request may go out", dispatcher.requests.isEmpty())
            assertEquals(
                "and the row is untouched",
                0,
                env.db
                    .editQueueDao()
                    .forPage(12)
                    ?.attempts,
            )
        }

    @Test
    fun anUploadDuringASwitch_sendsNothing() =
        runTest {
            env.seedPage(id = 12)
            env.seedStagedUpload(pageId = 12, fileName = "photo.jpg")

            worker<AttachmentUploadWorker>().doWork()

            assertTrue(dispatcher.requests.isEmpty())
        }

    private inline fun <reified W : ListenableWorker> worker(): W =
        TestListenableWorkerBuilder<W>(env.context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        when (workerClassName) {
                            SyncWorker::class.java.name -> {
                                SyncWorker(appContext, workerParameters, fullSync, session)
                            }

                            EditFlushWorker::class.java.name -> {
                                EditFlushWorker(
                                    appContext = appContext,
                                    params = workerParameters,
                                    pageDao = env.db.pageDao(),
                                    editQueueDao = env.db.editQueueDao(),
                                    bodyService = env.bodyService,
                                    database = env.db,
                                    accountGeneration = env.accountGeneration,
                                    sessionManager = session,
                                )
                            }

                            else -> {
                                AttachmentUploadWorker(
                                    appContext = appContext,
                                    params = workerParameters,
                                    pageDao = env.db.pageDao(),
                                    attachmentDao = env.db.attachmentDao(),
                                    bodyService = env.bodyService,
                                    attachmentRepository = env.attachmentRepository,
                                    pageRepository = env.pageRepository,
                                    accountGeneration = env.accountGeneration,
                                    sessionManager = session,
                                )
                            }
                        }
                },
            ).build()
}
