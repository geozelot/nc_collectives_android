package com.megamaced.nccollectives.data.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * S-28: revoke an app password on the server before forgetting it here.
 *
 * Signing out and removing an account used to delete the credential from the
 * device and nothing else. The app password stayed valid on the server
 * (listed under Settings → Security → Devices & sessions), so a copy taken
 * earlier, from a backup or a rooted device, still opened the account.
 * `DELETE /ocs/v2.php/core/apppassword` is Nextcloud's own endpoint for a
 * client to retire the password it is authenticating with.
 *
 * Best effort and short. Offline, the password stays valid until the user
 * revokes it in the web UI, and sign-out must not wait on the network to
 * finish. Runs on a derivative of the shared client with its interceptors
 * stripped. `AuthInterceptor` would otherwise sign the request with the
 * *active* account, which for a non-active account's revocation is the wrong
 * credential. The derivative keeps the TLS setup and the connection pool,
 * and follows no redirects.
 */
@Singleton
class AppPasswordRevoker
    @Inject
    constructor(
        sharedClient: OkHttpClient,
    ) {
        private val client: OkHttpClient =
            sharedClient
                .newBuilder()
                .apply {
                    interceptors().clear()
                    networkInterceptors().clear()
                }.followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(REVOKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

        /**
         * Returns true when the server no longer accepts [credentials]: it
         * confirmed the revocation, or answered 401 because the password was
         * already gone. False when it couldn't be reached or refused.
         */
        suspend fun revoke(credentials: StoredCredentials): Boolean {
            val base = credentials.host.trimEnd('/').toHttpUrlOrNull()
            // S-1 holds here too: never send the password over cleartext.
            if (base == null || !base.isHttps) {
                Timber.w("Not revoking an app password for a non-https host")
                return false
            }
            val url = base
                .newBuilder()
                .addPathSegments("ocs/v2.php/core/apppassword")
                .build()
            val request = Request
                .Builder()
                .url(url)
                .delete()
                .header("Authorization", Credentials.basic(credentials.loginName, credentials.appPassword))
                .header("OCS-APIRequest", "true")
                .header("Accept", "application/json")
                .build()
            return withContext(Dispatchers.IO) {
                try {
                    client.newCall(request).execute().use { response ->
                        val gone = response.isSuccessful || response.code == 401
                        if (!gone) Timber.w("App password revocation refused: HTTP %d", response.code)
                        gone
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Couldn't reach the server to revoke an app password")
                    false
                }
            }
        }

        private companion object {
            const val REVOKE_TIMEOUT_SECONDS = 10L
        }
    }
