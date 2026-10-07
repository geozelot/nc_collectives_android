package com.megamaced.nccollectives.data.auth

import com.megamaced.nccollectives.data.ServerStringValidation
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.CloudUserService
import com.megamaced.nccollectives.data.api.apiCall
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D12: the user id the signed-in account's WebDAV paths are built from.
 *
 * WebDAV addresses a user's files as `remote.php/dav/files/<user id>/`, and
 * the login name Login Flow hands back is not always that id: an email
 * address, or an LDAP attribute mapped to a different internal id, signs in
 * fine and then finds every file request answered 404. The id is asked of
 * the server once per account and stored with it.
 *
 * Asked lazily, on the first WebDAV call, rather than at sign-in, so that
 * accounts signed in before this existed are covered too, and so that no
 * WebDAV call — the edit flush's especially, which parks an edit as a
 * conflict on a 404 — can go out ahead of it.
 */
@Singleton
class DavUserResolver
    @Inject
    constructor(
        private val tokenStore: TokenStore,
        private val cloudUser: CloudUserService,
    ) {
        private val mutex = Mutex()

        /** When the last attempt failed; until [RETRY_AFTER_MS] later, don't ask again. */
        @Volatile
        private var lastFailureAt = Long.MIN_VALUE / 2

        /**
         * The active account's WebDAV user id: stored, or asked of the
         * server and stored. Null when it isn't known and can't be found out
         * right now — offline, or a server that won't say — and the caller
         * falls back to the login name, the same thing on most servers.
         */
        suspend fun davUserId(): String? {
            tokenStore.getCredentials()?.davUserId?.let { return it }
            return mutex.withLock {
                tokenStore.getCredentials()?.davUserId?.let { return@withLock it }
                if (System.currentTimeMillis() - lastFailureAt < RETRY_AFTER_MS) return@withLock null
                val accountId = tokenStore.activeAccountId() ?: return@withLock null
                val id = when (val result = apiCall { cloudUser.currentUser() }) {
                    is ApiResult.Success -> ServerStringValidation.cleanPathSegment(result.data.ocs.data.id)
                    else -> null
                }
                when {
                    id == null -> {
                        Timber.w("Couldn't learn the WebDAV user id; using the login name for now")
                        lastFailureAt = System.currentTimeMillis()
                        null
                    }

                    // The account changed while the request was out, so the
                    // answer may be the next account's. Ask again next time.
                    tokenStore.activeAccountId() != accountId -> {
                        null
                    }

                    else -> {
                        tokenStore.recordDavUserId(accountId, id)
                        id
                    }
                }
            }
        }

        private companion object {
            const val RETRY_AFTER_MS = 60_000L
        }
    }
