package com.megamaced.nccollectives.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.auth.AccountSwitcher
import com.megamaced.nccollectives.data.auth.LoginFlowInitResponse
import com.megamaced.nccollectives.data.auth.LoginFlowStatus
import com.megamaced.nccollectives.data.auth.NextcloudLoginFlow
import com.megamaced.nccollectives.data.auth.isSameServerHttpsUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val hostInput: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val loginUrl: String? = null,
    val isPolling: Boolean = false,
    val loginSuccess: Boolean = false,
)

@HiltViewModel
class LoginViewModel
    @Inject
    constructor(
        private val loginFlow: NextcloudLoginFlow,
        private val accountSwitcher: AccountSwitcher,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(LoginUiState())
        val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

        fun onHostChanged(host: String) {
            _uiState.update { it.copy(hostInput = host, error = null) }
        }

        fun startLogin() {
            val host = _uiState.value.hostInput.trim()
            if (host.isBlank()) {
                _uiState.update { it.copy(error = "Enter your Nextcloud server URL") }
                return
            }

            // S-1: refuse `http://` outright. App-password Basic-auth over
            // cleartext is exfil bait on any shared network; the manifest's
            // `network_security_config.xml` also denies cleartext at the
            // platform level, but we surface a clear error here rather than
            // letting the underlying connection fail confusingly.
            if (host.startsWith("http://", ignoreCase = true)) {
                _uiState.update {
                    it.copy(error = "HTTPS is required — drop the http:// prefix.")
                }
                return
            }
            val normalisedHost = if (!host.startsWith("https://", ignoreCase = true)) {
                "https://$host"
            } else {
                host
            }

            _uiState.update { it.copy(isLoading = true, error = null) }

            viewModelScope.launch {
                // B-44: `loginFlow.initiate` is now `suspend` and owns its
                // own `Dispatchers.IO` switch + Response.use {}.
                val result = loginFlow.initiate(normalisedHost)
                result.fold(
                    onSuccess = { initResponse -> onFlowInitiated(initResponse, normalisedHost) },
                    onFailure = { e ->
                        _uiState.update {
                            it.copy(isLoading = false, error = loginFailureMessage(e))
                        }
                    },
                )
            }
        }

        private fun onFlowInitiated(
            initResponse: LoginFlowInitResponse,
            expectedHost: String,
        ) {
            // S-26: `login` goes to a Custom Tab and `poll.endpoint` is
            // POSTed to, both server-supplied and both used before `poll()`
            // gets to apply its own S-17 check to the returned `server`
            // field — by which point the user has already been shown a login
            // page and typed a password into it. Hold them to the same rule
            // as `server`, and fail before the tab opens.
            val serverSuppliedUrls = listOf(initResponse.login, initResponse.poll.endpoint)
            if (serverSuppliedUrls.any { !isSameServerHttpsUrl(it, expectedHost) }) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Server returned a different host than the one you entered " +
                            "($expectedHost). Refusing to continue.",
                    )
                }
                return
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    loginUrl = initResponse.login,
                    isPolling = true,
                )
            }

            pollJob?.cancel()
            pollJob = viewModelScope.launch {
                // S-17: pass `expectedHost` so a server returning a different
                // canonical host than the user typed gets rejected before its
                // credentials are persisted.
                val status = loginFlow.poll(
                    endpoint = initResponse.poll.endpoint,
                    token = initResponse.poll.token,
                    expectedHost = expectedHost,
                )
                when (status) {
                    is LoginFlowStatus.Success -> {
                        // `signInTo` and not `SessionManager.onLoginSuccess`:
                        // this same screen is also the "add another account"
                        // flow (issue #14), and only the switcher knows
                        // whether there is an outgoing account's cache to
                        // wipe first. Deciding it there rather than from a
                        // mode flag is what keeps this screen mode-less.
                        val accepted = accountSwitcher.signInTo(
                            host = status.result.server,
                            loginName = status.result.loginName,
                            appPassword = status.result.appPassword,
                        )
                        _uiState.update {
                            if (accepted) {
                                it.copy(isPolling = false, loginSuccess = true)
                            } else {
                                // B-92: the re-auth prompt, answered as somebody else.
                                it.copy(
                                    isPolling = false,
                                    error = "That signed in as ${status.result.loginName}. Sign in as the account " +
                                        "shown to keep its offline changes, or remove it first.",
                                )
                            }
                        }
                    }

                    is LoginFlowStatus.Error -> {
                        _uiState.update {
                            it.copy(isPolling = false, error = status.message)
                        }
                    }
                }
            }
        }

        /** U13: the sign-in page couldn't be shown because the device has no browser. */
        fun onBrowserUnavailable() {
            _uiState.update {
                it.copy(
                    loginUrl = null,
                    error = "No browser on this device can show the Nextcloud sign-in page. Install one and try again.",
                )
            }
        }

        /** U16: the poll that waits for the browser sign-in, while one runs. */
        private var pollJob: Job? = null

        /**
         * U16: the sign-in page is open, so stop offering it. `loginUrl` used
         * to stay set, and the screen's effect re-opened the browser tab
         * whenever it re-ran: on every activity recreation, and on returning
         * to the screen.
         */
        fun onLoginPageOpened() {
            _uiState.update { it.copy(loginUrl = null) }
        }

        /**
         * U16: give up waiting for the browser. Closing the browser left the
         * poll running for its full five minutes with both buttons disabled.
         * If the user finishes signing in in the browser after this, the
         * server issues an app password this device never collects. It shows
         * in their Devices & sessions list and can be revoked there, which is
         * the lesser cost.
         */
        fun cancelLogin() {
            pollJob?.cancel()
            pollJob = null
            _uiState.update { it.copy(isPolling = false, isLoading = false, loginUrl = null) }
        }

        fun dismissError() {
            _uiState.update { it.copy(error = null) }
        }
    }

/**
 * U16: what a failed sign-in start means, in words. The exception's own
 * message ("Failed to connect: Unable to resolve host …", a Kotlin
 * serialization error) used to be shown as it was.
 */
internal fun loginFailureMessage(error: Throwable): String {
    val causes = generateSequence(error) { it.cause }.toList()
    return when {
        causes.any { it is java.net.UnknownHostException } -> {
            "Can't find a server at that address. Check it, and your connection."
        }

        causes.any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException } -> {
            "The server's certificate couldn't be verified."
        }

        causes.any { it is java.net.SocketTimeoutException || it is java.net.ConnectException } -> {
            "The server didn't answer. Check the address and your connection."
        }

        causes.any { it is kotlinx.serialization.SerializationException } -> {
            "That address didn't answer like a Nextcloud server."
        }

        error.message?.startsWith("Server returned ") == true -> {
            "That address didn't offer a Nextcloud sign-in (${error.message?.removePrefix("Server returned ")})."
        }

        else -> {
            "Couldn't reach the server."
        }
    }
}
