package com.megamaced.nccollectives.data.auth

import com.megamaced.nccollectives.data.db.NcCollectivesDatabase
import com.megamaced.nccollectives.share.SharePayloadHolder
import com.megamaced.nccollectives.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adding, switching and removing Nextcloud accounts (issue #14).
 *
 * **One account's data is cached at a time.** Switching wipes the outgoing
 * account's local state and re-syncs the incoming one, rather than keeping
 * both in Room simultaneously. The credentials of every account are kept,
 * which is the part the issue actually asked for — swapping accounts
 * without signing out and typing a server URL again.
 *
 * Caching both at once would mean an `accountId` column on every table and
 * a primary-key rewrite: `PageEntity` and `CollectiveEntity` key on the raw
 * *server* id, and two servers will happily both have a page 17. It would
 * also mean two `directediting` sessions sharing the WebView's single
 * cookie jar. Neither is needed to fix the reported problem, so neither is
 * here.
 *
 * The cost is a re-sync on each switch, which is why [pendingEditCount]
 * exists: queued writes that have not reached the server do not survive the
 * wipe, and the user is told how many before they commit to it.
 *
 * Also the [ExpiredSessionHandler]. A credential the server has stopped
 * accepting puts the session into `AuthState.ReauthRequired` and leaves
 * everything on the device (B-92). Whether to sign in again or remove the
 * account is the user's call.
 */
@Singleton
class AccountSwitcher
    @Inject
    constructor(
        private val sessionManager: SessionManager,
        private val tokenStore: TokenStore,
        private val localDataWiper: LocalDataWiper,
        private val syncScheduler: SyncScheduler,
        private val sharePayloadHolder: SharePayloadHolder,
        private val database: NcCollectivesDatabase,
        private val appPasswordRevoker: AppPasswordRevoker,
    ) : ExpiredSessionHandler {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Edits queued locally that the server has not accepted yet. They are
         * lost by the wipe a switch performs, so the confirmation says how
         * many there are — the same care `ManageAccountsViewModel` takes in
         * the Nextcloud Notes client before it drops an account.
         *
         * Counts conflicted rows too: the user has not resolved those either,
         * and losing one silently is the same surprise.
         */
        suspend fun pendingEditCount(): Int = database.editQueueDao().countAll()

        /**
         * Make [accountId] the live account, wiping whatever the previous one
         * had cached.
         *
         * No-ops when it is already active or names no stored account, so a
         * double tap or a switch racing a removal cannot strand the user in
         * [AuthState.Switching].
         */
        fun switchTo(accountId: String) {
            if (accountId == tokenStore.activeAccountId()) return
            if (tokenStore.accounts().none { it.id == accountId }) {
                Timber.w("Ignoring a switch to an account that is no longer stored")
                return
            }
            beginSwitch()
            scope.launch {
                if (!wipeOrAbandon("switching account")) return@launch
                tokenStore.setActiveAccount(accountId)
                finishSwitch()
            }
        }

        /**
         * Complete a login: store the credential, and make it live.
         *
         * The single entry point for *both* the cold sign-in and the "add
         * another account" flow, which is what lets `LoginScreen` stay
         * mode-less. Which of the three things happens is decided from the
         * store, not from a flag the caller passes:
         *
         *  - nothing signed in → no cache to wipe, straight to authenticated.
         *  - re-authenticating the account that is already active → rewrite
         *    the app password and keep the cache. A revoked app password is
         *    re-established without the user losing their offline copy.
         *  - anything else → a switch, with the wipe.
         *
         * Returns false, having changed nothing, for the one sign-in this
         * refuses. B-92: from the re-auth prompt, signing in as anyone but the
         * expired account would be a switch, and a switch wipes the queue the
         * prompt just promised to keep. That account can still be added after
         * the expired one is signed in again or removed.
         */
        fun signInTo(
            host: String,
            loginName: String,
            appPassword: String,
        ): Boolean {
            val incomingId = accountIdOf(host, loginName)
            val activeId = tokenStore.activeAccountId()
            val reauth = sessionManager.authState.value as? AuthState.ReauthRequired
            if (reauth != null && reauth.account.id != incomingId) {
                Timber.w("Refusing a sign-in to a different account from the re-auth prompt")
                return false
            }
            if (activeId == null || activeId == incomingId) {
                sessionManager.onLoginSuccess(host, loginName, appPassword)
                resumeSession()
                return true
            }
            beginSwitch()
            scope.launch {
                if (!wipeOrAbandon("adding an account")) return@launch
                tokenStore.upsertAndActivate(host, loginName, appPassword)
                finishSwitch()
            }
            return true
        }

        /**
         * Forget one account, keeping the others.
         *
         * Removing an account that is not the active one costs nothing —
         * none of its data is on the device. Removing the active one is a
         * switch to whichever account remains, or a sign-out if it was the
         * last.
         */
        fun removeAccount(accountId: String) = remove(accountId, what = "removing an account")

        /**
         * The server has stopped accepting this account's credential. Causes
         * include an app password revoked in Nextcloud's security settings, a
         * disabled user, a password change, or something that only looks like
         * one: an LDAP or SSO backend that is briefly down, or a proxy
         * stripping `Authorization`.
         *
         * B-92: ask for a fresh sign-in, and touch nothing. This used to be a
         * user-initiated [removeAccount] in all but name. It wiped the edit
         * queue, every conflict draft and every staged upload, all of which
         * exist only on this device, without the [pendingEditCount] warning a
         * real removal shows. Signing in again to the same account goes
         * through [signInTo]'s same-account path, which keeps the cache.
         * Removal is still on offer from the prompt, as an explicit choice.
         *
         * Issue #19 still holds: only this account is affected.
         * `AuthInterceptor` only ever attaches the active account's
         * credential, so the streak of 401s is attributable to it, and the
         * other stored accounts have done nothing wrong.
         */
        override fun onSessionExpired(accountId: String) {
            sessionManager.requireReauthentication(accountId)
        }

        /**
         * B-92: the user asked to try the stored credential again from the
         * re-auth prompt, because the 401s may have been an outage rather
         * than a revoked app password.
         */
        fun retryExpiredSession() {
            sessionManager.retryAuthentication()
            resumeSession()
        }

        /**
         * Restart the work a live session does, after a cold sign-in, a
         * re-authentication or a retry.
         *
         * The periodic job is cancelled by every wipe and only scheduled from
         * `Application.onCreate`, so without this a sign-in later in the same
         * process leaves background sync switched off until the app is next
         * cold-started. B-92: the flushes too. Both workers settle a 401 by
         * leaving their rows `PENDING` and finishing, so nothing would send
         * the queue that waited out an expiry until the app next came to the
         * foreground. During a re-auth it already is in the foreground.
         */
        private fun resumeSession() {
            syncScheduler.reschedulePeriodic()
            syncScheduler.syncNow()
            syncScheduler.flushEditsWhenOnline()
            syncScheduler.flushAttachmentUploadsWhenOnline()
        }

        private fun remove(
            accountId: String,
            what: String,
        ) {
            if (tokenStore.accounts().none { it.id == accountId }) return
            // S-28: retire the app password on the server too, best effort,
            // with the credential read before it is forgotten here.
            tokenStore.credentialsFor(accountId)?.let { credential ->
                scope.launch { appPasswordRevoker.revoke(credential) }
            }
            if (accountId != tokenStore.activeAccountId()) {
                // None of this account's data is on the device, so there is
                // nothing to wipe and no reason to disturb the live session.
                tokenStore.removeAccount(accountId)
                sessionManager.refreshState()
                return
            }
            beginSwitch()
            scope.launch {
                if (!wipeOrAbandon(what)) return@launch
                val nextActive = tokenStore.removeAccount(accountId)
                if (nextActive == null) {
                    // That was the last account. `endAccountSwitch` re-derives
                    // the state from an empty store, which lands on
                    // `Unauthenticated` and shows the login screen.
                    Timber.i("Removed the last account; signing out")
                }
                finishSwitch()
            }
        }

        /**
         * Run the wipe, and put the session back the way it was if it fails.
         *
         * The alternative — carrying on to activate the incoming account —
         * would leave the outgoing account's pages, attachments and queued
         * writes in Room while the app is authenticated as somebody else,
         * which is precisely the cross-account leak the wipe exists to
         * prevent. Abandoning keeps the invariant "whatever is cached belongs
         * to the active account", because the active account never changed.
         *
         * Returns true when the caller should carry on. Failure here is
         * pathological — a corrupted database, a full disk — so the recovery
         * is to stay put and log rather than to build a UI for it; the user
         * sees the switch simply not happen.
         */
        private suspend fun wipeOrAbandon(what: String): Boolean {
            val result = runCatching { localDataWiper.wipe(keepDevicePreferences = true) }
            result.exceptionOrNull()?.let { failure ->
                Timber.e(failure, "Local wipe failed while %s; staying on the current account", what)
                // Re-derives from the store, which still names the outgoing
                // account, so this lands back on `Authenticated`.
                sessionManager.endAccountSwitch()
                return false
            }
            return true
        }

        private fun beginSwitch() {
            sessionManager.beginAccountSwitch()
            // S-16, restated for switching: a share intent captured against
            // the outgoing account must not be replayed into the incoming
            // one's Nextcloud.
            sharePayloadHolder.discard()
        }

        private fun finishSwitch() {
            sessionManager.endAccountSwitch()
            if (tokenStore.activeAccountId() == null) return
            // Nothing is cached for the incoming account, so the app would
            // otherwise open on an empty collective list until the next
            // foreground or scheduled sync.
            syncScheduler.reschedulePeriodic()
            syncScheduler.syncNow()
        }
    }
