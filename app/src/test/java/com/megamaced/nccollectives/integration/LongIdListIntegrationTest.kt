package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.db.dao.AttachmentDao
import com.megamaced.nccollectives.data.db.dao.EditQueueDao
import com.megamaced.nccollectives.data.repository.CollectiveRepositoryImpl
import com.megamaced.nccollectives.integration.IntegrationEnvironment.Companion.COLLECTIVE_ID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B-101: no `IN (:ids)` statement may bind 1000 or more ids.
 *
 * SQLite before 3.32, which is what API 29 and 30 ship (minSdk is 29),
 * rejects a statement with more than 999 bound arguments. Room expands
 * `IN (:ids)` to one argument per element. The JVM tests run on a host
 * SQLite with a far higher limit, so they can't see the crash. This pins
 * the batch sizes instead, through DAOs that record the longest list they
 * were handed.
 */
@RunWith(AndroidJUnit4::class)
class LongIdListIntegrationTest {
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
    fun aCollectiveOfAThousandPagesGoingAway_deletesInBatches() =
        runTest {
            env.seedCollective()
            for (id in 1L..1_200L) env.seedPage(id = id, parentId = if (id == 1L) 0 else 1)
            val attachments = RecordingAttachmentDao(env.db.attachmentDao())
            val queue = RecordingEditQueueDao(env.db.editQueueDao())
            dispatcher.on("/api/v1.0/collectives", OcsResponses.collectiveList(), method = "GET")

            CollectiveRepositoryImpl(
                api = env.api,
                circlesApi = env.circlesApi,
                dao = env.db.collectiveDao(),
                pageDao = env.db.pageDao(),
                attachmentDao = attachments,
                editQueueDao = queue,
                database = env.db,
                accountGeneration = env.accountGeneration,
            ).refresh()

            assertTrue(
                "the collective's pages are gone",
                env.db
                    .pageDao()
                    .idsForCollective(COLLECTIVE_ID)
                    .isEmpty(),
            )
            assertTrue("attachments bound ${attachments.longest} ids", attachments.longest in 1..999)
            assertTrue("queue bound ${queue.longest} ids", queue.longest in 1..999)
        }

    private class RecordingAttachmentDao(
        private val real: AttachmentDao,
    ) : AttachmentDao by real {
        var longest = 0

        override suspend fun deleteForPageIds(pageIds: List<Long>) {
            longest = maxOf(longest, pageIds.size)
            real.deleteForPageIds(pageIds)
        }
    }

    private class RecordingEditQueueDao(
        private val real: EditQueueDao,
    ) : EditQueueDao by real {
        var longest = 0

        override suspend fun deleteForPageIds(pageIds: List<Long>) {
            longest = maxOf(longest, pageIds.size)
            real.deleteForPageIds(pageIds)
        }
    }
}
