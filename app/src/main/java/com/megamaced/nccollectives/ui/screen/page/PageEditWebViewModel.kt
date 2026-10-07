package com.megamaced.nccollectives.ui.screen.page

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.ServerVersionTracker
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.data.auth.TokenStore
import com.megamaced.nccollectives.data.auth.serverHostOf
import com.megamaced.nccollectives.domain.repository.DirectEditingRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * State for [PageEditWebScreen]. The `Loaded(url)` state carries the
 * single-use signed URL returned by `directediting/open`; reloading the
 * WebView with that URL (config change, process death) invalidates the
 * token and forces a re-request, which is why neither this state nor
 * the URL itself is `rememberSaveable` — every fresh ViewModel instance
 * fetches a fresh URL.
 */
sealed interface PageEditWebUiState {
    data object Loading : PageEditWebUiState

    data class Loaded(
        val url: String,
        /**
         * True when the server version changed since the last session, so
         * the WebView should drop its HTTP cache before loading. Carried on
         * the state rather than as a separate flow so it can't race the
         * `loadUrl` it has to precede.
         */
        val clearCacheFirst: Boolean = false,
    ) : PageEditWebUiState

    /** Loaded once but the JS bridge has reported `loaded()`. */
    data class Interactive(
        val url: String,
    ) : PageEditWebUiState

    data class Failed(
        val message: String,
    ) : PageEditWebUiState

    /**
     * Save-and-close is in flight (B-47). [PageEditWebViewModel.onClose]
     * has fired the two Room-refreshing round-trips and the screen is
     * waiting on them. Distinct from [Closed] so the screen can show
     * progress and swallow further close requests, instead of looking
     * frozen for the length of two network calls and fanning out a second
     * pair of requests on the next back-press.
     */
    data object Closing : PageEditWebUiState

    data object Closed : PageEditWebUiState
}

@HiltViewModel
class PageEditWebViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        tokenStore: TokenStore,
        private val directEditingRepository: DirectEditingRepository,
        private val pageRepository: PageRepository,
        private val serverVersionTracker: ServerVersionTracker,
    ) : ViewModel() {
        private val pageId: Long = checkNotNull(
            savedStateHandle.get<Long>(Destination.PageEditWeb.ARG_PAGE_ID),
        )

        private val _uiState = MutableStateFlow<PageEditWebUiState>(PageEditWebUiState.Loading)
        val uiState: StateFlow<PageEditWebUiState> = _uiState.asStateFlow()

        /**
         * S-22: the only host the editor WebView may navigate within, taken
         * from the user's **stored** credentials rather than from the
         * server-supplied `directediting` URL. Derived from that URL, the
         * allowlist self-adjusted to whatever host a compromised or hostile
         * Nextcloud named — and behind a chromeless full-screen WebView with
         * JavaScript on and the `DirectEditingMobileInterface` bridge bound,
         * that is a credential-phishing surface.
         *
         * Read once at construction: the editor is nav-scoped, and a
         * credential change signs the user out and pops the whole graph, so
         * the host can't shift underneath a live session. `null` fails
         * closed — `shouldKeepInWebView` then keeps nothing in the WebView.
         *
         * `serverHostOf` is the same extraction the login flow and
         * `DirectEditingRepositoryImpl`'s session-URL validation measure
         * against, so the gate can't drift from what the repository already
         * accepted.
         */
        val allowedHost: String? = tokenStore.getCredentials()?.host?.let(::serverHostOf)

        /** The `directediting/open` request in flight, if any. */
        private var sessionJob: Job? = null

        init {
            requestSession()
        }

        /**
         * Publish a session outcome unless the user has already left. U8:
         * [leave] closes while the request may still be out, and a late
         * `Loaded` landing over `Closed` before the screen saw it would have
         * left the user in an editor they had backed out of.
         */
        private fun publishSession(state: PageEditWebUiState) {
            _uiState.update { current ->
                if (current is PageEditWebUiState.Closing || current is PageEditWebUiState.Closed) current else state
            }
        }

        /**
         * Fire the OCS `directediting/open` request. Called once on init;
         * may be re-called explicitly on retry. The returned URL is a
         * one-shot token-bearing URL; reloading the WebView with the same
         * URL after `Interactive` has been reached will 410 / redirect to
         * login, so re-entries to the screen create a fresh ViewModel,
         * which fires this again.
         */
        fun requestSession() {
            _uiState.value = PageEditWebUiState.Loading
            sessionJob?.cancel()
            sessionJob = viewModelScope.launch {
                val page = pageRepository.getPage(pageId)
                if (page == null) {
                    publishSession(PageEditWebUiState.Failed("Page is no longer cached locally"))
                    return@launch
                }
                // Probed here rather than on app launch: we're about to make
                // a network round-trip anyway, and the answer only matters
                // when the WebView is about to load Text's assets. Keeps the
                // app's "no network calls the user didn't ask for" posture
                // (v2.3.9, F-Droid) intact.
                val staleAssets = serverVersionTracker.serverVersionChanged()
                // S-22: `openSession` fails the request outright if the URL
                // the server named isn't https on the stored host — the
                // WebView's navigation gate only sees navigations *after*
                // the first load, so the initial URL has to be validated
                // before it ever reaches `loadUrl`.
                when (val result = directEditingRepository.openSession(page)) {
                    is ApiResult.Success -> {
                        publishSession(
                            PageEditWebUiState.Loaded(
                                url = result.data,
                                clearCacheFirst = staleAssets,
                            ),
                        )
                    }

                    else -> {
                        publishSession(
                            PageEditWebUiState.Failed(
                                result.userMessage() ?: "Couldn't open the collaborative editor",
                            ),
                        )
                    }
                }
            }
        }

        /**
         * Surface a load-time failure from outside the OCS request path
         * (Batch 30c — e.g. WebView SSL error). Transitions to
         * [PageEditWebUiState.Failed] so the screen's snackbar fires.
         */
        fun surfaceLoadFailure(message: String) {
            _uiState.value = PageEditWebUiState.Failed(message)
        }

        /**
         * Called by the JS bridge (`reload()`) when Text reports the editing
         * session was invalidated server-side (its `onPushForbidden` path,
         * new at Text v34). The one-shot `directediting/open` token behind
         * the current URL is dead, so we re-request a fresh session rather
         * than reloading the stale URL (which would 410 / bounce to login).
         * `requestSession()` flips state to `Loading`, which tears down the
         * current WebView; the subsequent `Loaded(freshUrl)` mounts a new
         * one — same path a retry or a fresh VM instance takes.
         */
        fun onReloadRequested() {
            requestSession()
        }

        /** Called by the JS bridge once the editor JS has finished bootstrap. */
        fun onEditorReady() {
            _uiState.update { state ->
                when (state) {
                    is PageEditWebUiState.Loaded -> PageEditWebUiState.Interactive(state.url)
                    else -> state
                }
            }
        }

        /**
         * U8: leave the editor, for Back and the toolbar arrow once Text has
         * had its chance to save.
         *
         * While no editor ever ran — the session still being fetched, or
         * refused — there is nothing Text could have saved and nothing to
         * pull back, so this closes at once. It used to run [onClose]'s two
         * round-trips first with Back swallowed, which on a dead network was
         * two connect-and-read timeouts: about a minute and a half on a
         * screen that had shown nothing but a spinner or an error.
         */
        fun leave() {
            when (_uiState.value) {
                PageEditWebUiState.Loading, is PageEditWebUiState.Failed -> {
                    sessionJob?.cancel()
                    _uiState.value = PageEditWebUiState.Closed
                }

                else -> {
                    onClose()
                }
            }
        }

        /**
         * Called from the JS bridge (`close()`), the back press, or the
         * activity finish path. Pulls the server-side autosaved body back
         * into Room so PageView's observe-page Flow re-emits with the
         * fresh content, then transitions to Closed so the screen pops.
         *
         * **Why fetchBody and not just refresh:** `PageRepository.refresh`
         * upserts the page-list metadata but **preserves** the cached body
         * (`existingBody = existing.bodyMd`), so the Room row's bodyMd
         * stays stale — and the read-only view that re-renders on pop
         * shows the pre-edit content even though Text autosaved on the
         * server. `fetchBody` is the WebDAV round-trip that actually
         * replaces the body in Room. We still call refresh so any
         * sibling/tree changes Text might have made (rename, emoji)
         * come back too.
         */

        fun onClose() {
            // B-47: the JS bridge's `close()`, the toolbar close button and
            // the back-press escape hatch can all land inside the same
            // second, and each used to fan out its own pair of round-trips
            // with no feedback on screen. Claim `Closing` atomically first —
            // the bridge calls in on the WebView's own thread, so a plain
            // read-then-write check would still let two callers through.
            while (true) {
                val current = _uiState.value
                if (current is PageEditWebUiState.Closing ||
                    current is PageEditWebUiState.Closed
                ) {
                    return
                }
                if (_uiState.compareAndSet(current, PageEditWebUiState.Closing)) break
            }
            viewModelScope.launch {
                try {
                    // U8: bounded. The page view revalidates its body when the
                    // editor hands back, so a slow or dead network costs a
                    // moment of stale text there rather than holding the
                    // user on this spinner for the full HTTP timeouts.
                    withTimeoutOrNull(CLOSE_SYNC_BUDGET_MS) {
                        pageRepository.getPage(pageId)?.let { page ->
                            pageRepository.refresh(page.collectiveId)
                        }
                        pageRepository.fetchBody(pageId)
                    }
                } finally {
                    // Pop whatever the refresh did: stranding the user on a
                    // spinner that swallows back-presses would be worse than
                    // a stale body, which the next observe-page emission
                    // corrects anyway.
                    _uiState.value = PageEditWebUiState.Closed
                }
            }
        }
    }

/**
 * U8: how long closing the collaborative editor waits for the server's copy
 * of the page before it gives up and leaves the page view to revalidate.
 */
internal const val CLOSE_SYNC_BUDGET_MS = 5_000L
