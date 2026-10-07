package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.auth.AccountSummary
import com.megamaced.nccollectives.data.auth.AccountSwitcher
import com.megamaced.nccollectives.data.auth.AppPasswordRevoker
import com.megamaced.nccollectives.data.auth.LocalDataWiper
import com.megamaced.nccollectives.data.auth.SessionManager
import com.megamaced.nccollectives.data.auth.StoredCredentials
import com.megamaced.nccollectives.data.auth.TokenStore
import com.megamaced.nccollectives.share.SharePayloadHolder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** S-28: removing an account retires its app password on the server. */
@RunWith(AndroidJUnit4::class)
class AccountRemovalTest {
    private lateinit var env: IntegrationEnvironment
    private val tokenStore = mockk<TokenStore>(relaxed = true)
    private val revoker = mockk<AppPasswordRevoker>()
    private val bob = StoredCredentials(host = "https://b.example", loginName = "bob", appPassword = "b-pass")

    @Before
    fun setUp() {
        env = IntegrationEnvironment.create()
        every { tokenStore.accounts() } returns listOf(
            AccountSummary(id = "alice@https://a.example", host = "https://a.example", loginName = "alice"),
            AccountSummary(id = "bob@https://b.example", host = "https://b.example", loginName = "bob"),
        )
        every { tokenStore.activeAccountId() } returns "alice@https://a.example"
        every { tokenStore.credentialsFor("bob@https://b.example") } returns bob
        coEvery { revoker.revoke(any()) } returns true
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun removingAnInactiveAccount_revokesItsOwnAppPassword() {
        val switcher = AccountSwitcher(
            sessionManager = mockk<SessionManager>(relaxed = true),
            tokenStore = tokenStore,
            localDataWiper = mockk<LocalDataWiper>(relaxed = true),
            syncScheduler = env.syncScheduler,
            sharePayloadHolder = SharePayloadHolder(),
            database = env.db,
            appPasswordRevoker = revoker,
        )

        switcher.removeAccount("bob@https://b.example")

        coVerify(timeout = 2_000) { revoker.revoke(bob) }
        verify { tokenStore.removeAccount("bob@https://b.example") }
    }
}
