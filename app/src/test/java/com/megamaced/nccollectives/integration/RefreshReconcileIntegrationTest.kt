package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.db.entity.AttachmentEntity
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import com.megamaced.nccollectives.integration.IntegrationEnvironment.Companion.COLLECTIVE_ID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `refresh` reconciles the local cache against a single server listing: rows
 * the listing names are upserted, and every row it doesn't name is deleted,
 * cascading to that page's queued edit and attachment rows (B-65, B-66).
 *
 * That is right for a cache and wrong for anything the server has never
 * seen. A queued offline edit, a parked conflict draft and a staged upload
 * exist only on this device, so deleting them loses the user's work, and
 * nothing tells the user it happened. These tests pin two rules:
 *
 *  - a listing that doesn't say which rows exist is not evidence that they
 *    are gone, and
 *  - a listing that omits a row is not permission to delete unsynced text
 *    along with it.
 */
@RunWith(AndroidJUnit4::class)
class RefreshReconcileIntegrationTest {
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

    // --- Page listings that don't say which pages exist ---

    @Test
    fun pageListingWithoutAPagesKey_failsAndDeletesNothing() =
        runTest {
            seedLandingAndChild()
            servePages(OcsResponses.envelope("{}"))

            val result = env.pageRepository.refresh(COLLECTIVE_ID)

            assertFalse("a listing with no page list is not a refresh, was $result", result is ApiResult.Success)
            assertEquals(setOf(LANDING, CHILD), pageIds())
        }

    @Test
    fun pageListingWithNullPages_failsAndDeletesNothing() =
        runTest {
            seedLandingAndChild()
            servePages(OcsResponses.envelope("""{"pages":null}"""))

            val result = env.pageRepository.refresh(COLLECTIVE_ID)

            assertFalse("a null page list is not a refresh, was $result", result is ApiResult.Success)
            assertEquals(setOf(LANDING, CHILD), pageIds())
        }

    @Test
    fun emptyPageListing_deletesNothing() =
        runTest {
            // Every collective has a landing page the server can't delete, so
            // an empty listing describes a server fault, not the collective.
            seedLandingAndChild()
            servePages(OcsResponses.pageList())

            env.pageRepository.refresh(COLLECTIVE_ID)

            assertEquals(setOf(LANDING, CHILD), pageIds())
        }

    // --- Page listings that omit a page ---

    @Test
    fun pageTheListingOmits_isRemoved_whenNothingOnItIsUnsynced() =
        runTest {
            seedLandingAndChild()
            env.db.attachmentDao().upsert(remoteAttachment(pageId = CHILD, fileName = "done.jpg"))
            servePages(OcsResponses.pageList(OcsResponses.page(id = LANDING, title = "Landing")))

            val result = env.pageRepository.refresh(COLLECTIVE_ID)

            assertTrue("refresh should succeed, was $result", result is ApiResult.Success)
            assertEquals("a cache-only row goes when the server stops listing it", setOf(LANDING), pageIds())
            assertTrue(
                "its cached attachment rows go with it",
                env.db
                    .attachmentDao()
                    .listForPage(CHILD)
                    .isEmpty(),
            )
        }

    @Test
    fun pageTheListingOmits_keepsItsQueuedEdit() =
        runTest {
            seedLandingAndChild()
            env.db.editQueueDao().upsert(queuedEdit(pageId = CHILD, body = "# written offline"))
            servePages(OcsResponses.pageList(OcsResponses.page(id = LANDING, title = "Landing")))

            env.pageRepository.refresh(COLLECTIVE_ID)

            assertNotNull("the page holding the edit must survive", env.db.pageDao().getById(CHILD))
            assertEquals("# written offline", env.db.editQueueDao().pendingBody(CHILD))
        }

    @Test
    fun pageTheListingOmits_keepsTheDraftTheFlushWorkerParkedOnIt() =
        runTest {
            // What `EditFlushWorker` leaves behind when the PUT gets a 404 because
            // the page was trashed or moved: the text as a draft on the page row,
            // and the queue row marked CONFLICTED. Its KDoc promises that this
            // stays recoverable.
            env.seedPage(id = LANDING, title = "Landing")
            env.seedPage(id = CHILD, parentId = LANDING, bodyMd = "# server copy", draftBodyMd = "# my words")
            env.db.editQueueDao().upsert(
                queuedEdit(pageId = CHILD, body = "# my words", status = "CONFLICTED"),
            )
            servePages(OcsResponses.pageList(OcsResponses.page(id = LANDING, title = "Landing")))

            env.pageRepository.refresh(COLLECTIVE_ID)

            assertEquals(
                "the parked draft must survive",
                "# my words",
                env.db
                    .pageDao()
                    .getById(CHILD)
                    ?.draftBodyMd,
            )
            assertNotNull(env.db.editQueueDao().forPage(CHILD))
        }

    @Test
    fun pageTheListingOmits_keepsItsStagedUpload() =
        runTest {
            seedLandingAndChild()
            env.seedStagedUpload(pageId = CHILD, fileName = "photo.jpg", bytes = "the only copy".toByteArray())
            servePages(OcsResponses.pageList(OcsResponses.page(id = LANDING, title = "Landing")))

            env.pageRepository.refresh(COLLECTIVE_ID)

            val row = env.db.attachmentDao().getById(AttachmentEntity.key(CHILD, "photo.jpg"))
            assertNotNull("the row is the only pointer to the staged bytes", row)
            assertEquals(AttachmentEntity.STATUS_PENDING, row?.status)
            assertTrue(env.stagedFile(pageId = CHILD, fileName = "photo.jpg").exists())
        }

    @Test
    fun pageTheListingOmits_keepsTheAncestorsThatMakeItReachable() =
        runTest {
            // A collaborator trashed a subtree: the parent goes and the child
            // with it. If the child is kept but its parent isn't, the tree,
            // which walks down from the landing page, can no longer reach it.
            env.seedPage(id = LANDING, title = "Landing")
            env.seedPage(id = PARENT, parentId = LANDING, title = "Parent")
            env.seedPage(id = CHILD, parentId = PARENT, title = "Child")
            env.seedPage(id = UNRELATED, parentId = LANDING, title = "Unrelated")
            env.db.editQueueDao().upsert(queuedEdit(pageId = CHILD, body = "# written offline"))
            servePages(OcsResponses.pageList(OcsResponses.page(id = LANDING, title = "Landing")))

            env.pageRepository.refresh(COLLECTIVE_ID)

            assertEquals(setOf(LANDING, PARENT, CHILD), pageIds())
        }

    // --- Collective listings ---

    @Test
    fun collectiveListingWithoutACollectivesKey_failsAndDeletesNothing() =
        runTest {
            env.seedCollective()
            seedLandingAndChild()
            serveCollectives(OcsResponses.envelope("{}"))

            val result = env.collectiveRepository.refresh()

            assertFalse("a listing with no collective list is not a refresh, was $result", result is ApiResult.Success)
            assertNotNull(env.db.collectiveDao().getById(COLLECTIVE_ID))
            assertEquals(setOf(LANDING, CHILD), pageIds())
        }

    @Test
    fun collectiveTheListingOmits_isRemovedWithItsPages_whenNothingInItIsUnsynced() =
        runTest {
            env.seedCollective()
            seedLandingAndChild()
            serveCollectives(OcsResponses.collectiveList(OcsResponses.collective(id = OTHER_COLLECTIVE)))

            val result = env.collectiveRepository.refresh()

            assertTrue("refresh should succeed, was $result", result is ApiResult.Success)
            assertNull(env.db.collectiveDao().getById(COLLECTIVE_ID))
            assertTrue(pageIds().isEmpty())
        }

    @Test
    fun collectiveTheListingOmits_keepsThePagesHoldingUnsyncedText() =
        runTest {
            // The user was removed from the collective, or it was deleted. The
            // edit can't reach the server any more, but the user can still
            // copy it out, as long as the page and the collective are still there.
            env.seedCollective()
            seedLandingAndChild()
            env.seedPage(id = UNRELATED, parentId = LANDING, title = "Unrelated")
            env.db.editQueueDao().upsert(queuedEdit(pageId = CHILD, body = "# written offline"))
            serveCollectives(OcsResponses.collectiveList(OcsResponses.collective(id = OTHER_COLLECTIVE)))

            env.collectiveRepository.refresh()

            assertNotNull("the collective must stay reachable", env.db.collectiveDao().getById(COLLECTIVE_ID))
            assertEquals(setOf(LANDING, CHILD), pageIds())
            assertEquals("# written offline", env.db.editQueueDao().pendingBody(CHILD))
        }

    private suspend fun seedLandingAndChild() {
        env.seedPage(id = LANDING, title = "Landing")
        env.seedPage(id = CHILD, parentId = LANDING, title = "Child", bodyMd = "# cached")
    }

    private suspend fun pageIds(): Set<Long> =
        env.db
            .pageDao()
            .idsForCollective(COLLECTIVE_ID)
            .toSet()

    private fun servePages(listing: okhttp3.mockwebserver.MockResponse) {
        dispatcher
            .on("/tags", OcsResponses.emptyTagList(), method = "GET")
            .on("/pages", listing, method = "GET")
    }

    private fun serveCollectives(listing: okhttp3.mockwebserver.MockResponse) {
        dispatcher.on("/api/v1.0/collectives", listing, method = "GET")
    }

    /** An attachment the server already has: cache, nothing to lose. */
    private fun remoteAttachment(
        pageId: Long,
        fileName: String,
    ) = AttachmentEntity(
        id = AttachmentEntity.key(pageId, fileName),
        pageId = pageId,
        fileName = fileName,
        contentType = "image/jpeg",
        size = 1,
        lastModifiedMs = 0,
        etag = "att-etag",
        status = AttachmentEntity.STATUS_REMOTE,
        localUriString = null,
        lastSyncedAt = 0,
    )

    private fun queuedEdit(
        pageId: Long,
        body: String,
        status: String = "PENDING",
    ) = EditQueueEntity(
        pageId = pageId,
        baseEtag = "etag-1",
        newBodyMd = body,
        queuedAt = 1L,
        status = status,
    )

    private companion object {
        const val LANDING = 1L
        const val PARENT = 40L
        const val CHILD = 41L
        const val UNRELATED = 42L
        const val OTHER_COLLECTIVE = 8L
    }
}
