package com.megamaced.nccollectives.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D12: the user id WebDAV paths are built from is stored with the account
 * it belongs to, once the server has said what it is.
 */
@RunWith(AndroidJUnit4::class)
class TokenStoreDavUserIdTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun startFromNothing() {
        context.deleteSharedPreferences(TokenStore.PREFS_FILE)
    }

    @Test
    fun aRecordedUserId_isServedWithThatAccountsCredentials() {
        val store = newProcess()
        val alice = store.upsertAndActivate("https://a.example", "alice@example.com", "pw")

        store.recordDavUserId(alice, "alice")

        assertEquals("alice", newProcess().getCredentials()?.davUserId)
    }

    @Test
    fun signingInAgain_keepsTheUserId() {
        val store = newProcess()
        val alice = store.upsertAndActivate("https://a.example", "alice@example.com", "pw")
        store.recordDavUserId(alice, "alice")

        store.upsertAndActivate("https://a.example", "alice@example.com", "new-pw")

        assertEquals("alice", store.getCredentials()?.davUserId)
        assertEquals("new-pw", store.getCredentials()?.appPassword)
    }

    @Test
    fun aUserIdForAnotherAccount_isNotServedForTheActiveOne() {
        val store = newProcess()
        val alice = store.upsertAndActivate("https://a.example", "alice@example.com", "pw")
        store.upsertAndActivate("https://b.example", "bob", "pw")

        store.recordDavUserId(alice, "alice")

        assertNull(store.getCredentials()?.davUserId)
        store.setActiveAccount(alice)
        assertEquals("alice", store.getCredentials()?.davUserId)
    }

    @Test
    fun recordingForAnAccountThatIsGone_changesNothing() {
        val store = newProcess()
        store.upsertAndActivate("https://a.example", "alice", "pw")

        store.recordDavUserId("nobody@https://x.example", "nobody")

        assertEquals(1, store.accounts().size)
        assertNull(store.getCredentials()?.davUserId)
    }

    private fun newProcess() =
        TokenStore(
            context,
            { it.getSharedPreferences(TokenStore.PREFS_FILE, Context.MODE_PRIVATE) },
            { },
        )
}
