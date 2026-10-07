package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.AuthInterceptor
import com.megamaced.nccollectives.data.api.PageBodyService
import com.megamaced.nccollectives.data.auth.AccountSummary
import com.megamaced.nccollectives.data.auth.AccountSwitcher
import com.megamaced.nccollectives.data.auth.AppPasswordRevoker
import com.megamaced.nccollectives.data.auth.AuthState
import com.megamaced.nccollectives.data.auth.LocalDataWiper
import com.megamaced.nccollectives.data.auth.SessionManager
import com.megamaced.nccollectives.data.auth.accountIdOf
import com.megamaced.nccollectives.data.db.entity.AttachmentEntity
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.share.SharePayloadHolder
import io.mockk.every
import io.mockk.verify
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
 * What a run of 401s does to the work on the device.
 *
 * A credential the server rejects twice in a row used to take the account
 * off the device by the route a user-initiated removal takes: wipe Room, the
 * edit queue, every conflict draft and every staged upload, and drop the
 * credential. The user is asked about none of that. The 401s can also come
 * from something other than a revoked app password: an LDAP or SSO backend
 * that is briefly down, a proxy that strips `Authorization`, a domain move.
 * A user-initiated switch warns about pending edits first (`pendingEditCount`);
 * an expiry didn't.
 *
 * Built from the real `SessionManager`, `AccountSwitcher`, `LocalDataWiper`
 * and `AuthInterceptor` over the harness's Room and MockWebServer. Only
 * `TokenStore` is a mock, for the reason `IntegrationEnvironment` gives.
 */
@RunWith(AndroidJUnit4::class)
class SessionExpiryIntegrationTest {
    private lateinit var env: IntegrationEnvironment
    private lateinit var dispatcher: RoutingDispatcher
    private lateinit var sessionManager: SessionManager
    private lateinit var accountSwitcher: AccountSwitcher
    private lateinit var bodyService: PageBodyService

    @Before
    fun setUp() {
        env = IntegrationEnvironment.create()
        dispatcher = RoutingDispatcher()
        env.server.dispatcher = dispatcher

        val account = AccountSummary(id = accountId(), host = env.host, loginName = IntegrationEnvironment.LOGIN_NAME)
        every { env.tokenStore.accounts() } returns listOf(account)
        every { env.tokenStore.activeAccountId() } returns account.id
        every { env.tokenStore.removeAccount(any()) } returns null
        every { env.tokenStore.upsertAndActivate(any(), any(), any()) } returns account.id

        lateinit var switcher: AccountSwitcher
        sessionManager = SessionManager(env.tokenStore) { switcher }
        // The harness's client signs with a mocked SessionManager; this one
        // needs the real one in the chain, since that's what counts the 401s.
        val client = env.client
            .newBuilder()
            .apply {
                interceptors().replaceAll { interceptor ->
                    if (interceptor is AuthInterceptor) AuthInterceptor(env.tokenStore, sessionManager) else interceptor
                }
            }.build()
        val wiper = LocalDataWiper(
            context = env.context,
            database = env.db,
            syncScheduler = env.syncScheduler,
            userPreferences = UserPreferences(env.context),
            okHttpClient = client,
            accountGeneration = env.accountGeneration,
        )
        switcher = AccountSwitcher(
            sessionManager = sessionManager,
            tokenStore = env.tokenStore,
            localDataWiper = wiper,
            syncScheduler = env.syncScheduler,
            sharePayloadHolder = SharePayloadHolder(),
            database = env.db,
            appPasswordRevoker = AppPasswordRevoker(client),
        )
        accountSwitcher = switcher
        bodyService = PageBodyService(client, env.tokenStore)
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun twoRejectedRequests_askForASignInAndKeepEveryUnsyncedRow() =
        runTest {
            seedUnsyncedWork()
            dispatcher.on("/remote.php/dav", OcsResponses.webDav(401))

            repeat(2) { fetchSomething() }

            val state = sessionManager.authState.value
            assertTrue("expected a re-auth prompt, was $state", state is AuthState.ReauthRequired)
            assertEquals(accountId(), (state as AuthState.ReauthRequired).account.id)
            assertUnsyncedWorkIntact()
            verify(exactly = 0) { env.tokenStore.removeAccount(any()) }
        }

    @Test
    fun whileASignInIsRequired_theRejectedCredentialIsNotSentAgain() =
        runTest {
            dispatcher.on("/remote.php/dav", OcsResponses.webDav(401))
            repeat(2) { fetchSomething() }

            fetchSomething()

            val last = dispatcher.requests.last()
            assertNull(
                "a credential the server has rejected twice must not keep going out " +
                    "(brute-force protection counts every attempt)",
                last.getHeader("Authorization"),
            )
        }

    @Test
    fun signingInAgainToTheSameAccount_resumesWithTheQueueIntact() =
        runTest {
            seedUnsyncedWork()
            dispatcher.on("/remote.php/dav", OcsResponses.webDav(401))
            repeat(2) { fetchSomething() }

            accountSwitcher.signInTo(host = env.host, loginName = IntegrationEnvironment.LOGIN_NAME, appPassword = "fresh")

            assertEquals(AuthState.Authenticated, sessionManager.authState.value)
            verify { env.tokenStore.upsertAndActivate(env.host, IntegrationEnvironment.LOGIN_NAME, "fresh") }
            assertUnsyncedWorkIntact()

            dispatcher.on("/remote.php/dav", OcsResponses.webDav(200, etag = "e"))
            fetchSomething()
            assertNotNull("the session is live again", dispatcher.requests.last().getHeader("Authorization"))
        }

    @Test
    fun signingInAsSomebodyElseFromThePrompt_isRefusedAndWipesNothing() =
        runTest {
            // From the prompt, a different account would be a switch, and a
            // switch wipes the very queue the prompt promised to keep.
            seedUnsyncedWork()
            dispatcher.on("/remote.php/dav", OcsResponses.webDav(401))
            repeat(2) { fetchSomething() }

            val accepted = accountSwitcher.signInTo(host = env.host, loginName = "bob", appPassword = "bobs")

            assertFalse(accepted)
            assertTrue(sessionManager.authState.value is AuthState.ReauthRequired)
            verify(exactly = 0) { env.tokenStore.upsertAndActivate(any(), any(), any()) }
            assertUnsyncedWorkIntact()
        }

    @Test
    fun tryingAgain_resumesWithTheStoredCredential() =
        runTest {
            // A transient outage rather than a revoked password: the stored
            // credential is still good once the backend is back.
            dispatcher.on("/remote.php/dav", OcsResponses.webDav(401))
            repeat(2) { fetchSomething() }

            accountSwitcher.retryExpiredSession()

            assertEquals(AuthState.Authenticated, sessionManager.authState.value)
            dispatcher.on("/remote.php/dav", OcsResponses.webDav(200, etag = "e"))
            val result = fetchSomething()
            assertTrue("was $result", result is ApiResult.Success)
            assertNotNull(dispatcher.requests.last().getHeader("Authorization"))
        }

    private suspend fun fetchSomething() =
        bodyService.fetchBody(
            collectivePath = IntegrationEnvironment.COLLECTIVE_PATH,
            filePath = "",
            fileName = "Page.md",
        )

    private suspend fun seedUnsyncedWork() {
        env.seedPage(id = PAGE, bodyMd = "# server copy", bodyEtag = "etag-1", draftBodyMd = "# conflict draft")
        env.db.editQueueDao().upsert(
            EditQueueEntity(pageId = PAGE, baseEtag = "etag-1", newBodyMd = "# offline edit", queuedAt = 1L, status = "PENDING"),
        )
        env.seedStagedUpload(pageId = PAGE, fileName = "photo.jpg", bytes = "the only copy".toByteArray())
    }

    private suspend fun assertUnsyncedWorkIntact() {
        assertEquals("# offline edit", env.db.editQueueDao().pendingBody(PAGE))
        assertEquals(
            "# conflict draft",
            env.db
                .pageDao()
                .getById(PAGE)
                ?.draftBodyMd,
        )
        assertEquals(
            AttachmentEntity.STATUS_PENDING,
            env.db
                .attachmentDao()
                .getById(AttachmentEntity.key(PAGE, "photo.jpg"))
                ?.status,
        )
        assertTrue(env.stagedFile(pageId = PAGE, fileName = "photo.jpg").exists())
    }

    private fun accountId() = accountIdOf(env.host, IntegrationEnvironment.LOGIN_NAME)

    private companion object {
        const val PAGE = 41L
    }
}
