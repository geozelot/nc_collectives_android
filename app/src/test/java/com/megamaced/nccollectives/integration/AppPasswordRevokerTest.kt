package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.auth.AppPasswordRevoker
import com.megamaced.nccollectives.data.auth.StoredCredentials
import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S-28: the app password is retired on the server, signed with its own
 * credential, whichever account is active.
 */
@RunWith(AndroidJUnit4::class)
class AppPasswordRevokerTest {
    private lateinit var env: IntegrationEnvironment
    private lateinit var dispatcher: RoutingDispatcher
    private lateinit var revoker: AppPasswordRevoker

    @Before
    fun setUp() {
        env = IntegrationEnvironment.create()
        dispatcher = RoutingDispatcher()
        env.server.dispatcher = dispatcher
        revoker = AppPasswordRevoker(env.client)
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun revoking_deletesTheAppPasswordWithItsOwnCredential() =
        runTest {
            dispatcher.on("/ocs/v2.php/core/apppassword", OcsResponses.envelope("[]"), method = "DELETE")
            // Not the active account's credential: the harness's active one is
            // "alice"/"app-password", and AuthInterceptor must not swap it in.
            val other = StoredCredentials(host = env.host, loginName = "bob", appPassword = "bobs-password")

            assertTrue(revoker.revoke(other))

            val request = dispatcher.requests.single()
            assertEquals("DELETE", request.method)
            assertEquals(Credentials.basic("bob", "bobs-password"), request.getHeader("Authorization"))
            assertEquals("true", request.getHeader("OCS-APIRequest"))
        }

    @Test
    fun aPasswordTheServerAlreadyRejects_countsAsGone() =
        runTest {
            dispatcher.on("/ocs/v2.php/core/apppassword", OcsResponses.webDav(401), method = "DELETE")

            assertTrue(revoker.revoke(credentials()))
        }

    @Test
    fun anUnreachableServer_isReportedNotThrown() =
        runTest {
            env.server.shutdown()

            assertFalse(revoker.revoke(credentials()))
        }

    @Test
    fun aCleartextHost_isNeverSentThePassword() =
        runTest {
            assertFalse(revoker.revoke(StoredCredentials(host = "http://cleartext.example", loginName = "a", appPassword = "p")))
            assertTrue(dispatcher.requests.isEmpty())
        }

    private fun credentials() =
        StoredCredentials(host = env.host, loginName = IntegrationEnvironment.LOGIN_NAME, appPassword = "app-password")
}
