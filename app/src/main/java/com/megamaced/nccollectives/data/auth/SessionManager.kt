package com.megamaced.nccollectives.data.auth

import dagger.Lazy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthState {
    data object Unknown : AuthState

    data object Authenticated : AuthState

    data object Unauthenticated : AuthState

    /**
     * An account switch is in progress (issue #14). Distinct from
     * [Unauthenticated] because the user has not signed out and must not be
     * shown the login screen; distinct from [Unknown] because the scaffold
     * says so rather than showing a bare spinner.
     *
     * Load-bearing as well as cosmetic: the scaffold unmounts the whole
     * authenticated host on this state, which tears down every Room flow
     * observer *before* `AccountSwitcher` wipes the tables underneath them
     * — the same ordering `LogoutHandler` relies on.
     */
    data object Switching : AuthState

    /**
     * B-92: the server rejected [account]'s credential, and the user hasn't
     * signed in again yet.
     *
     * Not [Unauthenticated]: the account, its credential and everything
     * cached for it stay on the device. That includes the edit queue,
     * conflict drafts and staged uploads, which exist nowhere else. The
     * scaffold unmounts the authenticated host on this state, as it does on
     * [Switching], and asks for a fresh sign-in to the same account. That
     * goes through `AccountSwitcher.signInTo`'s same-account path, which
     * keeps the cache.
     */
    data class ReauthRequired(
        val account: AccountSummary,
    ) : AuthState
}

@Singleton
class SessionManager
    @Inject
    constructor(
        private val tokenStore: TokenStore,
        /**
         * `Lazy` breaks the construction cycle: the handler is
         * `AccountSwitcher`, which needs this class. Resolved on the first
         * expiry, from an OkHttp thread — `dagger.Lazy` is safe there, and
         * the same idiom the `Application` uses for the `OkHttpClient`.
         */
        private val expiredSessionHandler: Lazy<ExpiredSessionHandler>,
    ) {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
        val authState: StateFlow<AuthState> = _authState.asStateFlow()

        private val _accounts = MutableStateFlow<List<AccountSummary>>(emptyList())

        /** Every account on the device. Drives the switcher in Settings. */
        val accounts: StateFlow<List<AccountSummary>> = _accounts.asStateFlow()

        private val _activeAccountId = MutableStateFlow<String?>(null)
        val activeAccountId: StateFlow<String?> = _activeAccountId.asStateFlow()

        /**
         * Set while [LogoutHandler] is wiping local state, or while
         * `AccountSwitcher` is swapping accounts. Suppresses
         * `AuthInterceptor`'s 401-driven sign-out, so any in-flight
         * `SyncWorker` / `EditFlushWorker` requests that race with the wipe
         * don't trigger a second (concurrent) sign-out cycle.
         */
        private val sessionChangeInProgress = AtomicBoolean(false)

        /**
         * Decides when a run of 401s means the active account's credential
         * is dead. See [AuthFailureTracker] — the policy lives there so it
         * can be tested without `EncryptedSharedPreferences` underneath it.
         */
        private val authFailures = AuthFailureTracker()

        /**
         * B-92: the account whose credential the server rejected, until the
         * user signs in again, retries, or the active account changes.
         * Written from an OkHttp thread, read wherever [refreshState] runs.
         *
         * Deliberately not persisted. A cold start retries the stored
         * credential, which is also how a transient outage (an LDAP backend
         * restarting, say) clears itself without the user doing anything.
         */
        @Volatile
        private var reauthAccountId: String? = null

        init {
            refreshState()
        }

        fun refreshState() {
            val accounts = tokenStore.accounts()
            val activeId = tokenStore.activeAccountId()
            _accounts.value = accounts
            _activeAccountId.value = activeId
            val needsReauth = reauthAccountId
                ?.takeIf { it == activeId }
                ?.let { id -> accounts.firstOrNull { it.id == id } }
            _authState.value = when {
                tokenStore.getCredentials() == null -> AuthState.Unauthenticated
                needsReauth != null -> AuthState.ReauthRequired(needsReauth)
                else -> AuthState.Authenticated
            }
        }

        /**
         * B-92: the server has rejected [accountId]'s credential. Ask the user
         * to sign in again instead of removing the account. No-op unless it
         * is still the active account, because the streak is detected on an
         * OkHttp thread and a switch can land first.
         */
        fun requireReauthentication(accountId: String) {
            if (accountId != tokenStore.activeAccountId()) return
            reauthAccountId = accountId
            refreshState()
        }

        /**
         * B-92: try the stored credential again, for when the 401s were a
         * passing outage rather than a revoked app password. A credential
         * that really is dead fails twice more and lands back in
         * [AuthState.ReauthRequired].
         */
        fun retryAuthentication() {
            reauthAccountId = null
            authFailures.reset()
            refreshState()
        }

        /** Called from [LogoutHandler] before it touches local state. */
        fun beginSignOut() {
            sessionChangeInProgress.set(true)
            _authState.value = AuthState.Unauthenticated
        }

        /** Called from [LogoutHandler] once the local wipe is complete. */
        fun endSignOut() {
            tokenStore.clear()
            reauthAccountId = null
            authFailures.reset()
            sessionChangeInProgress.set(false)
            refreshState()
        }

        /**
         * Called from `AccountSwitcher` before it wipes the outgoing
         * account's cache. Unlike [beginSignOut] this must not flip to
         * [AuthState.Unauthenticated]: the user has not signed out, and a
         * flash of the login screen mid-switch reads as one.
         */
        fun beginAccountSwitch() {
            sessionChangeInProgress.set(true)
            _authState.value = AuthState.Switching
        }

        /**
         * Called from `AccountSwitcher` once the incoming account is active.
         * Re-derives the state from the store, so this lands on
         * [AuthState.Unauthenticated] when the switch was really the removal
         * of the last account.
         */
        fun endAccountSwitch() {
            reauthAccountId = null
            authFailures.reset()
            sessionChangeInProgress.set(false)
            refreshState()
        }

        /**
         * Persist a freshly obtained credential and make it the live one.
         *
         * Only correct as the *cold* sign-in path — there is no cached data
         * to wipe when nothing was signed in. Adding a second account, or
         * re-authenticating while another account's cache is loaded, goes
         * through `AccountSwitcher.signInTo`, which owns the wipe decision.
         */
        fun onLoginSuccess(
            host: String,
            loginName: String,
            appPassword: String,
        ) {
            tokenStore.upsertAndActivate(host, loginName, appPassword)
            reauthAccountId = null
            authFailures.reset()
            sessionChangeInProgress.set(false)
            refreshState()
        }

        /**
         * Record a response from an authenticated request. Once a run of
         * 401s says the active account's credential is dead, hand it to
         * [ExpiredSessionHandler]. Silently no-ops while a sign-out or
         * account switch is already in progress.
         *
         * Called from [com.megamaced.nccollectives.data.api.AuthInterceptor].
         *
         * Issue #19: this used to call a local `logout()` — `beginSignOut()`
         * + `endSignOut()` with nothing between them — which wiped no local
         * data and cleared the credentials of every account rather than the
         * one the server had rejected. `AuthInterceptor` only ever attaches
         * the *active* account's credential, so a 401 streak is attributable
         * to exactly one account, and that is the only one that should pay
         * for it.
         */
        fun onAuthenticatedResponse(code: Int) {
            if (sessionChangeInProgress.get()) return
            if (!authFailures.onResponse(code)) return
            val expired = tokenStore.activeAccountId()
            if (expired == null) {
                // Nothing to remove — the store emptied under us. Just make
                // sure the observed state agrees with it.
                refreshState()
                return
            }
            Timber.i("Server rejected the active account's credential; expiring it")
            expiredSessionHandler.get().onSessionExpired(expired)
        }
    }
