package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.data.db.dao.PageMetadata
import com.megamaced.nccollectives.data.db.entity.PageEntity
import com.megamaced.nccollectives.data.repository.PageRepositoryImpl
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
import com.megamaced.nccollectives.integration.IntegrationEnvironment.Companion.COLLECTIVE_ID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B-100: `refresh` writes what a listing says about pages, and nothing else.
 *
 * It used to load every cached row, bodies included, map each listed page
 * to a whole `PageEntity` carrying the cached body, etag and draft along,
 * and upsert the lot. Two costs:
 *
 *  - Anything written to those columns between that read and the upsert was
 *    reverted. `EditFlushWorker` parking a conflict draft and recording a PUT
 *    run on other threads, so the user's parked text could go back to null.
 *  - Every sync read every cached body and rewrote every row with it, then
 *    invalidated every `pages` observer, even when nothing had changed.
 *
 * The competing write is landed by [HookedPageDao] straight after the
 * refresh reads its snapshot. That is the window that was open, without
 * depending on thread timing to hit it.
 */
@RunWith(AndroidJUnit4::class)
class RefreshMetadataIntegrationTest {
    private lateinit var env: IntegrationEnvironment
    private lateinit var dispatcher: RoutingDispatcher

    @Before
    fun setUp() {
        env = IntegrationEnvironment.create()
        dispatcher = RoutingDispatcher()
        env.server.dispatcher = dispatcher
        dispatcher.on("/tags", OcsResponses.emptyTagList(), method = "GET")
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun aDraftParkedWhileTheRefreshWasBusy_isNotReverted() =
        runTest {
            env.seedPage(id = 1, title = "Landing", bodyMd = "landing", bodyEtag = "l-1")
            env.seedPage(id = 41, parentId = 1, title = "Old title", bodyMd = "server", bodyEtag = "etag-1")
            val dao = HookedPageDao(env.db.pageDao()) {
                // What parkAsConflict / recordPutOutcome write, from another
                // thread, while refresh holds its snapshot.
                env.db.pageDao().updateDraft(41, "my parked words")
                env.db.pageDao().updateBody(41, "server, newer", "etag-2", 5L)
            }
            dispatcher.on(
                "/pages",
                OcsResponses.pageList(
                    OcsResponses.page(id = 1, title = "Landing"),
                    OcsResponses.page(id = 41, title = "New title", parentId = 1),
                ),
                method = "GET",
            )

            repositoryOver(dao).refresh(COLLECTIVE_ID)

            val row = env.db.pageDao().getById(41)
            assertEquals("the listing's metadata lands", "New title", row?.title)
            assertEquals("the draft must survive", "my parked words", row?.draftBodyMd)
            assertEquals("server, newer", row?.bodyMd)
            assertEquals("etag-2", row?.bodyEtag)
        }

    @Test
    fun aListingThatChangedNothing_writesNothing() =
        runTest {
            dispatcher.on(
                "/pages",
                OcsResponses.pageList(
                    OcsResponses.page(id = 1, title = "Landing"),
                    OcsResponses.page(id = 41, title = "Child", parentId = 1),
                ),
                method = "GET",
            )
            val repository = repositoryOver(env.db.pageDao())
            repository.refresh(COLLECTIVE_ID)

            val dao = HookedPageDao(env.db.pageDao()) {}
            repositoryOver(dao).refresh(COLLECTIVE_ID)

            assertEquals("an unchanged listing is not a write", 0, dao.rowsWritten)
        }

    private fun repositoryOver(pageDao: PageDao) =
        PageRepositoryImpl(
            api = env.api,
            bodyService = env.bodyService,
            pageDao = pageDao,
            editQueueDao = env.db.editQueueDao(),
            attachmentDao = env.db.attachmentDao(),
            syncScheduler = env.syncScheduler,
            database = env.db,
            accountGeneration = env.accountGeneration,
            attachmentRepository = { env.attachmentRepository as AttachmentRepository },
        )

    /** The real DAO, with [afterRead] run once the refresh has its snapshot. */
    private class HookedPageDao(
        private val real: PageDao,
        private val afterRead: suspend () -> Unit,
    ) : PageDao by real {
        var rowsWritten = 0

        override suspend fun metadataForCollective(collectiveId: Long): List<PageMetadata> =
            real.metadataForCollective(collectiveId).also { afterRead() }

        override suspend fun upsertAll(pages: List<PageEntity>) {
            rowsWritten += pages.size
            real.upsertAll(pages)
        }

        override suspend fun upsertMetadata(rows: List<PageMetadata>) {
            rowsWritten += rows.size
            real.upsertMetadata(rows)
        }
    }
}
