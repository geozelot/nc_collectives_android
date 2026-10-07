package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.auth.StoredCredentials
import io.mockk.every
import io.mockk.just
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D12: WebDAV addresses a user's files by their user id, and the app built
 * the path from the login name instead. The two are the same on most
 * servers — but not for anyone who signs in with an email address, or
 * through LDAP with a different internal id, and for them every body and
 * attachment request 404'd. A queued edit was worse off still: 404 is
 * terminal for the flush, so it was parked as a conflict on its first try.
 */
@RunWith(AndroidJUnit4::class)
class WebDavUserIdIntegrationTest {
    private lateinit var env: IntegrationEnvironment
    private lateinit var dispatcher: RoutingDispatcher

    @Before
    fun setUp() {
        env = IntegrationEnvironment.create()
        dispatcher = RoutingDispatcher()
        env.server.dispatcher = dispatcher
        every { env.tokenStore.activeAccountId() } returns ACCOUNT
        every { env.tokenStore.recordDavUserId(any(), any()) } just runs
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun anEmailLogin_reachesItsFilesByTheUserId() =
        runTest {
            signedInAs(loginName = "alice@example.com", davUserId = null)
            env.seedPage(id = 17)
            dispatcher
                .on("/cloud/user", OcsResponses.envelope("""{"id":"alice","displayname":"Alice"}"""), method = "GET")
                .on("/remote.php/dav/files/alice/", OcsResponses.webDav(200, etag = "\"e1\"").setBody("# body"), method = "GET")

            val result = env.pageRepository.fetchBody(17)

            assertTrue("was $result", result is ApiResult.Success)
            assertTrue(dispatcher.requestsTo("alice%40example.com").isEmpty())
            verify { env.tokenStore.recordDavUserId(ACCOUNT, "alice") }
        }

    @Test
    fun aKnownUserId_isNotAskedForAgain() =
        runTest {
            signedInAs(loginName = "alice@example.com", davUserId = "alice")
            env.seedPage(id = 17)
            dispatcher.on("/remote.php/dav/files/alice/", OcsResponses.webDav(200, etag = "\"e1\"").setBody("# body"), method = "GET")

            env.pageRepository.fetchBody(17)

            assertTrue(dispatcher.requestsTo("/cloud/user").isEmpty())
        }

    @Test
    fun aServerThatWontSay_fallsBackToTheLoginNameAndIsNotAskedOnEveryCall() =
        runTest {
            signedInAs(loginName = "alice", davUserId = null)
            env.seedPage(id = 17)
            dispatcher
                .on("/cloud/user", OcsResponses.webDav(500), method = "GET")
                .on("/remote.php/dav/files/alice/", OcsResponses.webDav(200, etag = "\"e1\"").setBody("# body"), method = "GET")

            assertTrue(env.pageRepository.fetchBody(17) is ApiResult.Success)
            assertTrue(env.pageRepository.fetchBody(17) is ApiResult.Success)

            assertEquals(1, dispatcher.requestsTo("/cloud/user").size)
        }

    private fun signedInAs(
        loginName: String,
        davUserId: String?,
    ) {
        every { env.tokenStore.getCredentials() } returns
            StoredCredentials(host = env.host, loginName = loginName, appPassword = "x", davUserId = davUserId)
    }

    private companion object {
        const val ACCOUNT = "alice@example.com@localhost"
    }
}
