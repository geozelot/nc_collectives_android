package com.megamaced.nccollectives.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException

/**
 * S4: what [TokenStore] does when the encrypted prefs won't open.
 *
 * Before, the first exception from `EncryptedSharedPreferences.create`
 * deleted the file — every account's credential — on a read, with no second
 * try, even though some OEM Keystores fail transiently. The delete was a
 * `File.delete()` that left the platform's in-memory copy of the prefs in
 * place, so the next open in the same process hit the same unreadable keyset
 * and a later write could put the file back. And `clear()` cleared with
 * `apply()`, so sign-out returned before anything reached the disk.
 *
 * Plain prefs under the store's filename stand in for the encrypted ones —
 * Robolectric has no AndroidKeyStore — so what's under test is the store's
 * own handling of the file, which is where all of the above lived.
 */
@RunWith(AndroidJUnit4::class)
class TokenStoreKeystoreFailureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Opens still to fail, as a Keystore that hasn't come back yet would. */
    private var keystoreFailuresLeft = 0

    @Before
    fun startFromNothing() {
        context.deleteSharedPreferences(TokenStore.PREFS_FILE)
    }

    @Test
    fun aKeystoreErrorThatClearsOnRetryKeepsEveryAccount() {
        seedTwoAccounts()
        keystoreFailuresLeft = 1

        val store = newProcess()

        assertEquals(listOf(ALICE, BOB), store.accounts().map { it.id })
        assertEquals(BOB, store.activeAccountId())
    }

    @Test
    fun aKeystoreThatStaysDownSignsOutForNowButDestroysNothing() {
        seedTwoAccounts()
        keystoreFailuresLeft = Int.MAX_VALUE

        val store = newProcess()

        assertNull(store.getCredentials())
        assertTrue("a read must not delete the credentials", prefsFile().exists())

        // Nothing was cached from the failed open, so the next read tries
        // again — no restart needed once the Keystore is back.
        keystoreFailuresLeft = 0
        assertEquals(listOf(ALICE, BOB), store.accounts().map { it.id })
        assertEquals("pw-b", store.getCredentials()?.appPassword)
    }

    @Test
    fun signingInOverAKeysetThatNoLongerDecryptsStartsAFreshStore() {
        seedTwoAccounts()
        plainPrefs().edit().putString(UNDECRYPTABLE_KEYSET, "x").commit()

        val store = newProcess()
        store.upsertAndActivate("https://c.example", "carol", "pw-c")

        assertEquals(listOf(CAROL), store.accounts().map { it.id })
        assertEquals("pw-c", newProcess().getCredentials()?.appPassword)
    }

    @Test
    fun anUnreadableAccountListIsNotDeletedByAReadAndIsReplacedByTheNextSignIn() {
        plainPrefs().edit().putString("accounts", "{not json").commit()

        val store = newProcess()

        assertNull(store.getCredentials())
        assertTrue("a read must not delete the credentials", prefsFile().exists())

        store.upsertAndActivate("https://c.example", "carol", "pw-c")
        assertEquals(listOf(CAROL), store.accounts().map { it.id })
    }

    @Test
    fun signOutHasRemovedTheFileByTheTimeItReturns() {
        seedTwoAccounts()
        assertTrue(prefsFile().exists())

        newProcess().clear()

        assertFalse("sign-out must not leave the credentials on disk", prefsFile().exists())
        assertNull(newProcess().getCredentials())
        assertTrue(newProcess().accounts().isEmpty())
    }

    /**
     * Stands in for `EncryptedSharedPreferences.create`. A present
     * [UNDECRYPTABLE_KEYSET] is a keyset that no Keystore key will decrypt
     * again — a factory reset of the Keystore, say — and fails every open
     * for as long as it's in the file.
     */
    private fun open(ctx: Context): SharedPreferences {
        if (keystoreFailuresLeft > 0) {
            keystoreFailuresLeft--
            throw KeyStoreException("Keystore operation failed")
        }
        val prefs = ctx.getSharedPreferences(TokenStore.PREFS_FILE, Context.MODE_PRIVATE)
        if (prefs.contains(UNDECRYPTABLE_KEYSET)) throw AEADBadTagException("keyset no longer decrypts")
        return prefs
    }

    /** A fresh [TokenStore]: nothing opened, nothing cached, as after a restart. */
    private fun newProcess() = TokenStore(context, ::open) { }

    private fun seedTwoAccounts() {
        newProcess().apply {
            upsertAndActivate("https://a.example", "alice", "pw-a")
            upsertAndActivate("https://b.example", "bob", "pw-b")
        }
        // On disk, whichever of apply/commit the store wrote with.
        plainPrefs().edit().putString("flushed", "1").commit()
    }

    private fun plainPrefs() = context.getSharedPreferences(TokenStore.PREFS_FILE, Context.MODE_PRIVATE)

    private fun prefsFile() = File(context.dataDir, "shared_prefs/${TokenStore.PREFS_FILE}.xml")

    private companion object {
        const val UNDECRYPTABLE_KEYSET = "test_undecryptable_keyset"
        const val ALICE = "alice@https://a.example"
        const val BOB = "bob@https://b.example"
        const val CAROL = "carol@https://c.example"
    }
}
