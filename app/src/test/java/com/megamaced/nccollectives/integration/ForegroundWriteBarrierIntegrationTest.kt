package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.auth.StoredCredentials
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import com.megamaced.nccollectives.data.repository.CollectiveRepositoryImpl
import com.megamaced.nccollectives.domain.model.SaveOutcome
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D7b: issue #20's barrier, for the writes the foreground makes after a
 * request returns.
 *
 * `refresh` and the workers capture the account generation before their
 * request and check it inside the transaction that writes the reply. The
 * repository calls a screen makes did not: a save, a create, a copy, a
 * rename, a trash, an attachment listing and the rollbacks of optimistic
 * updates all wrote whatever came back, whenever it came back. Their
 * follow-up `refresh` was no help either, because it captured the
 * generation *after* the wipe and so passed its own check.
 *
 * Each test changes the account while the request is on the wire, which is
 * what `whileInFlight` is for, and checks the cache wasn't touched. In the
 * app the cache would just have been cleared, and the incoming account
 * shares page ids with the outgoing one (they are raw server ids), so a
 * write that lands is one account's content filed under another's.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundWriteBarrierIntegrationTest {
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
    fun anOfflineSave_queuesNothingForTheAccountThatLeft() =
        runTest {
            env.seedPage(id = 17, bodyMd = "server", bodyEtag = "e1")
            switchAccountAndLoseTheConnection()

            val outcome = env.pageRepository.saveBody(17, "account A's edit")

            assertTrue("nothing was queued, so it must not say so: $outcome", outcome is SaveOutcome.Error)
            assertNull(env.db.editQueueDao().forPage(17))
        }

    @Test
    fun aRefusedSave_parksNoDraft() =
        runTest {
            env.seedPage(id = 17, bodyMd = "server", bodyEtag = "e1")
            dispatcher
                .on(".md", OcsResponses.webDav(412), method = "PUT")
                .on(".md", OcsResponses.webDav(200, etag = "\"e2\"").setBody("theirs"), method = "GET")
            switchAccountDuring(".md")

            val outcome = env.pageRepository.saveBody(17, "account A's edit")

            assertTrue("no draft was parked, so it must not say so: $outcome", outcome is SaveOutcome.Error)
            val page = env.db.pageDao().getById(17)
            assertNull(page?.draftBodyMd)
            assertEquals("server", page?.bodyMd)
            assertNull(env.db.editQueueDao().forPage(17))
        }

    @Test
    fun anAcceptedSave_isNotWrittenIntoTheCache() =
        runTest {
            env.seedPage(id = 17, bodyMd = "server", bodyEtag = "e1")
            dispatcher.on(".md", OcsResponses.webDav(204, etag = "\"e2\""), method = "PUT")
            switchAccountDuring(".md")

            val outcome = env.pageRepository.saveBody(17, "account A's edit")

            assertEquals("the server has it", SaveOutcome.Saved, outcome)
            val page = env.db.pageDao().getById(17)
            assertEquals("server", page?.bodyMd)
            assertEquals("e1", page?.bodyEtag)
        }

    @Test
    fun anOfflineReplaceWithDraft_queuesNothing() =
        runTest {
            env.seedPage(id = 17, bodyMd = "server", bodyEtag = "e1", draftBodyMd = "draft")
            switchAccountAndLoseTheConnection()

            val outcome = env.pageRepository.replaceWithDraft(17, "draft")

            assertTrue(outcome is SaveOutcome.Error)
            assertNull(env.db.editQueueDao().forPage(17))
            assertEquals(
                "draft",
                env.db
                    .pageDao()
                    .getById(17)
                    ?.draftBodyMd,
            )
        }

    @Test
    fun aCreatedPage_isNotCachedAndGetsNoBody() =
        runTest {
            env.seedPage(id = 1, title = "Wiki")
            dispatcher
                .on("/pages/1", OcsResponses.singlePage(id = 40, title = "New", parentId = 1), method = "POST")
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on(
                    "/pages",
                    OcsResponses.pageList(OcsResponses.page(id = 1, title = "Wiki"), OcsResponses.page(id = 40, parentId = 1)),
                    method = "GET",
                )
            switchAccountDuring("/pages/1")

            val result = env.pageRepository.createPage(IntegrationEnvironment.COLLECTIVE_ID, 1, "New", "hello")

            assertTrue("a page that isn't cached can't be handed back: $result", result !is ApiResult.Success)
            assertNull(env.db.pageDao().getById(40))
            assertTrue("no body written for it", dispatcher.requestsTo(".md").isEmpty())
        }

    @Test
    fun aCopiedPage_isNotCached() =
        runTest {
            env.seedPage(id = 17)
            dispatcher
                .on("/pages/17", OcsResponses.singlePage(id = 41, title = "Copy"), method = "PUT")
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on("/pages", OcsResponses.pageList(OcsResponses.page(id = 17), OcsResponses.page(id = 41)), method = "GET")
            switchAccountDuring("/pages/17")

            val result = env.pageRepository.copyPage(IntegrationEnvironment.COLLECTIVE_ID, 17)

            assertTrue(result !is ApiResult.Success)
            assertNull(env.db.pageDao().getById(41))
        }

    @Test
    fun aRenameThatReissuedTheId_carriesNothingAcross() =
        runTest {
            env.seedPage(id = 41, title = "Old", bodyMd = "# body", bodyEtag = "e1")
            env.db.editQueueDao().upsert(queued(41))
            dispatcher
                .on("/pages/41", OcsResponses.singlePage(id = 99, title = "New"), method = "PUT")
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on("/pages", OcsResponses.pageList(OcsResponses.page(id = 99, title = "New")), method = "GET")
            switchAccountDuring("/pages/41")

            env.pageRepository.renamePage(41, "New")

            assertNull(env.db.pageDao().getById(99))
            assertNotNull(env.db.pageDao().getById(41))
            assertEquals("# queued", env.db.editQueueDao().pendingBody(41))
        }

    @Test
    fun aTrashedPage_takesNothingOutOfTheCache() =
        runTest {
            // Page 17 here stands for the incoming account's page 17.
            env.seedPage(id = 17, parentId = 1)
            env.db.editQueueDao().upsert(queued(17))
            dispatcher.on("/pages/17", OcsResponses.singlePage(id = 17, parentId = 1), method = "DELETE")
            switchAccountDuring("/pages/17")

            val result = env.pageRepository.trashPage(17)

            assertTrue("the server trashed it: $result", result is ApiResult.Success)
            assertNotNull(env.db.pageDao().getById(17))
            assertEquals("# queued", env.db.editQueueDao().pendingBody(17))
        }

    @Test
    fun aRestoredPage_doesNotRefreshIntoTheCache() =
        runTest {
            dispatcher
                .on("/pages/trash/17", OcsResponses.singlePage(id = 17), method = "PATCH")
                .on("/tags", OcsResponses.emptyTagList(), method = "GET")
                .on("/pages", OcsResponses.pageList(OcsResponses.page(id = 17)), method = "GET")
            switchAccountDuring("/pages/trash/17")

            env.pageRepository.restorePage(IntegrationEnvironment.COLLECTIVE_ID, 17)

            assertNull(env.db.pageDao().getById(17))
        }

    @Test
    fun aFailedEmojiChange_doesNotRollBackARowItNoLongerOwns() =
        runTest {
            env.seedPage(id = 17)
            dispatcher.on("/emoji", OcsResponses.webDav(500), method = "PUT")
            switchAccountDuring("/emoji")

            env.pageRepository.setEmoji(17, "🙂")

            assertEquals(
                "🙂",
                env.db
                    .pageDao()
                    .getById(17)
                    ?.emoji,
            )
        }

    @Test
    fun anAttachmentListing_writesNothing() =
        runTest {
            env.seedPage(id = 12)
            dispatcher.on(
                "/attachments",
                OcsResponses.envelope(
                    """{"attachments":[{"id":5,"name":"photo.jpg","filesize":4,"mimetype":"image/jpeg","timestamp":1}]}""",
                ),
                method = "GET",
            )
            switchAccountDuring("/attachments")

            val result = env.attachmentRepository.refresh(12)

            assertTrue(result is ApiResult.Success)
            assertTrue(
                env.db
                    .attachmentDao()
                    .listForPage(12)
                    .isEmpty(),
            )
        }

    @Test
    fun aCreatedCollective_isNotCached() =
        runTest {
            dispatcher.on("/collectives", OcsResponses.envelope("""{"collective":{"id":9,"name":"B"}}"""), method = "POST")
            switchAccountDuring("/collectives")

            val result = collectives().createCollective("B", null)

            assertTrue(result !is ApiResult.Success)
            assertNull(env.db.collectiveDao().getById(9))
        }

    @Test
    fun aRestoredCollective_doesNotRefreshIntoTheCache() =
        runTest {
            dispatcher
                .on("/collectives/trash/9", OcsResponses.envelope("""{"collective":{"id":9,"name":"B"}}"""), method = "PATCH")
                .on("/collectives", OcsResponses.envelope("""{"collectives":[{"id":9,"name":"B"}]}"""), method = "GET")
            switchAccountDuring("/collectives/trash/9")

            collectives().restoreTrashedCollective(9)

            assertNull(env.db.collectiveDao().getById(9))
        }

    @Test
    fun withNoSwitch_theSameCallsWriteAsBefore() =
        runTest {
            env.seedPage(id = 17, bodyMd = "server", bodyEtag = "e1")
            dispatcher.on(".md", OcsResponses.webDav(204, etag = "\"e2\""), method = "PUT")

            assertEquals(SaveOutcome.Saved, env.pageRepository.saveBody(17, "edit"))
            assertEquals(
                "edit",
                env.db
                    .pageDao()
                    .getById(17)
                    ?.bodyMd,
            )
        }

    /** The account changes while a request for [pathFragment] is on the wire. */
    private fun switchAccountDuring(pathFragment: String) {
        dispatcher.whileInFlight(pathFragment) { env.accountGeneration.invalidate() }
    }

    /**
     * The account changes as the request is being made, and the request
     * never reaches a server: the credential the network layer reads now
     * names a closed port, so the call fails with a `NetworkError` — the
     * offline path. (This MockWebServer ignores its socket policies.)
     */
    private fun switchAccountAndLoseTheConnection() {
        every { env.tokenStore.getCredentials() } answers {
            env.accountGeneration.invalidate()
            StoredCredentials(host = "https://localhost:1", loginName = IntegrationEnvironment.LOGIN_NAME, appPassword = "x")
        }
    }

    private fun queued(pageId: Long) =
        EditQueueEntity(
            pageId = pageId,
            baseEtag = "e1",
            newBodyMd = "# queued",
            queuedAt = 1,
            status = "PENDING",
        )

    private fun collectives() =
        CollectiveRepositoryImpl(
            api = env.api,
            circlesApi = mockk(relaxed = true),
            dao = env.db.collectiveDao(),
            pageDao = env.db.pageDao(),
            attachmentDao = env.db.attachmentDao(),
            editQueueDao = env.db.editQueueDao(),
            database = env.db,
            accountGeneration = env.accountGeneration,
        )
}
