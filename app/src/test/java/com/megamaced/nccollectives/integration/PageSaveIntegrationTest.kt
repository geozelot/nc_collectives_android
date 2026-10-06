package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.domain.model.SaveOutcome
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * B-95: a write the server may already have.
 *
 * A PUT whose response never made it back -- a read timeout after the server
 * accepted it, a worker cancelled while the request was out -- leaves this
 * device not knowing whether the write landed. The next attempt then sees an
 * etag that has moved and, before this, reported a conflict against the
 * user's own write. Comparing the server's body with the text being saved
 * settles it: if they match, the write landed.
 */
@RunWith(AndroidJUnit4::class)
class PageSaveIntegrationTest {
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
    fun aSaveRefusedByItsOwnEarlierWrite_isSaved() =
        runTest {
            // The first save timed out after the server took it; the user taps
            // Save again with the same text. The If-Match is the old etag, so
            // the server says 412, and the body it holds is the one being saved.
            env.seedPage(id = 12, bodyMd = "server body", bodyEtag = "etag-1")
            dispatcher
                .on(".md", OcsResponses.webDav(412), method = "PUT")
                .on(".md", OcsResponses.webDav(200, etag = "\"etag-2\"").setBody("my text"), method = "GET")

            val outcome = env.pageRepository.saveBody(12, "my text")

            assertEquals(SaveOutcome.Saved, outcome)
            val row = env.db.pageDao().getById(12)
            assertNull("nothing is in conflict", row?.draftBodyMd)
            assertEquals("my text", row?.bodyMd)
            assertEquals("etag-2", row?.bodyEtag)
            assertNull(env.db.editQueueDao().forPage(12))
        }

    @Test
    fun aGenuineConflict_isStillAConflict() =
        runTest {
            env.seedPage(id = 12, bodyMd = "server body", bodyEtag = "etag-1")
            dispatcher
                .on(".md", OcsResponses.webDav(412), method = "PUT")
                .on(".md", OcsResponses.webDav(200, etag = "\"etag-2\"").setBody("someone else's text"), method = "GET")

            val outcome = env.pageRepository.saveBody(12, "my text")

            assertEquals(SaveOutcome.Conflict, outcome)
            assertEquals(
                "my text",
                env.db
                    .pageDao()
                    .getById(12)
                    ?.draftBodyMd,
            )
        }

    @Test
    fun cancellingASave_abandonsTheRequestInsteadOfWaitingItOut() {
        // A cancelled coroutine used to sit in a blocking `execute()` until
        // the server answered, and then threw the answer away. The account
        // wipe and WorkManager's stop both rely on cancellation meaning the
        // write stops.
        val arrived = CountDownLatch(1)
        dispatcher
            .on(".md", MockResponse().setHeadersDelay(30, TimeUnit.SECONDS).setResponseCode(204), method = "PUT")
            .whileInFlight(".md") { arrived.countDown() }

        runBlocking {
            env.seedPage(id = 12, bodyMd = "server body", bodyEtag = "etag-1")
            val save = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                env.bodyService.saveBody(
                    collectivePath = IntegrationEnvironment.COLLECTIVE_PATH,
                    filePath = "",
                    fileName = "Page 12.md",
                    body = "my text",
                    baseEtag = "etag-1",
                )
            }
            assertTrue("the PUT should reach the server", arrived.await(10, TimeUnit.SECONDS))
            save.cancel()
            // Well inside the 30 s the server would take to answer.
            withTimeout(5_000) { save.join() }
            assertTrue(save.isCancelled)
        }
    }
}
